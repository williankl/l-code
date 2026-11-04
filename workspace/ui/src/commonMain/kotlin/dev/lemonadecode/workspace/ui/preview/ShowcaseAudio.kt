package dev.lemonadecode.workspace.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.flow.Flow

/**
 * Draws [content] with its sound previews read in plain Kotlin, at once, instead of by FFmpeg on
 * another thread: for the showcase, whose renders can't wait. It reads 16-bit PCM WAV files only.
 * They still play through the platform.
 */
@Composable
public fun ShowcaseAudio(content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalAudioEngine provides WavAudio, content = content)
}

/** Reads 16-bit PCM WAV files without leaving the caller's thread; plays them through the platform. */
private object WavAudio : AudioEngine {
  // The showcase draws the player as it is where sound can play; playing says so if it can't.
  override suspend fun outputAvailable(): Boolean = true

  override suspend fun read(bytes: ByteArray): AudioClip = readWav(bytes)

  override fun play(bytes: ByteArray, fromMicros: Long): Flow<Long> = PlatformAudio.play(bytes, fromMicros)
}

/** A 16-bit PCM WAV file's facts and waveform. Throws when [bytes] are anything else. */
internal fun readWav(bytes: ByteArray): AudioClip {
  fun text(at: Int): String = bytes.decodeToString(at, at + 4)
  fun u16(at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
  fun u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
  require(bytes.size >= 12 && text(0) == "RIFF" && text(8) == "WAVE") { "it isn't a WAV file" }
  var channels = 0
  var sampleRate = 0
  var bits = 0
  var at = 12
  while (at + 8 <= bytes.size) {
    val id = text(at)
    val length = u32(at + 4).coerceAtMost((bytes.size - at - 8).toLong()).toInt()
    val body = at + 8
    when (id) {
      "fmt " -> {
        require(length >= 16 && u16(body) == 1) { "it isn't plain PCM" }
        channels = u16(body + 2)
        sampleRate = u32(body + 4).toInt()
        bits = u16(body + 14)
      }
      "data" -> {
        require(channels > 0 && sampleRate > 0 && bits == 16) { "it isn't 16-bit PCM" }
        val frames = length / (2 * channels)
        val builder = WaveformBuilder(frames.toLong())
        val chunk = ShortArray(4096 * channels)
        var read = 0
        while (read < frames * channels) {
          val count = minOf(chunk.size, frames * channels - read)
          for (index in 0 until count) chunk[index] = u16(body + 2 * (read + index)).toShort()
          builder.addShorts(chunk, count, channels)
          read += count
        }
        return AudioClip(
          durationMicros = frames * 1_000_000L / sampleRate,
          sampleRate = sampleRate,
          channels = channels,
          codec = "PCM",
          bitsPerSample = 16,
          bitrate = sampleRate.toLong() * channels * 16,
          waveform = builder.build(),
        )
      }
    }
    // Chunks are padded to an even length.
    at = body + length + (length and 1)
  }
  throw IllegalArgumentException("it has no sound")
}
