package dev.lemonadecode.workspace.ui.preview

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.jetbrains.skia.AnimationDisposalMode
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.text.NumberFormat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal actual fun decodeAnimation(bytes: ByteArray): ImageAnimation? {
  val codec = try {
    Codec.makeFromData(Data.makeFromBytes(bytes))
  } catch (_: Exception) {
    return null
  }
  if (codec.frameCount < 2 || codec.width <= 0 || codec.height <= 0) {
    codec.close()
    return null
  }
  return SkiaAnimation(codec)
}

internal actual fun openVideo(bytes: ByteArray, maxWidth: Int, maxHeight: Int): MotionSource = FfmpegVideo.open(bytes, maxWidth, maxHeight)

internal actual fun formatSeconds(millis: Long): String = NumberFormat.getNumberInstance().apply {
  minimumFractionDigits = 1
  maximumFractionDigits = 1
}.format(millis / 1000.0)

/** Why FFmpeg's libraries didn't load, or null once they have. Loads them the first time it's read. */
internal val ffmpegLoadFailure: Throwable? by lazy {
  try {
    FFmpegFrameGrabber.tryLoad()
    // FFmpeg would otherwise print every oddity in a file to the app's error output.
    avutil.av_log_set_level(avutil.AV_LOG_QUIET)
    null
  } catch (e: Throwable) {
    e
  }
}

/** Delays this short or shorter mean "as fast as you like"; browsers show such frames for 100 ms. */
private const val MinFrameMillis = 10
private const val DefaultFrameMillis = 100

/** Skia decodes the frames; each builds on the one drawn before it when the format allows. */
private class SkiaAnimation(private val codec: Codec) : ImageAnimation {
  private val frames = codec.framesInfo
  private val info = ImageInfo.makeN32Premul(codec.width, codec.height)

  /** Holds frame [drawn], so the frame after it can be drawn on top. */
  private val canvas = Bitmap().apply { allocPixels(info) }
  private var drawn = -1
  private var closed = false

  override val width: Int = codec.width
  override val height: Int = codec.height
  override val frameCount: Int = frames.size

  // Skia counts repeats after the first play; a negative count repeats forever.
  override val plays: Int? = codec.repetitionCount.let { if (it < 0) null else it + 1 }

  override fun durationMillis(index: Int): Int = frames[index].duration.let { if (it <= MinFrameMillis) DefaultFrameMillis else it }

  @Synchronized
  override fun frame(index: Int): ImageBitmap {
    if (closed) return blankImage()
    val prior = index - 1
    val required = frames[index].requiredFrame
    // Skia may start from the frame already on the canvas only when this one builds on it.
    val buildsOnCanvas = drawn == prior && required >= 0 && prior >= required && frames[prior].disposalMethod != AnimationDisposalMode.RESTORE_PREVIOUS
    if (buildsOnCanvas) codec.readPixels(canvas, index, prior) else codec.readPixels(canvas, index)
    drawn = index
    val pixels = canvas.readPixels(info, info.minRowBytes, 0, 0) ?: return blankImage()
    return Bitmap().apply {
      installPixels(info, pixels, info.minRowBytes)
      setImmutable()
    }.asComposeImageBitmap()
  }

  @Synchronized
  override fun close() {
    if (closed) return
    closed = true
    canvas.close()
    codec.close()
  }
}

private fun blankImage(): ImageBitmap = Bitmap().apply {
  allocPixels(ImageInfo.makeN32Premul(1, 1))
  erase(0)
  setImmutable()
}.asComposeImageBitmap()

