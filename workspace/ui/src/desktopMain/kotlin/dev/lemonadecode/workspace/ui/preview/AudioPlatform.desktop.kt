package dev.lemonadecode.workspace.ui.preview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameGrabber
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.text.NumberFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Line
import javax.sound.sampled.SourceDataLine
import kotlin.math.max

internal actual val PlatformAudio: AudioEngine = FfmpegAudio(JavaSoundOutput)

internal actual fun formatDecimal(value: Double, maxFractionDigits: Int): String = NumberFormat.getNumberInstance().apply {
  minimumFractionDigits = 0
  maximumFractionDigits = maxFractionDigits
}.format(value)

/** Reads sound files with FFmpeg, by way of JavaCV, and plays them through [output]. */
internal class FfmpegAudio(private val output: PcmOutput) : AudioEngine {
  override suspend fun outputAvailable(): Boolean = withContext(Dispatchers.IO) { output.available() }

  override suspend fun read(bytes: ByteArray): AudioClip = withContext(Dispatchers.Default) {
    readAudio(bytes) { ensureActive() }
  }

  override fun play(bytes: ByteArray, fromMicros: Long): Flow<Long> = flow { playAudio(bytes, fromMicros, output) }
    .conflate()
    .flowOn(Dispatchers.IO)
}

/** Where played sound goes: the computer's sound output, or memory in tests. */
internal interface PcmOutput {
  /** Whether it has anywhere to play at all. */
  fun available(): Boolean

  /**
   * Opens for 16-bit signed little-endian PCM at [sampleRate] with [channels], or the nearest it
   * takes; the sink says which. Throws [AudioUnavailableException] when nothing can play.
   */
  fun open(sampleRate: Int, channels: Int): PcmSink
}

/** An open [PcmOutput]. Closing it stops the sound at once. */
internal interface PcmSink : AutoCloseable {
  val sampleRate: Int
  val channels: Int

  /** How long it has played since it opened, in microseconds. */
  val playedMicros: Long

  /** Whether all that was written has played. */
  val drained: Boolean

  /** Plays [length] bytes of [data] from [offset], waiting while it's full. */
  fun write(data: ByteArray, offset: Int, length: Int)

  override fun close()
}

/** The computer's sound output, through Java Sound. */
internal object JavaSoundOutput : PcmOutput {
  private val lineInfo = Line.Info(SourceDataLine::class.java)

  override fun available(): Boolean = try {
    AudioSystem.getSourceLineInfo(lineInfo).isNotEmpty()
  } catch (_: Exception) {
    false
  }

  override fun open(sampleRate: Int, channels: Int): PcmSink {
    // The file's own rate first, then the rates every output takes.
    val tries = listOf(sampleRate to channels, 48_000 to channels, 44_100 to channels, 48_000 to 2, 44_100 to 2).distinct()
    var failure: Exception? = null
    for ((rate, count) in tries) {
      val format = AudioFormat(rate.toFloat(), 16, count, true, false)
      val line = try {
        AudioSystem.getSourceDataLine(format)
      } catch (e: Exception) {
        failure = e
        continue
      }
      try {
        // A fifth of a second: enough not to stutter, little enough that pausing is instant.
        line.open(format, format.frameSize * (rate / 5))
        line.start()
        return JavaSoundSink(line, rate, count)
      } catch (e: Exception) {
        line.close()
        failure = e
      }
    }
    throw AudioUnavailableException("there's no sound output: ${failure?.message}", failure)
  }
}

private class JavaSoundSink(
  private val line: SourceDataLine,
  override val sampleRate: Int,
  override val channels: Int,
) : PcmSink {
  private var writtenFrames = 0L

  override val playedMicros: Long get() = line.longFramePosition * 1_000_000 / sampleRate

  override val drained: Boolean get() = line.longFramePosition >= writtenFrames

  override fun write(data: ByteArray, offset: Int, length: Int) {
    val written = line.write(data, offset, length)
    writtenFrames += written / (2 * channels)
  }

  override fun close() {
    line.stop()
    line.flush()
    line.close()
  }
}

/** A sound's PCM is written in pieces this long, so pausing never waits on more than one. */
private const val ChunkMillis = 20

/** The longest the tail of a sound is waited on once it's all written. */
private const val DrainMillis = 1_000L

/**
 * Decodes [bytes] from [fromMicros] into [output], emitting where playback is as it goes. Every
 * native resource is closed when it ends, however it ends.
 */
