package dev.lemonadecode.domain.mobile

import kotlin.math.abs
import kotlin.math.roundToInt

/** The sizes a screen's video is streamed at, mirrored and recorded. */
public object ScreenVideoSize {
  /** The longest side the mirror streams a screen at: plenty for the panel, and light on Wi‑Fi. */
  public const val MaxMirrorSide: Int = 1280

  private const val EncoderStep = 8

  /**
   * The size the mirror streams a screen [width] by [height] at: shrunk so its longer side is at
   * most [MaxMirrorSide], with both sides multiples of 8, which every encoder takes.
   * `screenrecord` keeps the screen's shape and fills the rest of the picture with black, so a
   * size whose shape is the screen's, within a pixel, is looked for.
   */
  public fun mirror(width: Int, height: Int): Pair<Int, Int> {
    val long = maxOf(width, height, 1)
    val ratio = minOf(width, height).coerceAtLeast(1).toDouble() / long
    val top = maxOf(EncoderStep, minOf(long, MaxMirrorSide) / EncoderStep * EncoderStep)
    fun shortFor(side: Int) = maxOf(EncoderStep, (side * ratio / EncoderStep).roundToInt() * EncoderStep)
    fun error(side: Int) = abs(shortFor(side) - side * ratio)
    val best = (top downTo maxOf(EncoderStep, top * 3 / 4) step EncoderStep)
      .firstOrNull { error(it) < 1.0 }
      ?: (top downTo maxOf(EncoderStep, top * 3 / 4) step EncoderStep).minBy(::error)
    return if (width >= height) best to shortFor(best) else shortFor(best) to best
  }

  /** The size a screen [width] by [height] is recorded at in full: its own, each side made even, as encoders need. */
  public fun full(width: Int, height: Int): Pair<Int, Int> = (width.coerceAtLeast(2) / 2 * 2) to (height.coerceAtLeast(2) / 2 * 2)

  /** The size [resolution] records a screen [width] by [height] at. */
  public fun of(resolution: RecordingResolution, width: Int, height: Int): Pair<Int, Int> = when (resolution) {
    RecordingResolution.Full -> full(width, height)
    RecordingResolution.Mirror -> mirror(width, height)
  }
}
