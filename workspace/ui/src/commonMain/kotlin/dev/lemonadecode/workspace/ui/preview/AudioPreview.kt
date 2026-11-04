package dev.lemonadecode.workspace.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.Text
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.preview_audio_bits
import dev.lemonadecode.workspace.strings.preview_audio_channels
import dev.lemonadecode.workspace.strings.preview_audio_kbps
import dev.lemonadecode.workspace.strings.preview_audio_khz
import dev.lemonadecode.workspace.strings.preview_audio_mono
import dev.lemonadecode.workspace.strings.preview_audio_no_output
import dev.lemonadecode.workspace.strings.preview_audio_play_failed
import dev.lemonadecode.workspace.strings.preview_audio_stereo
import dev.lemonadecode.workspace.strings.preview_audio_waveform
import dev.lemonadecode.workspace.strings.preview_loading
import dev.lemonadecode.workspace.strings.preview_seconds
import dev.lemonadecode.workspace.strings.preview_unreadable
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.sqrt

private val WaveformHeight = 72.dp
private val BarWidth = 2.dp
private val BarGap = 1.dp

private sealed interface AudioLoad {
  data object Loading : AudioLoad

  class Failed(val reason: String) : AudioLoad

  class Ready(val clip: AudioClip, val outputAvailable: Boolean) : AudioLoad
}

/**
 * A sound file: its waveform, with controls to play, pause and scrub, and what it is (length,
 * codec, sample rate, channels, bitrate). Where there's no sound output it still draws, and says it
 * can't play.
 */
@Composable
internal fun AudioPreview(bytes: ByteArray) {
  val engine = LocalAudioEngine.current
  val load by produceState<AudioLoad>(AudioLoad.Loading, bytes, engine) {
    value = try {
      AudioLoad.Ready(engine.read(bytes), engine.outputAvailable())
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      AudioLoad.Failed(e.message ?: e::class.simpleName.orEmpty())
    }
  }
  when (val state = load) {
    AudioLoad.Loading -> PreviewMessage(stringResource(Res.string.preview_loading), loading = true)
    is AudioLoad.Failed -> PreviewMessage(stringResource(Res.string.preview_unreadable, state.reason))
    is AudioLoad.Ready -> AudioPlayer(
      clip = state.clip,
      state = rememberAudioPlayer(engine, bytes, state.clip.durationMicros, state.outputAvailable),
      sizeBytes = bytes.size.toLong(),
    )
  }
}

@Composable
private fun AudioPlayer(
  clip: AudioClip,
  state: AudioPlayerState,
  sizeBytes: Long,
) {
  val duration = clip.durationMicros
  val timeline = LocalAudioTimeline.current
  DisposableEffect(timeline, state) {
    timeline?.add(state, duration)
    onDispose { timeline?.remove(state) }
  }
  val span = timeline?.span(duration) ?: 1f
  val fraction = if (duration > 0) (state.positionMicros.toFloat() / duration).coerceIn(0f, 1f) else 0f
  val seek: (Float) -> Unit = { state.seekTo((it * duration).toLong()) }
  val spaces = LemonadeTheme.spaces
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(spaces.spacing200),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(WaveformHeight)
        .clip(LemonadeTheme.shapes.radius150)
        .background(LemonadeTheme.colors.background.bgDefault)
        .padding(horizontal = spaces.spacing300, vertical = spaces.spacing200),
    ) {
      val scrub = rememberScrub(state.playing, state::play, state::pause)
      WaveformView(
        waveform = clip.waveform,
        span = span,
        fraction = { if (duration > 0) (state.positionMicros.toFloat() / duration).coerceIn(0f, 1f) else 0f },
        modifier = Modifier
          .fillMaxSize()
          .scrubbing(enabled = duration > 0, onStart = scrub::start, onScrub = seek, onEnd = scrub::end, span = { span }),
      )
    }
    PlaybackControls(
      playing = state.playing,
      fraction = fraction,
      position = "${formatAudioTime(state.positionMicros, duration)} / ${formatAudioTime(duration, duration)}",
      onPlay = state::play,
      onPause = state::pause,
      onSeek = seek,
      seekable = duration > 0,
      playable = state.canPlay,
      modifier = Modifier.fillMaxWidth(),
    )
    state.problem?.let { problem ->
      LemonadeUi.Text(
        text = when (problem) {
          AudioProblem.NoOutput -> stringResource(Res.string.preview_audio_no_output)
          is AudioProblem.Failed -> stringResource(Res.string.preview_audio_play_failed, problem.reason)
        },
        textStyle = LemonadeTheme.typography.bodyXSmallRegular,
        color = LemonadeTheme.colors.content.contentSecondary,
        textAlign = TextAlign.Center,
      )
    }
    LemonadeUi.Text(
      text = audioFacts(clip, sizeBytes),
      textStyle = LemonadeTheme.typography.bodyXSmallRegular,
      color = LemonadeTheme.colors.content.contentSecondary,
      textAlign = TextAlign.Center,
    )
  }
}

