package dev.lemonadecode.workspace.impl.showcase

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The sound files of the showcase's sound screen, made here rather than checked in: chimes of
 * decaying tones, written as 16-bit PCM WAV files.
 */
internal object ShowcaseSounds {
  /** The notification sound before the change: two notes, mono, 44.1 kHz. */
  val notifyBefore: ByteArray by lazy {
    wav(sampleRate = 44_100, channels = 1, seconds = 0.9, notes = listOf(Note(659.3, 0.0, 0.42), Note(987.8, 0.28, 0.38)))
  }

  /** After: three rising notes, a little louder and longer, in stereo at 48 kHz. */
  val notifyAfter: ByteArray by lazy {
    wav(
      sampleRate = 48_000,
      channels = 2,
      seconds = 1.4,
      notes = listOf(Note(659.3, 0.0, 0.45), Note(830.6, 0.22, 0.5), Note(987.8, 0.44, 0.62, decay = 3.2)),
    )
  }

  /** A new error sound: a low, short buzz falling a step. */
  val error: ByteArray by lazy {
    wav(
      sampleRate = 44_100,
      channels = 1,
      seconds = 0.6,
      notes = listOf(Note(220.0, 0.0, 0.7, decay = 9.0, buzz = true), Note(185.0, 0.18, 0.7, decay = 7.0, buzz = true)),
    )
  }

  /** A tone at [hertz] from [startSeconds], [level] of full scale at first, fading [decay] times a second. */
  private class Note(
    val hertz: Double,
    val startSeconds: Double,
    val level: Double,
    val decay: Double = 5.0,
    val buzz: Boolean = false,
  )

  private fun wav(
    sampleRate: Int,
    channels: Int,
    seconds: Double,
    notes: List<Note>,
  ): ByteArray {
    val frames = (seconds * sampleRate).roundToInt()
    val dataBytes = frames * channels * 2
    val out = ByteArray(44 + dataBytes)
    var at = 0
    fun text(value: String) = value.forEach { out[at++] = it.code.toByte() }
    fun u16(value: Int) {
      out[at++] = (value and 0xFF).toByte()
      out[at++] = (value shr 8 and 0xFF).toByte()
    }
    fun u32(value: Int) {
      u16(value and 0xFFFF)
      u16(value ushr 16)
    }
    text("RIFF")
    u32(36 + dataBytes)
    text("WAVE")
    text("fmt ")
    u32(16)
    u16(1)
    u16(channels)
    u32(sampleRate)
    u32(sampleRate * channels * 2)
    u16(channels * 2)
    u16(16)
    text("data")
    u32(dataBytes)
    for (frame in 0 until frames) {
      val time = frame.toDouble() / sampleRate
      var mixed = 0.0
      for (note in notes) {
        val since = time - note.startSeconds
        if (since < 0) continue
        // A few milliseconds to come in, so it doesn't click.
        val attack = (since / 0.004).coerceAtMost(1.0)
        val phase = 2 * PI * note.hertz * since
        val tone = if (note.buzz) sin(phase) + sin(3 * phase) / 3 + sin(5 * phase) / 5 else sin(phase) + 0.2 * sin(2 * phase)
        mixed += tone * note.level * attack * exp(-note.decay * since)
      }
      val sample = (mixed.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).roundToInt()
      repeat(channels) { channel ->
        // The stereo sound sits a little to the right.
        val pan = if (channels == 2 && channel == 0) 0.85 else 1.0
        u16((sample * pan).roundToInt())
      }
    }
    return out
  }
}
