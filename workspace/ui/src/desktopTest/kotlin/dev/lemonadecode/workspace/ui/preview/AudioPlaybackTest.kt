package dev.lemonadecode.workspace.ui.preview

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Sound decoded by FFmpeg and played into memory, as it would go to the sound output. */
class AudioPlaybackTest {
  private val samples = SoundFiles.tone(1.0, 8_000, 1)
  private val wav = SoundFiles.wav(samples, 8_000, 1)

  @Test
  fun `a sound plays its samples through to the end, saying where it is as it goes`() = runBlocking {
    val output = MemoryOutput()
    val positions = FfmpegAudio(output).play(wav, 0).toList()

    val sink = output.sinks.single()
    assertEquals(8_000 to 1, sink.sampleRate to sink.channels)
    assertContentEquals(littleEndian(samples), sink.bytes.toByteArray())
    assertTrue(sink.closed)
    // Positions the screen hasn't caught up with yet are dropped for the newest, but the end isn't.
    assertEquals(1_000_000, positions.last())
    positions.zipWithNext().forEach { (a, b) -> assertTrue(b >= a, "$a then $b") }
  }

  @Test
  fun `playing from the middle starts there`() = runBlocking {
    val output = MemoryOutput()
    val positions = FfmpegAudio(output).play(wav, 500_000).toList()
    val played = output.sinks.single().bytes.size() / 2
    assertTrue(abs(played - 4_000) < 400, "played $played samples")
    assertTrue(positions.all { it >= 500_000 }, "starts at ${positions.first()}")
    assertTrue(abs(positions.last() - 1_000_000) < 50_000, "ends at ${positions.last()}")
  }

  @Test
  fun `stopping early closes the output at once`() = runBlocking {
    // As slow as a real output: a piece takes a moment to go in.
    val output = MemoryOutput(writeMillis = 5)
    val _ = FfmpegAudio(output).play(wav, 0).take(3).toList()
    val sink = output.sinks.single()
    assertTrue(sink.closed)
    assertTrue(sink.bytes.size() < samples.size * 2, "stopped after ${sink.bytes.size()} bytes")
  }

  @Test
  fun `a sound is converted to what the output takes`() = runBlocking {
    val output = MemoryOutput(accepts = { _, _ -> 48_000 to 2 })
    val _ = FfmpegAudio(output).play(wav, 0).toList()
    val frames = output.sinks.single().bytes.size() / 4
    assertTrue(abs(frames - 48_000) < 1_000, "played $frames frames")
  }

  @Test
  fun `with nowhere to play, playing says so`() {
    val output = MemoryOutput(accepts = { _, _ -> null })
    runBlocking {
      assertEquals(false, FfmpegAudio(output).outputAvailable())
      assertFailsWith<AudioUnavailableException> { FfmpegAudio(output).play(wav, 0).toList() }
    }
  }

  @Test
  fun `the computer's sound output plays a moment of silence, or says there isn't one`() {
    val sink = try {
      JavaSoundOutput.open(44_100, 2)
    } catch (_: AudioUnavailableException) {
      // A machine without sound, like a build server.
      return
    }
    sink.use {
      it.write(ByteArray(44_100 / 20 * 4), 0, 44_100 / 20 * 4)
      assertEquals(2, it.channels)
    }
  }

  private fun littleEndian(samples: ShortArray): ByteArray = ByteArray(samples.size * 2).also { bytes ->
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
  }

  /** Takes what [accepts] says for each rate and channel count asked for; null for nothing. */
  private class MemoryOutput(
    private val accepts: (Int, Int) -> Pair<Int, Int>? = { rate, channels -> rate to channels },
    private val writeMillis: Long = 0,
  ) : PcmOutput {
    val sinks = mutableListOf<MemorySink>()

    override fun available(): Boolean = accepts(44_100, 2) != null

    override fun open(sampleRate: Int, channels: Int): PcmSink {
      val (rate, count) = accepts(sampleRate, channels) ?: throw AudioUnavailableException("nowhere to play")
      return MemorySink(rate, count, writeMillis).also { sinks += it }
    }
  }

  /** Plays everything written at once, after [writeMillis]. */
  private class MemorySink(
    override val sampleRate: Int,
    override val channels: Int,
    private val writeMillis: Long,
  ) : PcmSink {
    val bytes = ByteArrayOutputStream()
    var closed = false

    override val playedMicros: Long get() = bytes.size() / (2L * channels) * 1_000_000 / sampleRate

    override val drained: Boolean get() = true

    override fun write(data: ByteArray, offset: Int, length: Int) {
      check(!closed) { "written after closing" }
      if (writeMillis > 0) Thread.sleep(writeMillis)
      bytes.write(data, offset, length)
    }

    override fun close() {
      closed = true
    }
  }
}