private suspend fun FlowCollector<Long>.playAudio(
  bytes: ByteArray,
  fromMicros: Long,
  output: PcmOutput,
) {
  val grabber = startGrabber(bytes)
  var sink: PcmSink? = null
  try {
    val opened = output.open(grabber.sampleRate, grabber.audioChannels.coerceIn(1, 2))
    sink = opened
    // FFmpeg converts each frame to what the output took as it's read.
    grabber.sampleRate = opened.sampleRate
    grabber.audioChannels = opened.channels
    if (fromMicros > 0) grabber.setAudioTimestamp(fromMicros)
    val chunkBytes = max(1, opened.sampleRate * ChunkMillis / 1000) * 2 * opened.channels
    var pcm = ByteArray(0)
    emit(fromMicros)
    while (true) {
      currentCoroutineContext().ensureActive()
      // A frame that can't be read ends playback like the last one does.
      val frame = runCatching { grabber.grabSamples() }.getOrNull() ?: break
      val samples = frame.samples?.firstOrNull() as? ShortBuffer ?: continue
      val length = samples.remaining() * 2
      if (pcm.size < length) pcm = ByteArray(length)
      ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples.duplicate())
      var offset = 0
      while (offset < length) {
        currentCoroutineContext().ensureActive()
        val piece = minOf(chunkBytes, length - offset)
        opened.write(pcm, offset, piece)
        offset += piece
        emit(fromMicros + opened.playedMicros)
      }
    }
    var waited = 0L
    while (!opened.drained && waited < DrainMillis) {
      emit(fromMicros + opened.playedMicros)
      delay(10)
      waited += 10
    }
  } finally {
    sink?.close()
    grabber.close()
  }
}

/** [bytes] opened for their sound, decoded to 16-bit samples, interleaved. */
private fun startGrabber(bytes: ByteArray): FFmpegFrameGrabber {
  ffmpegLoadFailure?.let { throw IllegalStateException("FFmpeg didn't load: ${audioReason(it)}", it) }
  val grabber = FFmpegFrameGrabber(ByteArrayInputStream(bytes))
  try {
    grabber.sampleMode = FrameGrabber.SampleMode.SHORT
    grabber.sampleFormat = avutil.AV_SAMPLE_FMT_S16
    grabber.start()
    require(grabber.hasAudio() && grabber.audioChannels > 0 && grabber.sampleRate > 0) { "it has no sound" }
    return grabber
  } catch (e: Throwable) {
    runCatching { grabber.close() }
    throw if (e is IllegalArgumentException) e else IllegalStateException(audioReason(e), e)
  }
}

/**
 * [bytes] read through once for their facts and waveform. The samples go into the waveform as
 * they're decoded, so a long sound never sits in memory whole. [checkCancelled] is called between
 * frames, to stop early.
 */
internal fun readAudio(
  bytes: ByteArray,
  checkCancelled: () -> Unit = {},
): AudioClip {
  val grabber = startGrabber(bytes)
  try {
    val channels = grabber.audioChannels
    val rate = grabber.sampleRate
    val codecId = grabber.audioCodec
    val name = avcodec.avcodec_get_name(codecId)?.string.orEmpty()
    val builder = WaveformBuilder(expectedFrames = grabber.lengthInTime.coerceAtLeast(0) * rate / 1_000_000)
    var shorts = ShortArray(0)
    while (true) {
      checkCancelled()
      val frame = try {
        grabber.grabSamples()
      } catch (e: Exception) {
        // A damaged end still shows what came before it.
        if (builder.frames > 0) null else throw e
      } ?: break
      val samples = frame.samples?.firstOrNull() as? ShortBuffer ?: continue
      val count = samples.remaining()
      if (shorts.size < count) shorts = ShortArray(count)
      samples.duplicate().get(shorts, 0, count)
      builder.addShorts(shorts, count, frame.audioChannels.takeIf { it > 0 } ?: channels)
    }
    val duration = if (builder.frames > 0) builder.frames * 1_000_000 / rate else grabber.lengthInTime.coerceAtLeast(0)
    return AudioClip(
      durationMicros = duration,
      sampleRate = rate,
      channels = channels,
      codec = name.takeIf { it.isNotEmpty() && it != "none" }?.let(::codecLabel),
      bitsPerSample = bitsPerSample(grabber, codecId, name),
      bitrate = grabber.audioBitrate.toLong().takeIf { it > 0 } ?: (bytes.size * 8L * 1_000_000 / duration).takeIf { duration > 0 },
      waveform = builder.build(),
    )
  } catch (e: CancellationException) {
    throw e
  } catch (e: IllegalArgumentException) {
    throw e
  } catch (e: Exception) {
    throw IllegalStateException(audioReason(e), e)
  } finally {
    grabber.close()
  }
}

/** Bits per sample, for PCM and the lossless codecs that keep a bit depth; null for the rest. */
private fun bitsPerSample(
  grabber: FFmpegFrameGrabber,
  codecId: Int,
  name: String,
): Int? {
  val context = grabber.formatContext
  if (context != null) {
    for (index in 0 until context.nb_streams()) {
      val parameters = context.streams(index).codecpar()
      if (parameters.codec_type() != avutil.AVMEDIA_TYPE_AUDIO) continue
      parameters.bits_per_raw_sample().takeIf { it > 0 }?.let { return it }
      break
    }
  }
  return if (name.startsWith("pcm_")) avcodec.av_get_bits_per_sample(codecId).takeIf { it > 0 } else null
}

/**
 * Why a sound can't be read, for the preview to say. FFmpeg's own words name its functions, so
 * the common cases are put plainly.
 */
private fun audioReason(e: Throwable): String {
  val message = e.message ?: e.cause?.message ?: return e::class.simpleName.orEmpty()
  return when {
    "avformat_open_input()" in message -> "it isn't a sound FFmpeg can read"
    "avformat_find_stream_info()" in message -> "FFmpeg can't find what's in it"
    // JavaCV ends its messages with a hint about its log callback, which means nothing here.
    else -> message.substringBefore(" (For more details")
  }
}
