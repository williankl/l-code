package dev.lemonadecode.mobile.impl

import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FfmpegVideoFilesTest {
  /** 12 pictures of an emulator's home screen, recorded with `screenrecord --output-format=h264 --size 320x200`. */
  private val recording = checkNotNull(javaClass.getResourceAsStream("/screenrecord-320x200.h264")).use { it.readBytes() }

  private val folder = Files.createTempDirectory("lemonade-code-recordings").toFile()

  private fun units(): List<ByteArray> {
    val splitter = AccessUnitSplitter()
    return splitter.feed(recording) + listOfNotNull(splitter.flush())
  }

  @Test
  fun `a screen recording's pictures go into an MP4 as they are, each shown until the next, the last until the end`() {
    val units = units()
    assertTrue(startsStream(units.first()))
    val path = File(folder, "Pixel-8.mp4").path
    val file = FfmpegVideoFiles().mp4(path, 320, 200, checkNotNull(parameterSetsOf(units.first())))
    // A still screen sends nothing for a while: the fourth picture holds for 2 s.
    val times = units.indices.map { index -> index * 100_000L + if (index > 3) 2_000_000L else 0L }
    units.zip(times).forEach { (unit, at) -> file.write(unit, at, isKeyFrame(unit)) }
    file.finish(times.last() + 1_500_000L)

    val video = readVideo(path)
    assertEquals("h264", video.codec)
    assertEquals(320 to 200, video.width to video.height)
    assertEquals(12, video.pictures)
    assertEquals(times, video.timesMicros)
    assertEquals(listOf(0), video.keyFrames)
    // 3.1 s of pictures, then the last one for another 1.5 s.
    assertEquals(4_600_000L, video.endMicros)
  }

  @Test
  fun `a file that can't be written says so`() {
    val units = units()
    val blocked = File(folder, "not-a-folder").apply { writeText("") }
    assertFailsWith<IllegalStateException> { FfmpegVideoFiles().mp4(File(blocked, "a.mp4").path, 320, 200, checkNotNull(parameterSetsOf(units.first()))) }
  }

  private class Video(
    val codec: String,
    val width: Int,
    val height: Int,
    val pictures: Int,
    val timesMicros: List<Long>,
    val keyFrames: List<Int>,
    val endMicros: Long,
  )

  /** What FFmpeg's demuxer finds in the file at [path]. */
  private fun readVideo(path: String): Video {
    val context = AVFormatContext(null)
    check(avformat.avformat_open_input(context, path, null, null) >= 0) { "FFmpeg can't open $path" }
    try {
      check(avformat.avformat_find_stream_info(context, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) >= 0)
      assertEquals(1, context.nb_streams())
      val stream = context.streams(0)
      val parameters = stream.codecpar()
      val timeBase = stream.time_base()
      val microseconds = avutil.av_make_q(1, 1_000_000)
      val packet = avcodec.av_packet_alloc()
      val times = mutableListOf<Long>()
      val keys = mutableListOf<Int>()
      var end = 0L
      while (avformat.av_read_frame(context, packet) >= 0) {
        if (packet.flags() and avcodec.AV_PKT_FLAG_KEY != 0) keys += times.size
        times += avutil.av_rescale_q(packet.pts(), timeBase, microseconds)
        end = avutil.av_rescale_q(packet.pts() + packet.duration(), timeBase, microseconds)
        avcodec.av_packet_unref(packet)
      }
      avcodec.av_packet_free(packet)
      return Video(
        codec = avcodec.avcodec_get_name(parameters.codec_id()).string,
        width = parameters.width(),
        height = parameters.height(),
        pictures = times.size,
        timesMicros = times,
        keyFrames = keys,
        endMicros = end,
      )
    } finally {
      avformat.avformat_close_input(context)
    }
  }
}
