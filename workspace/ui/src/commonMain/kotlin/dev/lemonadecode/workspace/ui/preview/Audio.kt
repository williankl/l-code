package dev.lemonadecode.workspace.ui.preview

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Reads and plays sound files for their previews. The platform's reads them with FFmpeg and plays
 * them through the computer's sound output; the showcase and tests stand in through [LocalAudioEngine].
 */
internal interface AudioEngine {
  /** Whether there's a sound output to play through. Picks its own thread. */
  suspend fun outputAvailable(): Boolean

  /**
   * [bytes] read through once, for what's in them and their waveform, without keeping the samples.
   * Picks its own thread. Throws when they can't be read; the message says why.
   */
  suspend fun read(bytes: ByteArray): AudioClip

  /**
   * Plays [bytes] from [fromMicros] while collected, emitting where playback is, in microseconds
   * from the start, as it goes; completes at the end. Cancelling the collection stops the sound at
   * once. Fails with [AudioUnavailableException] when there's no sound output to play through.
   */
  fun play(bytes: ByteArray, fromMicros: Long): Flow<Long>
}

/** There's no sound output to play through, like on a server or a test machine. */
internal class AudioUnavailableException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)

/** The engine sound previews use: the platform's, unless the showcase or a test stands in. */
internal val LocalAudioEngine = staticCompositionLocalOf<AudioEngine> { PlatformAudio }

/** Reads sound files with FFmpeg and plays them through the computer's sound output. */
internal expect val PlatformAudio: AudioEngine

/** [value] written the way the user's locale writes numbers, with at most [maxFractionDigits]: 44.1, or 44,1. */
internal expect fun formatDecimal(value: Double, maxFractionDigits: Int): String

/** What a sound file holds, for its preview. */
internal class AudioClip(
  val durationMicros: Long,
  /** Samples per second, per channel. */
  val sampleRate: Int,
  val channels: Int,
  /** The codec's usual name, like MP3, AAC or PCM; null when it isn't known. */
  val codec: String?,
  /** Bits per sample, for formats that keep them, like PCM and FLAC; null for the rest. */
  val bitsPerSample: Int?,
  /** Bits per second, on average; null when it isn't known. */
  val bitrate: Long?,
  val waveform: Waveform,
)

/**
 * How loud a sound is along its length, in equal stretches from start to end: the loudest sample
 * of each, and its root mean square, both from 0 (silence) to 1 (full scale).
 */
internal class Waveform(
  val peaks: FloatArray,
  val rms: FloatArray,
) {
  init {
    require(peaks.size == rms.size)
  }

  val size: Int get() = peaks.size

  /** The loudest peak between fractions [from] and [to] of the length, and the RMS of all of it. */
  fun levels(from: Float, to: Float): Pair<Float, Float> {
    if (peaks.isEmpty()) return 0f to 0f
    val first = (from * size).toInt().coerceIn(0, size - 1)
    val last = (to * size).toInt().coerceIn(first + 1, size)
    var peak = 0f
    var squares = 0f
    for (index in first until last) {
      if (peaks[index] > peak) peak = peaks[index]
      squares += rms[index] * rms[index]
    }
    return peak to sqrt(squares / (last - first))
  }
}

/** Stretches a [Waveform] is kept in: from this many to twice as many. */
internal const val WaveformResolution = 1024

/**
 * Builds a [Waveform] as samples are decoded, without keeping them: each stretch keeps its loudest
 * sample and the sum of its squares. When there are too many stretches, neighbours merge and each
 * covers twice as long, so a sound of any length, or of a length that isn't known, fits.
 *
 * [expectedFrames] sizes the stretches from the start when the length is known; 0 when it isn't.
 */
internal class WaveformBuilder(expectedFrames: Long = 0, private val resolution: Int = WaveformResolution) {
  private var framesPerBucket: Long = if (expectedFrames > 0) max(1L, expectedFrames / resolution) else 256L
  private val peaks = FloatArray(resolution * 2)
  private val squares = DoubleArray(resolution * 2)
  private var buckets = 0
  private var peak = 0f
  private var square = 0.0
  private var inBucket = 0L

