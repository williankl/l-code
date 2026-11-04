package dev.lemonadecode.workspace.ui.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.Text
import com.teya.lemonade.textStyle
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.preview_pause
import dev.lemonadecode.workspace.strings.preview_play
import dev.lemonadecode.workspace.strings.preview_position
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.TimeSource

/**
 * Plays a [MotionSource]: which frame shows, where playback is, and whether it runs. The frames are
 * read by [run], on a thread of their own; the state changes on the main thread.
 */
@Stable
internal class MotionState(
  val source: MotionSource,
  autoplay: Boolean,
  /** How many times to play through before stopping on the last frame; null loops forever. */
  private val plays: Int?,
) {
  var playing: Boolean by mutableStateOf(autoplay)
    private set

  /** The frame on screen; null until the first is read. */
  var frame: ImageBitmap? by mutableStateOf(null)
    private set

  /** Where playback is, in microseconds from the start. */
  var positionMicros: Long by mutableLongStateOf(0L)
    private set

  private var seek: Seek? by mutableStateOf(null)
  private var completedPlays = 0
  private var finished = false

  /** A request to move; compared by identity, so asking for the same place twice moves twice. */
  private class Seek(val micros: Long)

  fun play() {
    if (finished) {
      finished = false
      completedPlays = 0
      seekTo(0)
    }
    playing = true
  }

  fun pause() {
    playing = false
  }

  fun toggle() = if (playing) pause() else play()

  fun seekTo(micros: Long) {
    val target = micros.coerceIn(0, source.durationMicros.coerceAtLeast(0))
    finished = false
    positionMicros = target
    seek = Seek(target)
  }

  /** Reads and shows frames until cancelled; the frames it made are freed when it ends. */
  suspend fun run(decoder: CoroutineDispatcher) {
    var retired: ImageBitmap? = null
    fun show(next: MotionFrame) {
      // The frame before the one on screen may still be drawing, so the one before that goes.
      retired?.let(::releaseImage)
      retired = frame
      frame = next.image
      positionMicros = next.timeMicros
    }
    try {
      if (frame == null) withContext(decoder) { readNext() }?.let(::show)
      snapshotFlow { playing to seek }.collectLatest { (play, request) ->
        if (request != null) {
          withContext(decoder) {
            guarded { source.seek(request.micros) }
            readNext()
          }?.let(::show)
          seek = null
        } else if (play) {
          playThrough(decoder, ::show)
        }
      }
    } finally {
      retired?.let(::releaseImage)
      frame?.let(::releaseImage)
      frame = null
    }
  }

  private suspend fun playThrough(decoder: CoroutineDispatcher, show: (MotionFrame) -> Unit) {
    var clock = TimeSource.Monotonic.markNow() - positionMicros.microseconds
    var shownUntil = positionMicros
    while (true) {
      val next = withContext(decoder) { readNext() }
      if (next == null) {
        // The last frame stays up for its own time before playback starts over.
        val length = maxOf(source.durationMicros, shownUntil)
        val left = length.microseconds - clock.elapsedNow()
        if (left.isPositive()) delay(left)
        completedPlays++
        if (length <= 0 || (plays != null && completedPlays >= plays)) {
          finished = true
          playing = false
          return
        }
        withContext(decoder) { guarded { source.seek(0) } }
        clock = TimeSource.Monotonic.markNow()
        shownUntil = 0
        continue
      }
      val wait = next.timeMicros.microseconds - clock.elapsedNow()
      if (wait.isPositive()) delay(wait)
      show(next)
      shownUntil = next.timeMicros + next.durationMicros
    }
  }

  /** A frame that can't be read ends playback like the last one does, instead of failing the screen. */
  private fun readNext(): MotionFrame? = guarded { source.next() }

  private inline fun <T> guarded(block: () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    null
  }
}

/** A [MotionState] for [source], playing while it's on screen. [source] is closed when it leaves. */
@Composable
internal fun rememberMotionState(
  source: MotionSource,
  autoplay: Boolean,
  plays: Int? = null,
): MotionState {
  val state = remember(source) { MotionState(source, autoplay, plays) }
  val decoder = remember(source) { Dispatchers.Default.limitedParallelism(1) }
  LaunchedEffect(state) { state.run(decoder) }
  DisposableEffect(source) {
    onDispose {
      // After any frame being read: closing waits for it, and that shouldn't hold up the screen.
      CoroutineScope(decoder + SupervisorJob()).launch { source.close() }
    }
  }
  return state
}

