package dev.lemonadecode.mobile.impl

import dev.lemonadecode.domain.mobile.DeviceScreen
import dev.lemonadecode.domain.mobile.ScreenPicture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.ComparableTimeMark
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
 * only squeeze into its old size; [turned] says it was just turned from here, so its size is
 * looked at again straight away. Until the size is known, and once recording has failed a few
 * times running, it falls back on [capture]'s [Screenshots], trying to record again now and then.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun mirrorScreens(
  timeSource: TimeSource.WithComparableMarks,
  size: suspend () -> Pair<Int, Int>?,
  stream: (width: Int, height: Int) -> Flow<ScreenPicture.Bgra>,
  capture: suspend () -> Capture,
  turned: Flow<Unit> = emptyFlow(),
): Flow<DeviceScreen> = channelFlow {
  send(DeviceScreen.Loading)
  val rate = FrameRate(timeSource)
  val screenshots = Screenshots(rate, capture)
  var failures = 0
  var gaveUpAt: ComparableTimeMark? = null
  while (true) {
    if (failures >= MaxRecordingFailures) {
      val since = gaveUpAt ?: timeSource.markNow().also { gaveUpAt = it }
      if (since.elapsedNow() < RecordingAgainAfter) {
        screenshots.next { send(it) }
        continue
      }
      // One more try; another failure goes back to screenshots for a while.
      failures = MaxRecordingFailures - 1
      gaveUpAt = null
    }
    val screen = size()
    if (screen == null) {
      screenshots.next { send(it) }
      continue
    }
    val (width, height) = screen
    var frames = 0
    var turnedAway = false
    coroutineScope {
      val shown = CompletableDeferred<Unit>()
      val recording = launch {
        stream(width, height).catch { }.collect { picture ->
          frames++
          shown.complete(Unit)
          val frame = DeviceScreen.Frame(picture, width, height, rate.tick())
          screenshots.shown = frame
          send(frame)
        }
      }
      // A recording that never shows a first picture, e.g. of a screen that's off, has failed.
      val waiting = launch {
        if (withTimeoutOrNull(FirstPictureWait) { shown.await() } == null) recording.cancel()
      }
      val nudges = turned.produceIn(this)
      val turning = launch {
        var quickChecks = 0
        var nudgesOpen = true
        while (true) {
          val nudged = select {
            if (nudgesOpen) nudges.onReceiveCatching { it.isSuccess.also { open -> nudgesOpen = open } }
            onTimeout(if (quickChecks > 0) QuickTurnCheck else TurnCheck) { false }
          }
          quickChecks = if (nudged) QuickTurnChecks else (quickChecks - 1).coerceAtLeast(0)
          val now = size()
          if (now != null && now != screen) break
        }
        turnedAway = true
        recording.cancel()
      }
      recording.join()
      waiting.cancel()
      turning.cancel()
      nudges.cancel()
    }
    when {
      turnedAway -> delay(RecordingRestart)
      frames == 0 -> {
        failures++
        delay(RecordingRetry)
      }
      else -> {
        failures = 0
        // A breath before starting again, so a device whose recordings end at once isn't asked nonstop.
        delay(RecordingRestart)
      }
    }
  }
}

/**
 * The pictures a video's [chunks] hold, as they come. A picture whose end the stream hasn't shown
 * yet is decoded once nothing more has come for [quiet], so the screen's last change shows. When
 * that turns out to have been a pause in the middle of the picture, the decoder has a damaged one
 * that the pictures after it build on, so the flow ends there, for a new recording to start whole.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun decodeVideo(chunks: Flow<ByteArray>, decoders: ScreenDecoders, quiet: Duration = QuietFlush): Flow<ScreenPicture.Bgra> = channelFlow {
  val decoder = decoders.h264()
  try {
    val splitter = AccessUnitSplitter()
    val incoming = produce(capacity = Channel.BUFFERED) { chunks.collect { send(it) } }
    while (true) {
      // Unlike a timeout around a receive, a select never loses a chunk that comes at the last moment.
      val chunk = select {
        incoming.onReceiveCatching { it }
        onTimeout(quiet) { null }
      }
      val units = when {
        chunk == null -> listOfNotNull(splitter.flush())
        chunk.isClosed -> {
          chunk.exceptionOrNull()?.let { throw it }
          listOfNotNull(splitter.flush())
        }
        else -> splitter.feed(chunk.getOrThrow())
      }
      if (splitter.cutShort) {
        incoming.cancel()
        break
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
 * [MaxRecordingSide], which is plenty for the panel and light on Wi‑Fi, with both sides multiples
 * of 8, which every encoder takes. `screenrecord` keeps the screen's shape and fills the rest of
 * the picture with black, so a size whose shape is the screen's, within a pixel, is looked for.
 */
internal fun recordingSize(width: Int, height: Int): Pair<Int, Int> {
  val long = maxOf(width, height, 1)
  val ratio = minOf(width, height).coerceAtLeast(1).toDouble() / long
  val top = maxOf(EncoderStep, minOf(long, MaxRecordingSide) / EncoderStep * EncoderStep)
  fun shortFor(side: Int) = maxOf(EncoderStep, (side * ratio / EncoderStep).roundToInt() * EncoderStep)
  fun error(side: Int) = abs(shortFor(side) - side * ratio)
  val best = (top downTo maxOf(EncoderStep, top * 3 / 4) step EncoderStep)
    .firstOrNull { error(it) < 1.0 }
    ?: (top downTo maxOf(EncoderStep, top * 3 / 4) step EncoderStep).minBy(::error)
  return if (width >= height) best to shortFor(best) else shortFor(best) to best
}

internal const val MaxRecordingSide = 1280
private const val EncoderStep = 8

/** Recording failures in a row after which only screenshots are taken, for [RecordingAgainAfter]. */
private const val MaxRecordingFailures = 3

/** How long screenshots stand in after recording failed, before recording is tried again. */
internal val RecordingAgainAfter: Duration = 30.seconds

/** How long a new recording may take to show its first picture. */
internal val FirstPictureWait: Duration = 5.seconds

/** How often the screen's size is looked at while recording, to notice it turning. */
internal val TurnCheck: Duration = 1.seconds

/** After the screen was turned from here, its size is looked at this often, [QuickTurnChecks] times. */
internal val QuickTurnCheck: Duration = 100.milliseconds
private const val QuickTurnChecks = 30

/** The pause before recording again after a recording gave nothing. */
internal val RecordingRetry: Duration = 500.milliseconds

/** The pause before recording again after a recording ended or the screen turned. */
internal val RecordingRestart: Duration = 100.milliseconds

/** How long the stream has to stay quiet for the picture in hand to count as whole, over a cable. */
internal val QuietFlush: Duration = 40.milliseconds

/** The same over Wi‑Fi, where a picture's bytes can pause on their way. */
internal val WifiQuietFlush: Duration = 120.milliseconds
