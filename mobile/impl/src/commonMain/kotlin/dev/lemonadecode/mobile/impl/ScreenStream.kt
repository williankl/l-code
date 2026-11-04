package dev.lemonadecode.mobile.impl

import dev.lemonadecode.domain.mobile.DeviceScreen
import dev.lemonadecode.domain.mobile.ScreenPicture
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Decodes a video one access unit at a time. Used by one reader at a time. */
internal interface ScreenDecoder : AutoCloseable {
  /** The pictures [accessUnit] completes: usually one; none for parameter sets or a damaged unit. */
  fun decode(accessUnit: ByteArray): List<ScreenPicture.Bgra>
}

/** Makes [ScreenDecoder]s. */
internal fun interface ScreenDecoders {
  /** A new H.264 decoder; throws when there's none to be had. */
  fun h264(): ScreenDecoder
}

/**
 * A device's screen as a video while collected. [size] says how big the screen is now, in the
 * device's pixels, and [stream] records it at that size. The recording starts again when it ends,
 * as `screenrecord` does after a few minutes, and when the screen turns, which a recording would
 * only squeeze into its old size. Until the size is known, and for good once recording has failed
 * a few times running, it falls back on [capture]'s [Screenshots].
 */
internal fun mirrorScreens(
  timeSource: TimeSource.WithComparableMarks,
  size: suspend () -> Pair<Int, Int>?,
  stream: (width: Int, height: Int) -> Flow<ScreenPicture.Bgra>,
  capture: suspend () -> Capture,
): Flow<DeviceScreen> = channelFlow {
  send(DeviceScreen.Loading)
  val rate = FrameRate(timeSource)
  val screenshots = Screenshots(rate, capture)
  var failures = 0
  while (true) {
    val screen = if (failures < MaxRecordingFailures) size() else null
    if (screen == null) {
      screenshots.next { send(it) }
      continue
    }
    val (width, height) = screen
    var frames = 0
    coroutineScope {
      val recording = launch {
        stream(width, height).catch { }.collect { picture ->
          frames++
          val frame = DeviceScreen.Frame(picture, width, height, rate.tick())
          screenshots.shown = frame
          send(frame)
        }
      }
      val turning = launch {
        while (true) {
          delay(TurnCheck)
          val now = size()
          if (now != null && now != screen) break
        }
        recording.cancel()
      }
      recording.join()
      turning.cancel()
    }
    if (frames == 0) {
      failures++
      delay(RecordingRetry)
    } else {
      failures = 0
      // A breath before starting again, so a device whose recordings end at once isn't asked nonstop.
      delay(RecordingRestart)
    }
  }
}

/**
 * The pictures a video's [chunks] hold, as they come. A picture whose end the stream hasn't shown
 * yet is decoded once nothing more has come for [QuietFlush], so the screen's last change shows.
 */
internal fun decodeVideo(chunks: Flow<ByteArray>, decoders: ScreenDecoders): Flow<ScreenPicture.Bgra> = channelFlow {
  val decoder = decoders.h264()
  try {
    val splitter = AccessUnitSplitter()
    val incoming = produce(capacity = Channel.BUFFERED) { chunks.collect { send(it) } }
    while (true) {
      val chunk = withTimeoutOrNull(QuietFlush) { incoming.receiveCatching() }
      val units = when {
        chunk == null -> listOfNotNull(splitter.flush())
        chunk.isClosed -> {
          chunk.exceptionOrNull()?.let { throw it }
          listOfNotNull(splitter.flush())
        }
        else -> splitter.feed(chunk.getOrThrow())
      }
      for (unit in units) decoder.decode(unit).forEach { send(it) }
      if (chunk?.isClosed == true) break
    }
  } finally {
    decoder.close()
  }
}

/**
 * The size a screen [width] by [height] is recorded at: shrunk so its longer side is at most
 * [MaxRecordingSide], which is plenty for the panel and light on Wi‑Fi, in multiples of 8, which
 * every encoder takes.
 */
internal fun recordingSize(width: Int, height: Int): Pair<Int, Int> {
  val scale = minOf(1.0, MaxRecordingSide.toDouble() / maxOf(width, height, 1))
  fun fit(side: Int) = maxOf(EncoderStep, (side * scale).toInt() / EncoderStep * EncoderStep)
  return fit(width) to fit(height)
}

internal const val MaxRecordingSide = 1280
private const val EncoderStep = 8

/** Recording failures in a row after which only screenshots are taken. */
private const val MaxRecordingFailures = 3

/** How often the screen's size is looked at while recording, to notice it turning. */
internal val TurnCheck: Duration = 1.seconds

/** The pause before recording again after a recording gave nothing. */
internal val RecordingRetry: Duration = 500.milliseconds

/** The pause before recording again after a recording ended or the screen turned. */
internal val RecordingRestart: Duration = 100.milliseconds

/** How long the stream has to stay quiet for the picture in hand to count as whole. */
internal val QuietFlush: Duration = 40.milliseconds
