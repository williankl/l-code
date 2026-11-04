package dev.lemonadecode.mobile.impl

import dev.lemonadecode.domain.mobile.DeviceScreen
import dev.lemonadecode.domain.mobile.ScreenPicture
import dev.lemonadecode.domain.mobile.ScreenUnavailable
import kotlinx.coroutines.delay
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/** One try at capturing a screen. */
internal sealed interface Capture {
  class Picture(val png: PngPicture) : Capture

  /** [message] is what the tool printed, for a screenshot's error. */
  data class Unavailable(val reason: ScreenUnavailable, val message: String) : Capture
}

/**
 * Screenshots one at a time, each followed by a pause: short after a picture, so the device isn't
 * kept busy, and longer after each failure, longest when the device can't be captured at all.
 */
internal class Screenshots(private val rate: FrameRate, private val capture: suspend () -> Capture) {
  private var failures = 0

  /** What was sent last, so a screen that stays unavailable isn't sent again. */
  var shown: DeviceScreen = DeviceScreen.Loading

  /** Takes one screenshot, sends what it shows when that's new, then waits. */
  suspend fun next(send: suspend (DeviceScreen) -> Unit) {
    when (val shot = capture()) {
      is Capture.Picture -> {
        failures = 0
        val frame = DeviceScreen.Frame(ScreenPicture.Encoded(shot.png.bytes), shot.png.width, shot.png.height, rate.tick())
        shown = frame
        send(frame)
        delay(FrameGap)
      }
      is Capture.Unavailable -> {
        failures++
        rate.reset()
        val screen = DeviceScreen.Unavailable(shot.reason)
        if (screen != shown) send(screen)
        shown = screen
        delay(retryDelay(shot.reason, failures))
      }
    }
  }
}

private fun retryDelay(reason: ScreenUnavailable, failures: Int): Duration = when (reason) {
  ScreenUnavailable.CableOnly -> 3.seconds
  ScreenUnavailable.NotSupported -> 15.seconds
  ScreenUnavailable.Failed -> (500.milliseconds * (1 shl (failures - 1).coerceIn(0, 5))).coerceAtMost(10.seconds)
}

/** The pause between one frame and the next capture, so the device isn't kept busy. */
internal val FrameGap: Duration = 120.milliseconds

/** How often frames have been arriving, over the last few. */
internal class FrameRate(private val timeSource: TimeSource.WithComparableMarks, private val window: Int = 10) {
  private val marks = ArrayDeque<ComparableTimeMark>()

  /** Counts a frame now; null until there are two. */
  fun tick(): Float? {
    marks.addLast(timeSource.markNow())
    while (marks.size > window) marks.removeFirst()
    if (marks.size < 2) return null
    val elapsed = marks.last() - marks.first()
    if (elapsed <= Duration.ZERO) return null
    return ((marks.size - 1) / elapsed.toDouble(DurationUnit.SECONDS)).toFloat()
  }

  fun reset() = marks.clear()
}