/** The frame on screen, turned upright and scaled into the space like an image. */
@Composable
internal fun MotionFrameView(
  state: MotionState,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Fit,
  filterQuality: FilterQuality = FilterQuality.Medium,
) {
  val rotation = state.source.rotation
  Canvas(modifier.clipToBounds()) {
    val image = state.frame ?: return@Canvas
    val quarterTurn = rotation % 180 != 0
    val upright = if (quarterTurn) Size(image.height.toFloat(), image.width.toFloat()) else Size(image.width.toFloat(), image.height.toFloat())
    val scale = contentScale.computeScaleFactor(upright, size)
    val drawn = Size(upright.width * scale.scaleX, upright.height * scale.scaleY)
    // Drawn as stored, then turned about the middle.
    val stored = if (quarterTurn) Size(drawn.height, drawn.width) else drawn
    val middle = Offset(size.width / 2f, size.height / 2f)
    rotate(rotation.toFloat(), middle) {
      drawImage(
        image = image,
        dstOffset = IntOffset((middle.x - stored.width / 2f).roundToInt(), (middle.y - stored.height / 2f).roundToInt()),
        dstSize = IntSize(stored.width.roundToInt(), stored.height.roundToInt()),
        filterQuality = filterQuality,
      )
    }
  }
}

/** Play or pause, a bar to scrub along, and where playback is, e.g. `0:03 / 0:12`. */
@Composable
internal fun MotionControls(
  state: MotionState,
  position: String,
  modifier: Modifier = Modifier,
) {
  val duration = state.source.durationMicros
  PlaybackControls(
    playing = state.playing,
    fraction = if (duration > 0) (state.positionMicros.toFloat() / duration).coerceIn(0f, 1f) else 0f,
    position = position,
    onPlay = state::play,
    onPause = state::pause,
    onSeek = { fraction -> state.seekTo((fraction * duration).toLong()) },
    seekable = duration > 0,
    modifier = modifier,
  )
}

/**
 * Play or pause, a bar to scrub along filled up to [fraction], and where playback is. Scrubbing
 * pauses, and playback carries on once it's let go. [playable] false greys the button out, for
 * media that can't play here.
 */
@Composable
internal fun PlaybackControls(
  playing: Boolean,
  fraction: Float,
  position: String,
  onPlay: () -> Unit,
  onPause: () -> Unit,
  onSeek: (Float) -> Unit,
  modifier: Modifier = Modifier,
  seekable: Boolean = true,
  playable: Boolean = true,
) {
  val scrub = rememberScrub(playing, onPlay, onPause)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing200),
    modifier = modifier,
  ) {
    PlayPauseButton(playing = playing, enabled = playable, onClick = if (playing) onPause else onPlay)
    SeekBar(
      fraction = fraction,
      enabled = seekable,
      description = stringResource(Res.string.preview_position),
      onScrubStart = scrub::start,
      onScrub = onSeek,
      onScrubEnd = scrub::end,
      modifier = Modifier.weight(1f),
    )
    LemonadeUi.Text(
      text = position,
      textStyle = LemonadeTheme.typography.bodyXSmallRegular.textStyle.copy(
        color = LemonadeTheme.colors.content.contentSecondary,
        fontFeatureSettings = "tnum",
        textAlign = TextAlign.End,
      ),
    )
  }
}

/** Pauses playback while the user scrubs, and plays on once let go if it was playing. */
internal class Scrub(
  private val playing: () -> Boolean,
  private val play: () -> Unit,
  private val pause: () -> Unit,
) {
  private var resume = false

  fun start() {
    resume = playing()
    pause()
  }

  fun end() {
    if (resume) play()
  }
}

@Composable
internal fun rememberScrub(
  playing: Boolean,
  play: () -> Unit,
  pause: () -> Unit,
): Scrub {
  val isPlaying by rememberUpdatedState(playing)
  val currentPlay by rememberUpdatedState(play)
  val currentPause by rememberUpdatedState(pause)
  return remember { Scrub(playing = { isPlaying }, play = { currentPlay() }, pause = { currentPause() }) }
}

