package dev.lemonadecode.workspace.ui.preview

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionDecodingTest {
  private val opened = mutableListOf<AutoCloseable>()

  @AfterTest
  fun closeAll() = opened.forEach { it.close() }

  private fun <T : AutoCloseable> T.closedAfter(): T = also { opened += it }

  @Test
  fun `an animated GIF draws each frame whole, on top of the ones before it`() {
    val animation = assertNotNull(decodeAnimation(LoopingGif)).closedAfter()
    assertEquals(4, animation.width)
    assertEquals(4, animation.height)
    assertEquals(3, animation.frameCount)
    assertNull(animation.plays, "loops forever")

    assertColor(Red, animation.frame(0), 3, 3)
    // The second frame only covers the top-left corner; the rest is still the first frame.
    val second = animation.frame(1)
    assertColor(Blue, second, 0, 0)
    assertColor(Red, second, 3, 3)
    assertColor(Green, animation.frame(2), 0, 0)
    // Out of order, too.
    assertColor(Blue, animation.frame(1), 1, 1)
    assertColor(Red, animation.frame(1), 2, 2)
  }

  @Test
  fun `frames show for their delays, and delays browsers skip count as 100 ms`() {
    val animation = assertNotNull(decodeAnimation(LoopingGif)).closedAfter()
    assertEquals(listOf(100, 200, 100), List(animation.frameCount) { animation.durationMillis(it) })
  }

  @Test
  fun `a GIF without a loop count plays once`() {
    assertEquals(1, assertNotNull(decodeAnimation(OnceGif)).closedAfter().plays)
  }

  @Test
  fun `an animated WebP plays too`() {
    val animation = assertNotNull(decodeAnimation(AnimatedWebp)).closedAfter()
    assertEquals(2, animation.frameCount)
    assertEquals(listOf(120, 80), List(animation.frameCount) { animation.durationMillis(it) })
    assertColor(Blue, animation.frame(1), 0, 0)
  }

  @Test
  fun `still pictures and unreadable bytes aren't animations`() {
    assertNull(decodeAnimation(StillGif))
    assertNull(decodeAnimation(byteArrayOf(1, 2, 3)))
  }

  @Test
  fun `a closed animation draws blank frames instead of failing`() {
    val animation = assertNotNull(decodeAnimation(LoopingGif))
    animation.close()
    assertEquals(1, animation.frame(1).width)
  }

  @Test
  fun `an animation plays frame after frame, and seeks to the frame showing at a time`() {
    val animation = assertNotNull(decodeAnimation(LoopingGif)).closedAfter()
    val playback = AnimationPlayback(animation)
    assertEquals(400_000, playback.durationMicros)
    assertEquals(listOf(0, 0, 1, 1, 2, 2), listOf(0L, 99_999, 100_000, 299_999, 300_000, 9_000_000).map(playback::frameAt))

    assertEquals(listOf(0L, 100_000, 300_000), generateSequence { playback.next() }.map { it.timeMicros }.toList())
    playback.seek(150_000)
    val frame = assertNotNull(playback.next())
    assertEquals(100_000, frame.timeMicros)
    assertEquals(200_000, frame.durationMicros)
  }

  @Test
  fun `a video reads its size, length and frames in order`() {
    val video = openVideo(recordVideo(), 1600, 960).closedAfter()
    assertEquals(64, video.width)
    assertEquals(48, video.height)
    assertEquals(0, video.rotation)
    assertTrue(abs(video.durationMicros - 1_000_000) <= 100_000, "lasts about a second: ${video.durationMicros}")

    val frames = generateSequence { video.next() }.toList()
    assertEquals(10, frames.size)
    assertEquals(0, frames.first().timeMicros)
    frames.zipWithNext().forEach { (a, b) -> assertTrue(b.timeMicros > a.timeMicros) }
    assertColor(Red, frames[0].image, 32, 24, tolerance = 48)
    assertColor(Blue, frames[7].image, 32, 24, tolerance = 48)
    assertNull(video.next())
  }

  @Test
  fun `a video seeks to the frame at a time, and back to the start`() {
    val video = openVideo(recordVideo(), 1600, 960).closedAfter()
    video.seek(700_000)
    val later = assertNotNull(video.next())
    assertTrue(abs(later.timeMicros - 700_000) < 100_000, "at ${later.timeMicros}")
    assertColor(Blue, later.image, 32, 24, tolerance = 48)

    while (video.next() != null) Unit
    video.seek(0)
    val first = assertNotNull(video.next())
    assertEquals(0, first.timeMicros)
    assertColor(Red, first.image, 32, 24, tolerance = 48)
  }

  @Test
  fun `a big video is decoded smaller, but keeps its own size`() {
    val video = openVideo(recordVideo(), 32, 32).closedAfter()
    assertEquals(64, video.width)
    val frame = assertNotNull(video.next())
    assertEquals(32, frame.image.width)
    assertEquals(24, frame.image.height)
  }

  @Test
  fun `a video filmed on its side is turned upright, the way FFmpeg turns it`() {
    // A phone held upright stores landscape frames and a display matrix that ffprobe reports as
    // "rotation=-90": shown turned a quarter clockwise.
    val phone = recordVideo(rotation = -90.0)
    assertEquals(-90.0, displayRotation(phone))
    val upright = openVideo(phone, 1600, 960).closedAfter()
    assertEquals(90, upright.rotation)
    assertEquals(48, upright.width)
    assertEquals(64, upright.height)

    val other = recordVideo(rotation = 90.0)
    assertEquals(90.0, displayRotation(other))
    assertEquals(270, openVideo(other, 1600, 960).closedAfter().rotation)
  }

  /** The counterclockwise rotation in the video's display matrix, as ffprobe reports it. */
  private fun displayRotation(video: ByteArray): Double = FFmpegFrameGrabber(ByteArrayInputStream(video)).use { grabber ->
    grabber.start()
    grabber.displayRotation
  }

  @Test
  fun `bytes that aren't a video say why they can't be played`() {
    val error = assertFailsWith<Exception> { openVideo(ByteArray(64) { it.toByte() }, 1600, 960) }
    assertEquals("it isn't a video FFmpeg can read", error.message)
  }

  @Test
  fun `a closed video has no more frames`() {
    val video = openVideo(recordVideo(), 1600, 960)
    video.close()
    video.seek(0)
    assertNull(video.next())
  }

  /**
   * A second of 64×48 video at 10 fps: half a second of red, then blue. JavaCV's [rotation] comes
   * out of the file as the display matrix's counterclockwise rotation.
   */
  private fun recordVideo(rotation: Double? = null): ByteArray {
    avutil.av_log_set_level(avutil.AV_LOG_QUIET)
    val file = File.createTempFile("motion", ".mp4")
    try {
      FFmpegFrameRecorder(file, 64, 48).apply {
        format = "mp4"
        videoCodec = avcodec.AV_CODEC_ID_MPEG4
        pixelFormat = avutil.AV_PIX_FMT_YUV420P
        frameRate = 10.0
        gopSize = 3
        videoQuality = 2.0
        rotation?.let(::setDisplayRotation)
      }.use { recorder ->
        recorder.start()
        val frame = Frame(64, 48, Frame.DEPTH_UBYTE, 3)
        repeat(10) { index ->
          val (blue, green, red) = if (index < 5) Triple(0, 0, 255) else Triple(255, 0, 0)
          val pixels = frame.image[0] as ByteBuffer
          for (offset in 0 until frame.imageStride * 48 step 3) {
            pixels.put(offset, blue.toByte()).put(offset + 1, green.toByte()).put(offset + 2, red.toByte())
          }
          recorder.record(frame)
        }
        recorder.stop()
      }
      return file.readBytes()
    } finally {
      file.delete()
    }
  }

  private fun assertColor(expected: Color, image: ImageBitmap, x: Int, y: Int, tolerance: Int = 2) {
    val actual = image.toPixelMap()[x, y]
    val close = listOf(expected.red to actual.red, expected.green to actual.green, expected.blue to actual.blue)
      .all { (a, b) -> abs(a - b) * 255 <= tolerance }
    assertTrue(close, "expected $expected at ($x, $y), was $actual")
  }

  private companion object {
    val Red = Color(0xFFFF0000)
    val Green = Color(0xFF00FF00)
    val Blue = Color(0xFF0000FF)

    // 4×4 frames: red for 100 ms, then blue over the top-left 2×2 for 200 ms, then green with no delay.
    val LoopingGif = Base64.decode(
      "R0lGODlhBAAEAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQECgAAACwAAAAABAAEAAAICQABCBxIsCCAgAAh+QQFFAACACwAAAAAAgAC" +
        "AIH/AAAAAP8AAAAAAAAIBgADCAwQEAAh+QQFAAABACwAAAAABAAEAIEA/wAAAAAAAAAAAAAICQABCBxIsCCAgAA7",
    )

    // The same frames without a loop count.
    val OnceGif = Base64.decode(
      "R0lGODlhBAAEAIEAAP8AAAAAAAAAAAAAACH5BAQKAAAALAAAAAAEAAQAAAgJAAEIHEiwIICAACH5BAUUAAIALAAAAAACAAIAgf8AAAAA/wAAAAAAAAgGAAMI" +
        "DBAQACH5BAUAAAEALAAAAAAEAAQAgQD/AAAAAAAAAAAAAAgJAAEIHEiwIICAADs=",
    )

    val StillGif = Base64.decode("R0lGODdhBAAEAIEAAP8AAAAAAAAAAAAAACwAAAAABAAEAAAICQABCBxIsCCAgAA7")

    // Red for 120 ms, then blue over the top-left corner for 80 ms.
    val AnimatedWebp = Base64.decode(
      "UklGRoQAAABXRUJQVlA4WAoAAAACAAAAAwAAAwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAAMAAAMAAHgAAAJWUDhMDwAAAC8DwAAABxD9j/4HIqL/" +
        "AQBBTk1GKAAAAAAAAAAAAAEAAAEAAFAAAABWUDhMDwAAAC8BQAAABxDR//4HIqL/AQA=",
    )
  }
}
