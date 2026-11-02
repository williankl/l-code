package dev.lemonadecode.workspace.ui.preview

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Moving pictures read one frame at a time: an animated GIF or WebP, or a video. Not thread-safe:
 * one caller at a time. Reading after [close] finds no more frames rather than failing.
 */
internal interface MotionSource : AutoCloseable {
  /** The picture's size as shown, in pixels: frames turned by [rotation] fill it. */
  val width: Int
  val height: Int

  /** Degrees clockwise each frame is turned to stand upright: 0, 90, 180 or 270. */
  val rotation: Int

  /** How long one play-through lasts, or 0 when it isn't known. */
  val durationMicros: Long

  /** Moves so that [next] returns the frame showing at [micros]. */
  fun seek(micros: Long)

  /** The next frame, or null after the last. The caller owns its image; [releaseImage] frees it. */
  fun next(): MotionFrame?
}

/** A frame shown from [timeMicros] for [durationMicros]. */
internal class MotionFrame(
  val image: ImageBitmap,
  val timeMicros: Long,
  val durationMicros: Long,
)

/** An animated GIF or WebP, decoded a frame at a time. Safe to use from any thread. */
internal interface ImageAnimation : AutoCloseable {
  /** In pixels. */
  val width: Int
  val height: Int
  val frameCount: Int

  /** How many times it plays through before stopping on its last frame, or null to loop forever. */
  val plays: Int?

  /** How long frame [index] shows, in milliseconds. Delays browsers treat as missing count as 100 ms. */
  fun durationMillis(index: Int): Int

  /**
   * Frame [index] as it looks on screen, earlier frames it builds on included. Cheapest in order.
   * The caller owns the image; [releaseImage] frees it. A closed animation draws blank frames.
   */
  fun frame(index: Int): ImageBitmap
}

/**
 * [bytes] as an animation, or null when they hold a still picture or can't be read. Decodes the
 * header only, but still call it off the main thread. Close it when it's no longer drawn.
 */
internal expect fun decodeAnimation(bytes: ByteArray): ImageAnimation?

/**
 * A video read from [bytes], its frames shrunk to fit [maxWidth] × [maxHeight] pixels. Slow, since
 * the first video unpacks FFmpeg: call it off the main thread. Throws when the video can't be read;
 * the message says why.
 */
internal expect fun openVideo(bytes: ByteArray, maxWidth: Int, maxHeight: Int): MotionSource

/** [millis] as seconds with one decimal, written the way the user's locale writes numbers: 2.4, or 2,4. */
internal expect fun formatSeconds(millis: Long): String

/** Plays [animation] frame by frame. Closing it leaves the animation open: its owner closes that. */
internal class AnimationPlayback(private val animation: ImageAnimation) : MotionSource {
  /** When each frame starts showing, and after the last, when the animation ends. */
  private val starts = LongArray(animation.frameCount + 1).also { starts ->
    for (index in 0 until animation.frameCount) starts[index + 1] = starts[index] + animation.durationMillis(index) * 1_000L
  }
  private var index = 0

  override val width: Int get() = animation.width
  override val height: Int get() = animation.height
  override val rotation: Int get() = 0
  override val durationMicros: Long get() = starts.last()

  val frameCount: Int get() = animation.frameCount

  /** The frame showing at [micros]. */
  fun frameAt(micros: Long): Int {
    if (micros <= 0) return 0
    var low = 0
    var high = animation.frameCount - 1
    while (low < high) {
      val middle = (low + high + 1) / 2
      if (starts[middle] <= micros) low = middle else high = middle - 1
    }
    return low
  }

  override fun seek(micros: Long) {
    index = frameAt(micros)
  }

  override fun next(): MotionFrame? {
    if (index >= animation.frameCount) return null
    val frame = MotionFrame(animation.frame(index), starts[index], starts[index + 1] - starts[index])
    index++
    return frame
  }

  override fun close() = Unit
}

/** Media time the way players show it: 0:07, 1:05:09. */
internal fun formatMediaTime(micros: Long): String {
  val seconds = (micros.coerceAtLeast(0) / 1_000_000).toInt()
  val hours = seconds / 3600
  val minutes = seconds / 60 % 60
  val rest = seconds % 60
  val secondsText = rest.toString().padStart(2, '0')
  return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$secondsText" else "$minutes:$secondsText"
}
