package dev.lemonadecode.workspace.ui.preview

import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Small sound files made for the tests, so none has to be checked in. */
internal object SoundFiles {
  /**
   * A 440 Hz tone at [loudness] of full scale for the first half of [seconds], then at a tenth of
   * that, as samples interleaved [channels] to a frame.
   */
  fun tone(
    seconds: Double,
    sampleRate: Int,
    channels: Int,
    loudness: Double = 0.8,
  ): ShortArray {
    val frames = (seconds * sampleRate).roundToInt()
    return ShortArray(frames * channels) { index ->
      val frame = index / channels
      val level = if (frame < frames / 2) loudness else loudness / 10
      (sin(2 * PI * 440 * frame / sampleRate) * level * Short.MAX_VALUE).roundToInt().toShort()
    }
  }

  /** [samples] as a 16-bit PCM WAV file, written by hand. */
  fun wav(
    samples: ShortArray,
    sampleRate: Int,
    channels: Int,
  ): ByteArray {
    val out = ByteArrayOutputStream()
    fun u16(value: Int) {
      out.write(value and 0xFF)
      out.write(value shr 8 and 0xFF)
    }
    fun u32(value: Int) {
      u16(value and 0xFFFF)
      u16(value ushr 16)
    }
    val dataBytes = samples.size * 2
    out.write("RIFF".encodeToByteArray())
    u32(36 + dataBytes)
    out.write("WAVE".encodeToByteArray())
    out.write("fmt ".encodeToByteArray())
    u32(16)
    u16(1)
    u16(channels)
    u32(sampleRate)
    u32(sampleRate * channels * 2)
    u16(channels * 2)
    u16(16)
    out.write("data".encodeToByteArray())
    u32(dataBytes)
    samples.forEach { u16(it.toInt()) }
    return out.toByteArray()
  }

  /** Whether this FFmpeg can write [codec]: the LGPL build leaves some encoders out. */
  fun canEncode(codec: Int): Boolean {
    ffmpegLoadFailure?.let { throw it }
    return avcodec.avcodec_find_encoder(codec) != null
  }

  /** [samples] encoded by FFmpeg as [codec] in a [format] file, like `flac` or `ipod` for M4A. */
  fun encode(
    samples: ShortArray,
    sampleRate: Int,
    channels: Int,
    format: String,
    codec: Int,
    extension: String,
    bitrate: Int = 128_000,
  ): ByteArray {
    ffmpegLoadFailure?.let { throw it }
    avutil.av_log_set_level(avutil.AV_LOG_QUIET)
    val file = File.createTempFile("sound", ".$extension")
    try {
      FFmpegFrameRecorder(file, channels).apply {
        this.format = format
        audioCodec = codec
        this.sampleRate = sampleRate
        audioBitrate = bitrate
        // Vorbis and Opus are experimental in FFmpeg's own encoders.
        setAudioOption("strict", "experimental")
      }.use { recorder ->
        recorder.start()
        recorder.recordSamples(sampleRate, channels, ShortBuffer.wrap(samples))
        recorder.stop()
      }
      return file.readBytes()
    } finally {
      file.delete()
    }
  }
}
