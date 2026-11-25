package dev.lemonadecode.mobile.impl

import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
  fun `a stream that starts again at the same size carries on in the same file, and all of it decodes`() {
    val units = units()
    val path = File(folder, "restarted.mp4").path
    val file = FfmpegVideoFiles().mp4(path, 320, 200, checkNotNull(parameterSetsOf(units.first())))
    // screenrecord stopped after its few minutes and started again: parameter sets and a key frame again.
    val times = (0 until units.size * 2).map { it * 100_000L }
    (units + units).zip(times).forEach { (unit, at) -> file.write(unit, at, isKeyFrame(unit)) }
    file.finish(times.last() + 100_000L)

    val video = readVideo(path)
    assertEquals(24, video.pictures)
    assertEquals(times, video.timesMicros)
    assertEquals(listOf(0, 12), video.keyFrames)
    assertEquals(24, video.decoded)
  }

  @Test
  fun `a file the app never finished plays up to its last fragment on disk`() {
    val units = units()
    val path = File(folder, "unfinished.mp4").path
    val copy = File(folder, "unfinished-copy.mp4")
    val file = FfmpegVideoFiles().mp4(path, 320, 200, checkNotNull(parameterSetsOf(units.first())))
    // A picture every 400 ms: a fragment fills a second, and is written once the next picture comes.
    val times = units.indices.map { it * 400_000L }
    units.zip(times).forEach { (unit, at) -> file.write(unit, at, isKeyFrame(unit)) }
    // As a crash would leave it.
    File(path).copyTo(copy)
    file.finish(times.last() + 400_000L)

    val video = readVideo(copy.path)
    assertTrue(video.pictures in 1 until units.size, "${video.pictures} of ${units.size}")
    assertEquals(times.take(video.pictures), video.timesMicros)
    assertEquals(video.pictures, video.decoded)
  }

  @Test
  fun `a picture taken back isn't written, and the one before shows until the next`() {
    val units = units()
    val path = File(folder, "taken-back.mp4").path
    val file = FfmpegVideoFiles().mp4(path, 320, 200, checkNotNull(parameterSetsOf(units.first())))
    units.take(3).forEachIndexed { index, unit -> file.write(unit, index * 100_000L, isKeyFrame(unit)) }
    file.discardLast()
    file.write(units.first(), 500_000L, keyFrame = true)
    file.finish(600_000L)

    val video = readVideo(path)
    assertEquals(listOf(0L, 100_000L, 500_000L), video.timesMicros)
    assertEquals(listOf(0, 2), video.keyFrames)
  }

  @Test
  fun `a file that can't be opened leaves nothing behind, not even the name taken for it`() {
    val units = units()
    val reserved = File(folder, "Pixel-8-20261125-101403.mp4").apply { createNewFile() }
    // Root, as in many CI containers, writes to it anyway: there's nothing to see there.
    if (!reserved.setWritable(false) || reserved.canWrite()) return
    assertFailsWith<IllegalStateException> { FfmpegVideoFiles().mp4(reserved.path, 320, 200, checkNotNull(parameterSetsOf(units.first()))) }
    assertFalse(reserved.exists())
  }

  @Test
  fun `a file nothing was kept in is deleted`() {
    val units = units()
    val path = File(folder, "empty.mp4")
    val file = FfmpegVideoFiles().mp4(path.path, 320, 200, checkNotNull(parameterSetsOf(units.first())))
    file.write(units.first(), 0, keyFrame = true)
    file.discardLast()
    file.delete()
    assertFalse(path.exists())
  }

  @Test
  fun `a write that doesn't reach the disk fails at once, not when the file is finished`() {
    if (System.getProperty("os.name").orEmpty().startsWith("Windows")) return
    val units = units()
    // A pipe whose reader goes away stands in for a disk that's full or ejected.
    val pipe = File(folder, "pipe.mp4")
    check(ProcessBuilder("mkfifo", pipe.path).start().waitFor() == 0)
    val reader = thread { FileInputStream(pipe).use { val _ = it.read() } }
    val file = FfmpegVideoFiles().mp4(pipe.path, 320, 200, checkNotNull(parameterSetsOf(units.first())))
    reader.join(5_000)
    val failure = assertFailsWith<IllegalStateException> {
      units.forEachIndexed { index, unit -> file.write(unit, index * 100_000L, isKeyFrame(unit)) }
    }
    assertEquals("Couldn't write to ${pipe.path} (Broken pipe)", failure.message)
    file.delete()
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
    /** How many pictures FFmpeg's decoder gave back from it. */
    val decoded: Int,
  )

  /** What FFmpeg's demuxer finds in the file at [path], and how many pictures its decoder gets from it. */
  private fun readVideo(path: String): Video {
    val context = AVFormatContext(null)
    check(avformat.avformat_open_input(context, path, null, null) >= 0) { "FFmpeg can't open $path" }
    try {
      check(avformat.avformat_find_stream_info(context, null as AVDictionary?) >= 0)
      assertEquals(1, context.nb_streams())
      val stream = context.streams(0)
      val parameters = stream.codecpar()
      val timeBase = stream.time_base()
      val microseconds = avutil.av_make_q(1, 1_000_000)
      val codec = checkNotNull(avcodec.avcodec_find_decoder(parameters.codec_id()))
      val decoder = avcodec.avcodec_alloc_context3(codec)
      check(avcodec.avcodec_parameters_to_context(decoder, parameters) >= 0)
      check(avcodec.avcodec_open2(decoder, codec, null as AVDictionary?) >= 0)
      val frame = avutil.av_frame_alloc()
      var decoded = 0
      fun receive() {
        while (avcodec.avcodec_receive_frame(decoder, frame) >= 0) {
          decoded++
          avutil.av_frame_unref(frame)
        }
      }
      val packet = avcodec.av_packet_alloc()
      val times = mutableListOf<Long>()
      val keys = mutableListOf<Int>()
      var end = 0L
      while (avformat.av_read_frame(context, packet) >= 0) {
        if (packet.flags() and avcodec.AV_PKT_FLAG_KEY != 0) keys += times.size
        times += avutil.av_rescale_q(packet.pts(), timeBase, microseconds)
        end = avutil.av_rescale_q(packet.pts() + packet.duration(), timeBase, microseconds)
        check(avcodec.avcodec_send_packet(decoder, packet) >= 0) { "FFmpeg can't decode picture ${times.size}" }
        receive()
        avcodec.av_packet_unref(packet)
      }
      val _ = avcodec.avcodec_send_packet(decoder, null as AVPacket?)
      receive()
      avcodec.av_packet_free(packet)
      avutil.av_frame_free(frame)
      avcodec.avcodec_free_context(decoder)
      return Video(
        codec = avcodec.avcodec_get_name(parameters.codec_id()).string,
        width = parameters.width(),
        height = parameters.height(),
        pictures = times.size,
        timesMicros = times,
        keyFrames = keys,
        endMicros = end,
        decoded = decoded,
      )
    } finally {
      avformat.avformat_close_input(context)
    }
  }
}
