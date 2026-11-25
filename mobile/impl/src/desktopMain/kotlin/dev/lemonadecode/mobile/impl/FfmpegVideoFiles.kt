package dev.lemonadecode.mobile.impl

import dev.lemonadecode.platform.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.BytePointer
import java.io.File

/**
 * MP4 files written by FFmpeg's muxer, through JavaCPP, from the H.264 the screen streams as it
 * is: nothing is decoded or encoded again.
 */
@ContributesBinding(AppScope::class)
@Inject
internal class FfmpegVideoFiles : VideoFiles {
  override fun mp4(path: String, width: Int, height: Int, parameterSets: ByteArray): VideoFile = FfmpegMp4File(path, width, height, parameterSets)
}

/**
 * One MP4 being written. Each picture is held until the next comes, which says how long it shows;
 * the last one until [finish]. When the app quits before then, the file is finished as it goes. A
 * file left unfinished, e.g. by a crash, plays up to its last fragment on disk: the fragment being
 * gathered and the picture held are lost.
 *
 * A picture's presentation time is its decoding time: `screenrecord`'s encoder sends no B-frames,
 * so pictures come in the order they show.
 */
private class FfmpegMp4File(
  private val path: String,
  width: Int,
  height: Int,
  parameterSets: ByteArray,
) : VideoFile {
  private val context = AVFormatContext(null)
  private val packet: AVPacket = avcodec.av_packet_alloc()
  private val microseconds: AVRational = avutil.av_make_q(1, 1_000_000)
  private var stream: AVStream? = null
  private var finished = false

  /** The picture waiting for the next one, which says how long it shows. */
  private var held: Held? = null

  /** When [held] was given, to finish the file at the time the app quits. */
  private var heldSince = 0L

  private val onQuit = Thread({ finishOnQuit() }, "lemonade-code-recording")

  private class Held(val accessUnit: ByteArray, val atMicros: Long, val keyFrame: Boolean)

  init {
    try {
      check(avformat.avformat_alloc_output_context2(context, null, "mp4", path) >= 0 && !context.isNull) { "FFmpeg can't write MP4" }
      val video = checkNotNull(avformat.avformat_new_stream(context, null)) { "FFmpeg couldn't add the video to $path" }
      stream = video
      val parameters = video.codecpar()
      parameters.codec_type(avutil.AVMEDIA_TYPE_VIDEO)
      parameters.codec_id(avcodec.AV_CODEC_ID_H264)
      parameters.width(width)
      parameters.height(height)
      // FFmpeg frees it with the file, and reads it with zeroed padding past its end.
      val extradata = BytePointer(avutil.av_mallocz((parameterSets.size + avcodec.AV_INPUT_BUFFER_PADDING_SIZE).toLong()))
      extradata.capacity(parameterSets.size.toLong()).put(parameterSets, 0, parameterSets.size)
      parameters.extradata(extradata)
      parameters.extradata_size(parameterSets.size)
      video.time_base(avutil.av_make_q(1, TimeScale))
      val io = AVIOContext(null)
      check(avformat.avio_open(io, path, avformat.AVIO_FLAG_WRITE) >= 0) { "Can't write $path" }
      context.pb(io)
      val options = AVDictionary(null)
      val _ = avutil.av_dict_set(options, "movflags", MovFlags, 0)
      val _ = avutil.av_dict_set(options, "frag_duration", "$FragmentMicros", 0)
      val header = avformat.avformat_write_header(context, options)
      avutil.av_dict_free(options)
      check(header >= 0) { "FFmpeg couldn't start $path ($header)" }
      // On disk at once, so the file opens even if nothing more makes it there.
      avformat.avio_flush(io)
      Runtime.getRuntime().addShutdownHook(onQuit)
    } catch (e: Throwable) {
      release()
      // The name was taken for this file; nothing of it is worth keeping.
      val _ = File(path).delete()
      throw e
    }
  }

  @Synchronized
  override fun write(accessUnit: ByteArray, atMicros: Long, keyFrame: Boolean) {
    if (finished) return
    held?.let { writePacket(it, atMicros - it.atMicros) }
    held = Held(accessUnit, atMicros, keyFrame)
    heldSince = System.nanoTime()
  }

  @Synchronized
  override fun discardLast() {
    held = null
  }

  @Synchronized
  override fun delete() {
    release()
    forgetQuit()
    val _ = File(path).delete()
  }

  @Synchronized
  override fun finish(atMicros: Long) {
    if (finished) return
    try {
      held?.let { writePacket(it, atMicros - it.atMicros) }
      held = null
      check(avformat.av_write_trailer(context) >= 0) { "FFmpeg couldn't finish $path" }
    } finally {
      release()
      forgetQuit()
    }
  }

  private fun forgetQuit() {
    try {
      val _ = Runtime.getRuntime().removeShutdownHook(onQuit)
    } catch (_: IllegalStateException) {
      // The app is quitting already; this is the hook.
    }
  }

  /** The app is quitting while recording: the last picture shows until now. */
  @Synchronized
  private fun finishOnQuit() {
    val last = held
    val now = if (last == null) 0L else last.atMicros + (System.nanoTime() - heldSince) / 1_000
    val _ = runCatching { finish(now) }
  }

  private fun writePacket(picture: Held, durationMicros: Long) {
    val video = checkNotNull(stream)
    // The muxer settles the stream's time base when the file starts.
    val timeBase = video.time_base()
    check(avcodec.av_new_packet(packet, picture.accessUnit.size) >= 0) { "FFmpeg is out of memory" }
    packet.data().put(picture.accessUnit, 0, picture.accessUnit.size)
    packet.stream_index(video.index())
    val at = avutil.av_rescale_q(picture.atMicros, microseconds, timeBase)
    packet.pts(at)
    packet.dts(at)
    packet.duration(avutil.av_rescale_q(durationMicros, microseconds, timeBase).coerceAtLeast(1))
    if (picture.keyFrame) packet.flags(packet.flags() or avcodec.AV_PKT_FLAG_KEY)
    val written = avformat.av_write_frame(context, packet)
    avcodec.av_packet_unref(packet)
    check(written >= 0) { "Couldn't write to $path (${errorText(written)})" }
    // A fragment the muxer let go of goes to disk now, not when FFmpeg's buffer fills, and a disk
    // that's full or gone says so now rather than at the end.
    val io = context.pb()
    avformat.avio_flush(io)
    check(io.error() >= 0) { "Couldn't write to $path (${errorText(io.error())})" }
  }

  /** FFmpeg's words for [code], e.g. "No space left on device". */
  private fun errorText(code: Int): String {
    val text = ByteArray(ErrorTextSize)
    if (avutil.av_strerror(code, text, text.size.toLong()) < 0) return "$code"
    return text.decodeToString(endIndex = text.indexOf(0).takeIf { it >= 0 } ?: text.size)
  }

  private fun release() {
    if (finished) return
    finished = true
    if (!context.isNull) {
      context.pb()?.takeIf { !it.isNull }?.let { io ->
        val _ = avformat.avio_closep(io)
      }
      avformat.avformat_free_context(context)
    }
    avcodec.av_packet_free(packet)
    microseconds.close()
  }

  private companion object {
    /** 90 kHz, the usual clock for video. */
    const val TimeScale = 90_000

    /**
     * Written in fragments as it goes, at a key frame or once a fragment holds [FragmentMicros], so
     * what reached the disk plays even when the app can't finish the file.
     */
    const val MovFlags = "frag_keyframe+empty_moov+default_base_moof"
    const val FragmentMicros = 1_000_000

    const val ErrorTextSize = 128
  }
}
