package dev.lemonadecode.workspace.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest

/** Why a sound can't play. */
internal sealed interface AudioProblem {
  /** There's no sound output here, like on a server or a test machine. */
  data object NoOutput : AudioProblem

  /** Playing it failed; [reason] says why. */
  data class Failed(val reason: String) : AudioProblem
}

/**
 * Plays one sound file through [engine]: whether it plays, and where it is. Only one sound plays at
 * a time: playing this one pauses any other. The sound itself is played by [run], on the engine's
 * thread; the state changes on the main thread.
 */
@Stable
internal class AudioPlayerState(
  private val engine: AudioEngine,
  private val bytes: ByteArray,
  val durationMicros: Long,
  outputAvailable: Boolean,
) {
  var playing: Boolean by mutableStateOf(false)
    private set

  /** Where playback is, in microseconds from the start. */
  var positionMicros: Long by mutableLongStateOf(0L)
    private set

  /** Why it can't play; null while nothing stands in the way. */
  var problem: AudioProblem? by mutableStateOf(if (outputAvailable) null else AudioProblem.NoOutput)
    private set

  val canPlay: Boolean get() = problem != AudioProblem.NoOutput

  /** The playback asked for, from where; compared by identity, so each one starts afresh. */
  private var request: Request? by mutableStateOf(null)

  private class Request(val fromMicros: Long)

  fun play() {
    if (!canPlay) return
    // Played to the end, it starts over.
    if (durationMicros > 0 && positionMicros >= durationMicros) positionMicros = 0
    problem = null
    playing = true
    request = Request(positionMicros)
    AudioFocus.take(this)
  }

  fun pause() {
    playing = false
    request = null
    AudioFocus.release(this)
  }

  fun toggle() = if (playing) pause() else play()

  fun seekTo(micros: Long) {
    positionMicros = micros.coerceIn(0, durationMicros.coerceAtLeast(0))
    if (playing) request = Request(positionMicros)
  }

  /** Plays what's asked for until cancelled; stopping this stops the sound. */
  suspend fun run() {
    snapshotFlow { request }.collectLatest { asked ->
      if (asked == null) return@collectLatest
      try {
        engine.play(bytes, asked.fromMicros).collect { micros ->
          positionMicros = if (durationMicros > 0) micros.coerceIn(0, durationMicros) else micros.coerceAtLeast(0)
        }
        positionMicros = durationMicros
        stopped(null)
      } catch (e: CancellationException) {
        throw e
      } catch (_: AudioUnavailableException) {
        stopped(AudioProblem.NoOutput)
      } catch (e: Exception) {
        stopped(AudioProblem.Failed(e.message ?: e::class.simpleName.orEmpty()))
      }
    }
  }

  private fun stopped(why: AudioProblem?) {
    problem = why
    playing = false
    request = null
    AudioFocus.release(this)
  }
}

/** The one sound playing, so that starting another pauses it. Used on the main thread only. */
internal object AudioFocus {
  private var playing: AudioPlayerState? = null

  fun take(state: AudioPlayerState) {
    val before = playing
    playing = state
    if (before != null && before !== state) before.pause()
  }

  fun release(state: AudioPlayerState) {
    if (playing === state) playing = null
  }
}

/**
 * An [AudioPlayerState] for [bytes], whose sound stops when it leaves the screen: when its file is
 * scrolled away, folded, switched to its diff, or another file or tab shows instead.
 */
@Composable
internal fun rememberAudioPlayer(
  engine: AudioEngine,
  bytes: ByteArray,
  durationMicros: Long,
  outputAvailable: Boolean,
): AudioPlayerState {
  val state = remember(engine, bytes) { AudioPlayerState(engine, bytes, durationMicros, outputAvailable) }
  LaunchedEffect(state) { state.run() }
  DisposableEffect(state) {
    onDispose { state.pause() }
  }
  return state
}
