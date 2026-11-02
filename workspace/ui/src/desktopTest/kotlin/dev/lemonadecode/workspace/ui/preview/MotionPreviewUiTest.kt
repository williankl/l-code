package dev.lemonadecode.workspace.ui.preview

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import java.io.File
import java.nio.ByteBuffer
import kotlin.io.encoding.Base64
import kotlin.test.Test

/** The motion previews in a real composition: they play, pause and scrub through their controls. */
@OptIn(ExperimentalTestApi::class)
class MotionPreviewUiTest {
  @Test
  fun `an animated GIF plays by itself and pauses`() = runDesktopComposeUiTest(width = 600, height = 500) {
    setContent { LemonadeCodeTheme { ImagePreview(Gif) } }
    waitUntil(timeoutMillis = 5_000) { onAllNodes(hasContentDescription("Pause")).fetchSemanticsNodes().isNotEmpty() }
    onNode(hasText("4 × 4 · 3 frames · 0.4 s · 156 B")).assertExists()

    onNodeWithContentDescription("Pause").performClick()
    waitUntil(timeoutMillis = 5_000) { onAllNodes(hasContentDescription("Play")).fetchSemanticsNodes().isNotEmpty() }
  }

  @Test
  fun `a GIF scrubs to the frame under the bar`() = runDesktopComposeUiTest(width = 600, height = 500) {
    setContent { LemonadeCodeTheme { ImagePreview(Gif) } }
    waitUntil(timeoutMillis = 5_000) { onAllNodes(hasContentDescription("Pause")).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithContentDescription("Pause").performClick()

    // 0.4 s in all: the second frame shows from 0.1 s to 0.3 s, the third from 0.3 s.
    onNodeWithContentDescription("Position").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
    waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("2 / 3")).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithContentDescription("Position").performSemanticsAction(SemanticsActions.SetProgress) { it(0.9f) }
    waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("3 / 3")).fetchSemanticsNodes().isNotEmpty() }
  }

  @Test
  fun `a video waits on its first frame, plays, and scrubs`() = runDesktopComposeUiTest(width = 600, height = 500) {
    val video = recordVideo()
    setContent { LemonadeCodeTheme { VideoPreview(video) } }
    waitUntil(timeoutMillis = 30_000) { onAllNodes(hasContentDescription("Play")).fetchSemanticsNodes().isNotEmpty() }
    onNode(hasText("64 × 48 · 0:02 · ${formatBytes(video.size.toLong())}")).assertExists()
    onNode(hasText("0:00 / 0:02")).assertExists()

    onNodeWithContentDescription("Position").performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }
    waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("0:01 / 0:02")).fetchSemanticsNodes().isNotEmpty() }

    onNodeWithContentDescription("Play").performClick()
    waitUntil(timeoutMillis = 5_000) { onAllNodes(hasContentDescription("Pause")).fetchSemanticsNodes().isNotEmpty() }
  }

  @Test
  fun `bytes that aren't a video say so`() = runDesktopComposeUiTest(width = 600, height = 300) {
    setContent { LemonadeCodeTheme { VideoPreview(ByteArray(64) { it.toByte() }) } }
    waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Couldn’t draw this file", substring = true)).fetchSemanticsNodes().isNotEmpty() }
  }

  /** Two seconds of 64×48 video at 10 fps. */
  private fun recordVideo(): ByteArray {
    avutil.av_log_set_level(avutil.AV_LOG_QUIET)
    val file = File.createTempFile("motion", ".mp4")
    try {
      FFmpegFrameRecorder(file, 64, 48).apply {
        format = "mp4"
        videoCodec = avcodec.AV_CODEC_ID_MPEG4
        pixelFormat = avutil.AV_PIX_FMT_YUV420P
        frameRate = 10.0
      }.use { recorder ->
        recorder.start()
        val frame = Frame(64, 48, Frame.DEPTH_UBYTE, 3)
        repeat(20) { index ->
          val pixels = frame.image[0] as ByteBuffer
          for (offset in 0 until frame.imageStride * 48) pixels.put(offset, (index * 12).toByte())
          recorder.record(frame)
        }
        recorder.stop()
      }
      return file.readBytes()
    } finally {
      file.delete()
    }
  }

  private companion object {
    val Gif = Base64.decode(
      "R0lGODlhBAAEAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQECgAAACwAAAAABAAEAAAICQABCBxIsCCAgAAh+QQFFAACACwAAAAAAgAC" +
        "AIH/AAAAAP8AAAAAAAAIBgADCAwQEAAh+QQFAAABACwAAAAABAAEAIEA/wAAAAAAAAAAAAAICQABCBxIsCCAgAA7",
    )
  }
}