/** A round lime button with a drawn play or pause sign: Lemonade has no icons for them. */
@Composable
private fun PlayPauseButton(
  playing: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  val colors = LemonadeTheme.colors
  val interactionSource = remember { MutableInteractionSource() }
  val hovered by interactionSource.collectIsHoveredAsState()
  val label = stringResource(if (playing) Res.string.preview_pause else Res.string.preview_play)
  val sign = colors.content.contentOnBrandHigh
  Box(
    modifier = Modifier
      .size(24.dp)
      .alpha(if (enabled) 1f else LemonadeTheme.opacities.state.opacityDisabled)
      .clip(CircleShape)
      .background(if (hovered && enabled) colors.interaction.bgBrandInteractive else colors.background.bgBrand)
      .hoverable(interactionSource, enabled = enabled)
      .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
      .semantics { contentDescription = label }
      .drawBehind {
        val unit = size.minDimension / 24f
        if (playing) {
          val bar = Size(3f * unit, 10f * unit)
          val top = (size.height - bar.height) / 2f
          val radius = CornerRadius(unit, unit)
          drawRoundRect(sign, topLeft = Offset(size.width / 2f - 4f * unit, top), size = bar, cornerRadius = radius)
          drawRoundRect(sign, topLeft = Offset(size.width / 2f + 1f * unit, top), size = bar, cornerRadius = radius)
        } else {
          // Nudged right of centre, so it looks centred.
          val left = size.width / 2f - 3f * unit
          val path = Path().apply {
            moveTo(left, size.height / 2f - 5f * unit)
            lineTo(left + 9f * unit, size.height / 2f)
            lineTo(left, size.height / 2f + 5f * unit)
            close()
          }
          drawPath(path, sign)
        }
      },
  )
}

/** A track filled up to [fraction]; pressing or dragging along it scrubs. */
@Composable
private fun SeekBar(
  fraction: Float,
  enabled: Boolean,
  description: String,
  onScrubStart: () -> Unit,
  onScrub: (Float) -> Unit,
  onScrubEnd: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = LemonadeTheme.colors
  val track = colors.border.borderNeutralMedium
  val fill = if (enabled) colors.background.bgBrand else colors.border.borderNeutralHigh
  val currentScrub by rememberUpdatedState(onScrub)
  Box(
    modifier = modifier
      .height(20.dp)
      .semantics {
        contentDescription = description
        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        if (enabled) {
          setProgress { target ->
            currentScrub(target.coerceIn(0f, 1f))
            true
          }
        }
      }
      .scrubbing(enabled, onScrubStart, onScrub, onScrubEnd)
      .drawBehind {
        val thickness = 4.dp.toPx()
        val thumb = 5.dp.toPx()
        val left = thumb
        val width = (size.width - 2 * thumb).coerceAtLeast(0f)
        val top = (size.height - thickness) / 2f
        val radius = CornerRadius(thickness / 2f, thickness / 2f)
        drawRoundRect(track, topLeft = Offset(left, top), size = Size(width, thickness), cornerRadius = radius)
        val filled = width * fraction
        if (filled > 0f) drawRoundRect(fill, topLeft = Offset(left, top), size = Size(filled, thickness), cornerRadius = radius)
        if (enabled) drawCircle(fill, radius = thumb, center = Offset(left + filled, size.height / 2f))
      },
  )
}

/**
 * Pressing or dragging along the element scrubs: [onScrub] hears where, as a fraction of [span] of
 * its width (all of it by default), between [onStart] and [onEnd].
 */
@Composable
internal fun Modifier.scrubbing(
  enabled: Boolean,
  onStart: () -> Unit,
  onScrub: (Float) -> Unit,
  onEnd: () -> Unit,
  span: () -> Float = { 1f },
): Modifier {
  val currentStart by rememberUpdatedState(onStart)
  val currentScrub by rememberUpdatedState(onScrub)
  val currentEnd by rememberUpdatedState(onEnd)
  val currentSpan by rememberUpdatedState(span)
  return pointerInput(enabled) {
    if (!enabled) return@pointerInput
    fun fractionAt(x: Float): Float {
      val width = size.width * currentSpan().coerceIn(0f, 1f)
      return if (width <= 0f) 0f else (x / width).coerceIn(0f, 1f)
    }
    awaitEachGesture {
      val down = awaitFirstDown()
      down.consume()
      currentStart()
      try {
        currentScrub(fractionAt(down.position.x))
        drag(down.id) { change ->
          change.consume()
          currentScrub(fractionAt(change.position.x))
        }
      } finally {
        currentEnd()
      }
    }
  }
}
