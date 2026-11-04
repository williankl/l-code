package dev.lemonadecode.workspace.ui.preview

import kotlinx.coroutines.CancellationException
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Sound files read by FFmpeg for their previews: what's in them, and their waveforms. */
class AudioDecodingTest {
  @Test
  fun `a WAV file says what it is, and its waveform follows how loud it is`() {
    val clip = readAudio(SoundFiles.wav(SoundFiles.tone(1.0, 8_000, 1), 8_000, 1))
    assertEquals(1_000_000, clip.durationMicros)
    assertEquals(8_000, clip.sampleRate)
    assertEquals(1, clip.channels)
    assertEquals("PCM", clip.codec)
    assertEquals(16, clip.bitsPerSample)
    assertEquals(128_000, clip.bitrate)
    assertLoudThenQuiet(clip.waveform, loudness = 0.8)
  }

  @Test
  fun `a stereo WAV keeps both channels`() {
    val clip = readAudio(SoundFiles.wav(SoundFiles.tone(0.5, 44_100, 2), 44_100, 2))
    assertEquals(2, clip.channels)
    assertEquals(44_100, clip.sampleRate)
    assertEquals(500_000, clip.durationMicros)
    assertEquals(1_411_200, clip.bitrate)
  }

  @Test
  fun `FLAC, AIFF, M4A, MP3 and Ogg files read like WAV files do`() {
    val samples = SoundFiles.tone(1.0, 44_100, 2)
    val formats = listOf(
      Encoded("flac", "flac", avcodec.AV_CODEC_ID_FLAC, "FLAC", bits = 16),
      Encoded("aiff", "aiff", avcodec.AV_CODEC_ID_PCM_S16BE, "PCM", bits = 16),
      Encoded("ipod", "m4a", avcodec.AV_CODEC_ID_AAC, "AAC"),
      Encoded("mp3", "mp3", avcodec.AV_CODEC_ID_MP3, "MP3"),
      Encoded("ogg", "ogg", avcodec.AV_CODEC_ID_VORBIS, "Vorbis"),
      Encoded("ogg", "opus", avcodec.AV_CODEC_ID_OPUS, "Opus", sampleRate = 48_000),
    )
    val read = formats.filter { SoundFiles.canEncode(it.codec) }
    // The LGPL build may leave an encoder or two out, but never all of these.
    assertTrue(read.size >= 4, "only ${read.map { it.extension }} can be written")
    read.forEach { format ->
      val rate = format.sampleRate ?: 44_100
      val tone = if (rate == 44_100) samples else SoundFiles.tone(1.0, rate, 2)
      val clip = readAudio(SoundFiles.encode(tone, rate, 2, format.format, format.codec, format.extension))
      assertEquals(format.label, clip.codec, format.extension)
      assertEquals(2, clip.channels, format.extension)
      assertEquals(rate, clip.sampleRate, format.extension)
      assertEquals(format.bits, clip.bitsPerSample, format.extension)
      // Lossy codecs pad the start and end a little.
      assertTrue(abs(clip.durationMicros - 1_000_000) < 80_000, "${format.extension} lasts ${clip.durationMicros}")
      assertTrue((clip.bitrate ?: 0) > 0, "${format.extension} has a bitrate")
      assertLoudThenQuiet(clip.waveform, loudness = 0.8, tolerance = 0.12)
    }
  }

  private class Encoded(
    val format: String,
    val extension: String,
    val codec: Int,
    val label: String,
    val bits: Int? = null,
    val sampleRate: Int? = null,
  )

  @Test
  fun `a long sound keeps a waveform of the same size, read as it streams`() {
    val minutes = 3
    val clip = readAudio(SoundFiles.wav(SoundFiles.tone(minutes * 60.0, 8_000, 1), 8_000, 1))
    assertEquals(minutes * 60_000_000L, clip.durationMicros)
    assertTrue(clip.waveform.size in WaveformResolution..2 * WaveformResolution, "${clip.waveform.size} stretches")
    assertLoudThenQuiet(clip.waveform, loudness = 0.8)
  }

  @Test
  fun `Kotlin's WAV reader, for the showcase, agrees with FFmpeg`() {
    val wav = SoundFiles.wav(SoundFiles.tone(0.75, 22_050, 2), 22_050, 2)
    val ours = readWav(wav)
    val ffmpeg = readAudio(wav)
    assertEquals(ffmpeg.durationMicros, ours.durationMicros)
    assertEquals(ffmpeg.sampleRate, ours.sampleRate)
    assertEquals(ffmpeg.channels, ours.channels)
    assertEquals(ffmpeg.bitrate, ours.bitrate)
    assertEquals(ffmpeg.waveform.peaks.toList(), ours.waveform.peaks.toList())
  }

  @Test
  fun `bytes that aren't a sound say why`() {
    val error = assertFailsWith<Exception> { readAudio(ByteArray(64) { it.toByte() }) }
    assertEquals("it isn't a sound FFmpeg can read", error.message)
  }

  @Test
  fun `a video without a sound track says it has no sound`() {
    val error = assertFailsWith<IllegalArgumentException> { readAudio(silentVideo()) }
    assertEquals("it has no sound", error.message)
  }

  @Test
  fun `a stopped read stops between frames`() {
    val wav = SoundFiles.wav(SoundFiles.tone(5.0, 8_000, 1), 8_000, 1)
    var frames = 0
    val stopped = assertFailsWith<CancellationException> {
      readAudio(wav) { if (++frames > 2) throw CancellationException("left the screen") }
    }
    assertEquals("left the screen", stopped.message)
    assertEquals(3, frames)
  }

  /** The first half of the sound is [loudness] of full scale, the second a tenth of that. */
  private fun assertLoudThenQuiet(
    waveform: Waveform,
    loudness: Double,
    tolerance: Double = 0.02,
  ) {
    val (loudPeak, loudRms) = waveform.levels(0.1f, 0.4f)
    val (quietPeak, quietRms) = waveform.levels(0.6f, 0.9f)
    assertTrue(abs(loudPeak - loudness) < tolerance, "loud peak $loudPeak")
    assertTrue(abs(quietPeak - loudness / 10) < tolerance, "quiet peak $quietPeak")
    // A sine's RMS is its peak over √2.
    assertTrue(abs(loudRms - loudness / sqrt(2.0)) < tolerance, "loud RMS $loudRms")
    assertTrue(quietRms < loudRms / 5, "quiet RMS $quietRms")
  }

  /** Half a second of 32×32 video and no sound track. */
  private fun silentVideo(): ByteArray {
    avutil.av_log_set_level(avutil.AV_LOG_QUIET)
    val file = File.createTempFile("silent", ".mp4")
    try {
      FFmpegFrameRecorder(file, 32, 32, 0).apply {
        format = "mp4"
        videoCodec = avcodec.AV_CODEC_ID_MPEG4
        pixelFormat = avutil.AV_PIX_FMT_YUV420P
        frameRate = 10.0
      }.use { recorder ->
        recorder.start()
        val frame = Frame(32, 32, Frame.DEPTH_UBYTE, 3)
        repeat(5) { recorder.record(frame) }
        recorder.stop()
      }
      return file.readBytes()
    } finally {
      file.delete()
    }
  }
}