  /** Every frame added so far: one sample per channel. */
  var frames: Long = 0
    private set

  /** The first [count] values of [samples], 16-bit and interleaved: [channels] to a frame. */
  fun addShorts(samples: ShortArray, count: Int, channels: Int) {
    require(channels > 0)
    var index = 0
    while (index + channels <= count) {
      var framePeak = 0
      var frameSquare = 0L
      for (channel in 0 until channels) {
        val sample = samples[index + channel].toInt()
        val magnitude = if (sample < 0) -sample else sample
        if (magnitude > framePeak) framePeak = magnitude
        frameSquare += sample.toLong() * sample
      }
      addFrame(framePeak / FullScale, frameSquare / (FullScale.toDouble() * FullScale * channels))
      index += channels
    }
  }

  private fun addFrame(level: Float, meanSquare: Double) {
    if (level > peak) peak = level
    square += meanSquare
    inBucket++
    frames++
    if (inBucket >= framesPerBucket) closeBucket()
  }

  private fun closeBucket() {
    peaks[buckets] = peak.coerceAtMost(1f)
    squares[buckets] = square
    buckets++
    peak = 0f
    square = 0.0
    inBucket = 0
    if (buckets == peaks.size) halve()
  }

  /** Merges each pair of neighbours, so every stretch covers twice as long. */
  private fun halve() {
    for (index in 0 until buckets / 2) {
      peaks[index] = maxOf(peaks[2 * index], peaks[2 * index + 1])
      squares[index] = squares[2 * index] + squares[2 * index + 1]
    }
    buckets /= 2
    framesPerBucket *= 2
  }

  fun build(): Waveform {
    val partial = if (inBucket > 0) 1 else 0
    val count = buckets + partial
    val outPeaks = FloatArray(count)
    val outRms = FloatArray(count)
    for (index in 0 until buckets) {
      outPeaks[index] = peaks[index]
      outRms[index] = sqrt(squares[index] / framesPerBucket).toFloat().coerceAtMost(1f)
    }
    if (partial == 1) {
      outPeaks[buckets] = peak.coerceAtMost(1f)
      outRms[buckets] = sqrt(square / inBucket).toFloat().coerceAtMost(1f)
    }
    return Waveform(outPeaks, outRms)
  }

  private companion object {
    const val FullScale = 32768f
  }
}

/**
 * The name people know a codec by, from FFmpeg's name for it: `mp3` is MP3, `pcm_s16le` is PCM,
 * `vorbis` is Vorbis. Names it doesn't know are shown as they are, in capitals.
 */
internal fun codecLabel(name: String): String = when {
  name.startsWith("pcm_") -> "PCM"
  name.startsWith("adpcm_") -> "ADPCM"
  name.startsWith("mp3") -> "MP3"
  name.startsWith("wma") -> "WMA"
  else -> when (name) {
    "aac", "aac_latm" -> "AAC"
    "vorbis" -> "Vorbis"
    "opus" -> "Opus"
    "flac" -> "FLAC"
    "alac" -> "ALAC"
    "ac3" -> "AC-3"
    "eac3" -> "E-AC-3"
    "amr_nb" -> "AMR-NB"
    "amr_wb" -> "AMR-WB"
    "mp2" -> "MP2"
    "wavpack" -> "WavPack"
    else -> name.uppercase()
  }
}

/**
 * Where a sound is, the way players show it, e.g. `0:07` or `1:05:09`. Sounds shorter than ten
 * seconds, like most in an app, show tenths too, e.g. `0:01.2`.
 */
internal fun formatAudioTime(micros: Long, lengthMicros: Long): String {
  if (lengthMicros >= 10_000_000) return formatMediaTime(micros)
  val tenths = micros.coerceAtLeast(0) / 100_000
  val seconds = formatSeconds(tenths * 100)
  return if (tenths < 100) "0:0$seconds" else "0:$seconds"
}