/**
 * The waveform as bars, mirrored about the middle: each the loudest moment of its stretch, faint,
 * over how loud the stretch is on the whole. Bars already played are lime. The waveform covers
 * [span] of the width, so sides of one change can share a time scale; past it there's only a line.
 */
@Composable
private fun WaveformView(
  waveform: Waveform,
  span: Float,
  fraction: () -> Float,
  modifier: Modifier = Modifier,
) {
  val colors = LemonadeTheme.colors
  val played = colors.background.bgBrand
  val unplayed = colors.content.contentTertiary
  val beyond = colors.border.borderNeutralMedium
  val description = stringResource(Res.string.preview_audio_waveform)
  Box(
    modifier = modifier
      .semantics { contentDescription = description }
      .drawWithCache {
        val barWidth = BarWidth.toPx()
        val step = barWidth + BarGap.toPx()
        val spanWidth = size.width * span.coerceIn(0f, 1f)
        val bars = max(1, ((spanWidth + BarGap.toPx()) / step).toInt())
        val peaks = FloatArray(bars)
        val loudness = FloatArray(bars)
        for (bar in 0 until bars) {
          val (peak, rms) = waveform.levels(bar / bars.toFloat(), (bar + 1) / bars.toFloat())
          // Heights follow the square root of the level, the way ears hear it more than a meter
          // does: quiet passages still show, and louder still stands taller.
          peaks[bar] = sqrt(peak)
          loudness[bar] = sqrt(rms)
        }
        val middle = size.height / 2f
        val least = 0.5.dp.toPx()
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
        val drawnWidth = bars * step - BarGap.toPx()
        onDrawBehind {
          val playedBars = (fraction() * bars).toInt()
          for (bar in 0 until bars) {
            val color = if (bar < playedBars) played else unplayed
            val x = bar * step
            val peakHalf = max(least, peaks[bar] * middle)
            drawRoundRect(
              color.copy(alpha = color.alpha * 0.4f),
              topLeft = Offset(x, middle - peakHalf),
              size = Size(barWidth, peakHalf * 2f),
              cornerRadius = radius,
            )
            val loudHalf = max(least, loudness[bar] * middle)
            drawRoundRect(color, topLeft = Offset(x, middle - loudHalf), size = Size(barWidth, loudHalf * 2f), cornerRadius = radius)
          }
          if (drawnWidth + step < size.width) {
            drawLine(beyond, Offset(drawnWidth + step, middle), Offset(size.width, middle), strokeWidth = 1.dp.toPx())
          }
        }
      },
  )
}

/** What a sound is, e.g. `1.2 s · PCM · 44.1 kHz · 16-bit · Mono · 706 kb/s · 104 KB`. */
@Composable
private fun audioFacts(
  clip: AudioClip,
  sizeBytes: Long,
): String {
  val duration = clip.durationMicros
  val facts = mutableListOf<String>()
  facts += if (duration < 60_000_000) stringResource(Res.string.preview_seconds, formatSeconds(duration / 1_000)) else formatMediaTime(duration)
  clip.codec?.let { facts += it }
  if (clip.sampleRate > 0) facts += stringResource(Res.string.preview_audio_khz, formatDecimal(clip.sampleRate / 1000.0, 3))
  clip.bitsPerSample?.let { facts += stringResource(Res.string.preview_audio_bits, it) }
  facts += when (clip.channels) {
    1 -> stringResource(Res.string.preview_audio_mono)
    2 -> stringResource(Res.string.preview_audio_stereo)
    else -> pluralStringResource(Res.plurals.preview_audio_channels, clip.channels, clip.channels)
  }
  clip.bitrate?.let { facts += stringResource(Res.string.preview_audio_kbps, formatCount(((it + 500) / 1000).toInt())) }
  facts += formatBytes(sizeBytes)
  return facts.joinToString(" · ")
}

/**
 * The lengths of the sides of one sound's preview, shown side by side, so their waveforms share a
 * time scale: a sound that got longer or shorter shows it at a glance.
 */
@Stable
internal class AudioTimeline {
  private val lengths = mutableStateMapOf<Any, Long>()

  fun add(side: Any, lengthMicros: Long) {
    lengths[side] = lengthMicros
  }

  fun remove(side: Any) {
    lengths.remove(side)
  }

  /** How much of the shared width a side [lengthMicros] long covers. */
  fun span(lengthMicros: Long): Float {
    val longest = lengths.values.maxOrNull() ?: 0L
    return if (longest <= 0 || lengthMicros <= 0) 1f else (lengthMicros.toFloat() / longest).coerceIn(0f, 1f)
  }
}

/** The time scale the sound previews below share; null where each has its own. */
internal val LocalAudioTimeline = compositionLocalOf<AudioTimeline?> { null }