/** FFmpeg reads the video through JavaCV, one frame at a time, as BGRA pixels. */
private class FfmpegVideo private constructor(
  private val grabber: FFmpegFrameGrabber,
  override val width: Int,
  override val height: Int,
  override val rotation: Int,
  override val durationMicros: Long,
  /** The first frame's time stamp; frames are timed from it. */
  private val originMicros: Long,
  private val frameMicros: Long,
) : MotionSource {
  private var closed = false
  private var pixels = ByteArray(0)

  @Synchronized
  override fun seek(micros: Long) {
    if (closed) return
    grabber.setVideoTimestamp(micros.coerceAtLeast(0))
  }

  @Synchronized
  override fun next(): MotionFrame? {
    if (closed) return null
    val frame = grabber.grabImage() ?: return null
    val buffer = frame.image?.firstOrNull() as? ByteBuffer ?: return null
    val stride = frame.imageStride
    val length = stride * frame.imageHeight
    if (frame.imageWidth <= 0 || frame.imageHeight <= 0 || buffer.capacity() < length) return null
    if (pixels.size != length) pixels = ByteArray(length)
    buffer.duplicate().apply { position(0) }.get(pixels, 0, length)
    // FFmpeg's BGRA is straight alpha; video is nearly always opaque anyway.
    val image = Bitmap().apply {
      installPixels(ImageInfo(frame.imageWidth, frame.imageHeight, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), pixels, stride)
      setImmutable()
    }.asComposeImageBitmap()
    return MotionFrame(image, (frame.timestamp - originMicros).coerceAtLeast(0), frameMicros)
  }

  @Synchronized
  override fun close() {
    if (closed) return
    closed = true
    grabber.close()
  }

  companion object {
    fun open(bytes: ByteArray, maxWidth: Int, maxHeight: Int): FfmpegVideo {
      ffmpegLoadFailure?.let { throw IllegalStateException("FFmpeg didn't load: ${reason(it)}", it) }
      val grabber = FFmpegFrameGrabber(ByteArrayInputStream(bytes))
      try {
        grabber.pixelFormat = avutil.AV_PIX_FMT_BGRA
        grabber.start()
        val storedWidth = grabber.imageWidth
        val storedHeight = grabber.imageHeight
        require(grabber.hasVideo() && storedWidth > 0 && storedHeight > 0) { "it has no pictures" }
        // Pixels that aren't square widen or narrow the picture.
        val pixelAspect = grabber.aspectRatio.takeIf { it.isFinite() && it > 0 } ?: 1.0
        val uprightWidth = max(1, (storedWidth * pixelAspect).roundToInt())
        val rotation = quarterTurns(grabber.displayRotation)
        val quarterTurn = rotation % 180 != 0
        val shownWidth = if (quarterTurn) storedHeight else uprightWidth
        val shownHeight = if (quarterTurn) uprightWidth else storedHeight
        // Big videos are shrunk as they're decoded: each frame is copied a couple of times on its way to the screen.
        val shrink = min(1.0, min(maxWidth.toDouble() / shownWidth, maxHeight.toDouble() / shownHeight))
        val decodedWidth = max(2, (uprightWidth * shrink).roundToInt())
        val decodedHeight = max(2, (storedHeight * shrink).roundToInt())
        if (decodedWidth != storedWidth || decodedHeight != storedHeight) {
          grabber.imageWidth = decodedWidth
          grabber.imageHeight = decodedHeight
        }
        val rate = grabber.videoFrameRate.takeIf { it.isFinite() && it > 0 } ?: 30.0
        return FfmpegVideo(
          grabber = grabber,
          width = shownWidth,
          height = shownHeight,
          rotation = rotation,
          durationMicros = grabber.lengthInTime.coerceAtLeast(0),
          originMicros = videoStartMicros(grabber),
          frameMicros = (1_000_000 / rate).roundToInt().toLong(),
        )
      } catch (e: Throwable) {
        runCatching { grabber.close() }
        throw if (e is IllegalArgumentException) e else IllegalStateException(reason(e), e)
      }
    }

    /** When the video stream starts, which seeking counts from too. */
    private fun videoStartMicros(grabber: FFmpegFrameGrabber): Long {
      val context = grabber.formatContext ?: return 0
      for (index in 0 until context.nb_streams()) {
        val stream = context.streams(index)
        if (stream.codecpar().codec_type() != avutil.AVMEDIA_TYPE_VIDEO) continue
        val start = stream.start_time()
        if (start == avutil.AV_NOPTS_VALUE) return 0
        val base = stream.time_base()
        return 1_000_000L * start * base.num() / base.den()
      }
      return 0
    }

    /** FFmpeg's display rotation is counterclockwise; this is the clockwise quarter turn that undoes it. */
    private fun quarterTurns(counterclockwise: Double): Int {
      if (!counterclockwise.isFinite()) return 0
      val clockwise = (-counterclockwise / 90.0).roundToInt() * 90
      return ((clockwise % 360) + 360) % 360
    }

    /**
     * Why a video can't be played, for the preview to say. FFmpeg's own words name its functions
     * and the stream object, so the common cases are put plainly.
     */
    private fun reason(e: Throwable): String {
      val message = e.message ?: e.cause?.message ?: return e::class.simpleName.orEmpty()
      return when {
        "avformat_open_input()" in message -> "it isn't a video FFmpeg can read"
        "avformat_find_stream_info()" in message -> "FFmpeg can't find what's in it"
        // JavaCV ends its messages with a hint about its log callback, which means nothing here.
        else -> message.substringBefore(" (For more details")
      }
    }
  }
}
