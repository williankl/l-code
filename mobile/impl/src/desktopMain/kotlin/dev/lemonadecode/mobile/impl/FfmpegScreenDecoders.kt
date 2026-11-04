package dev.lemonadecode.mobile.impl

import dev.lemonadecode.domain.mobile.ScreenPicture
import dev.lemonadecode.platform.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer

/** FFmpeg's H.264 decoder, through JavaCPP, giving BGRA pictures. */
@ContributesBinding(AppScope::class)
@Inject
internal class FfmpegScreenDecoders : ScreenDecoders {
  override fun h264(): ScreenDecoder {
    loaded?.let { throw IllegalStateException("FFmpeg didn't load", it) }
    return FfmpegH264Decoder()
  }

  private companion object {
    val loaded: Throwable? by lazy {
      try {
        // FFmpeg would otherwise print every damaged picture to the app's error output.
        avutil.av_log_set_level(avutil.AV_LOG_QUIET)
        null
      } catch (e: Throwable) {
        e
      }
    }
  }
}

private class FfmpegH264Decoder : ScreenDecoder {
  private val context: AVCodecContext
  private val packet: AVPacket
  private val frame: AVFrame
  private var scaler: SwsContext? = null

  /** The colour space [scaler] was told the frames are in: their matrix and whether they're full range. */
  private var scalerColours: Pair<Int, Int>? = null
  private var converted: BytePointer? = null

  // swscale reads four planes and strides for any format; BGRA only fills the first.
  private val planes = PointerPointer<BytePointer>(MaxPlanes.toLong())
  private val strides = IntPointer(MaxPlanes.toLong())
  private var closed = false

  init {
    val codec = avcodec.avcodec_find_decoder(avcodec.AV_CODEC_ID_H264) ?: error("FFmpeg has no H.264 decoder")
    context = avcodec.avcodec_alloc_context3(codec) ?: error("FFmpeg couldn't make an H.264 decoder")
    packet = avcodec.av_packet_alloc()
    frame = avutil.av_frame_alloc()
    for (plane in 0 until MaxPlanes) {
      planes.put(plane.toLong(), null)
      strides.put(plane.toLong(), 0)
    }
    // One thread and no reordering, so each picture comes out as soon as its unit goes in.
    context.thread_count(1)
    context.flags(context.flags() or avcodec.AV_CODEC_FLAG_LOW_DELAY)
    if (avcodec.avcodec_open2(context, codec, null as AVDictionary?) < 0) {
      close()
      error("FFmpeg couldn't open its H.264 decoder")
    }
  }

  override fun decode(accessUnit: ByteArray): List<ScreenPicture.Bgra> {
    if (closed || accessUnit.isEmpty()) return emptyList()
    // The packet gets FFmpeg's zeroed padding past the unit's end.
    if (avcodec.av_new_packet(packet, accessUnit.size) < 0) return emptyList()
    packet.data().put(accessUnit, 0, accessUnit.size)
    val sent = avcodec.avcodec_send_packet(context, packet)
    avcodec.av_packet_unref(packet)
    if (sent < 0) return emptyList()
    val pictures = mutableListOf<ScreenPicture.Bgra>()
    while (avcodec.avcodec_receive_frame(context, frame) >= 0) {
      bgra()?.let(pictures::add)
      avutil.av_frame_unref(frame)
    }
    return pictures
  }

  /** The decoded frame turned into BGRA pixels of its own. */
  private fun bgra(): ScreenPicture.Bgra? {
    val width = frame.width()
    val height = frame.height()
    if (width <= 0 || height <= 0) return null
    val rowBytes = width * BytesPerPixel
    val length = rowBytes * height
    // A changed size frees the old context first, so it's kept even when the new one can't be made.
    val next = swscale.sws_getCachedContext(scaler, width, height, frame.format(), width, height, avutil.AV_PIX_FMT_BGRA, swscale.SWS_BILINEAR, null, null, null as DoublePointer?)
    if (next != scaler) scalerColours = null
    scaler = next
    if (next == null) return null
    matchColours(next)
    val target = converted?.takeIf { it.capacity() == length.toLong() } ?: BytePointer(length.toLong()).also {
      converted?.close()
      converted = it
    }
    planes.put(0L, target)
    strides.put(0L, rowBytes)
    if (swscale.sws_scale(next, frame.data(), frame.linesize(), 0, height, planes, strides) != height) return null
    val pixels = ByteArray(length)
    target.position(0L).get(pixels)
    return ScreenPicture.Bgra(pixels, width, height, rowBytes)
  }

  /** Tells [scaler] which colour matrix and range the frames use: phones mark theirs BT.709, which swscale doesn't assume. */
  private fun matchColours(scaler: SwsContext) {
    val matrix = if (frame.colorspace() == avutil.AVCOL_SPC_BT709) swscale.SWS_CS_ITU709 else swscale.SWS_CS_ITU601
    val fullRange = if (frame.color_range() == avutil.AVCOL_RANGE_JPEG) 1 else 0
    val colours = matrix to fullRange
    if (colours == scalerColours) return
    scalerColours = colours
    val _ = swscale.sws_setColorspaceDetails(
      scaler,
      swscale.sws_getCoefficients(matrix),
      fullRange,
      swscale.sws_getCoefficients(swscale.SWS_CS_DEFAULT),
      1,
      0,
      1 shl 16,
      1 shl 16,
    )
  }

  override fun close() {
    if (closed) return
    closed = true
    avcodec.avcodec_free_context(context)
    avcodec.av_packet_free(packet)
    avutil.av_frame_free(frame)
    scaler?.let(swscale::sws_freeContext)
    scaler = null
    converted?.close()
    planes.close()
    strides.close()
  }

  private companion object {
    const val BytesPerPixel = 4
    const val MaxPlanes = 4
  }
}
