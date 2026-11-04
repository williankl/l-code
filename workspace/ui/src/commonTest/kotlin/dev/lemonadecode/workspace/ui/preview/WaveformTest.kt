package dev.lemonadecode.workspace.ui.preview

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaveformTest {
  @Test
  fun `each stretch keeps its loudest sample, across channels`() {
    val builder = WaveformBuilder(expectedFrames = 4, resolution = 4)
    // Four stereo frames; the loudest of each is on either side.
    builder.addShorts(shortArrayOf(16384, -8192, 0, -32768, 4096, 0, 0, 0), count = 8, channels = 2)
    val waveform = builder.build()
    assertEquals(4, builder.frames)
    assertEquals(listOf(0.5f, 1f, 0.125f, 0f), waveform.peaks.toList())
    assertEquals(0f, waveform.rms.last())
  }

  @Test
  fun `a sound longer than expected merges its stretches, so the waveform stays the same size`() {
    val builder = WaveformBuilder(expectedFrames = 0, resolution = 8)
    val loud = ShortArray(256 * 20) { 16384 }
    val quiet = ShortArray(256 * 20) { 1638 }
    builder.addShorts(loud, loud.size, channels = 1)
    builder.addShorts(quiet, quiet.size, channels = 1)
    val waveform = builder.build()
    assertTrue(waveform.size in 8..16, "${waveform.size} stretches")
    assertEquals(0.5f, waveform.levels(0f, 0.45f).first)
    assertTrue(abs(waveform.levels(0.55f, 1f).first - 0.05f) < 0.001f)
    // A steady level's RMS is the level itself.
    assertTrue(abs(waveform.levels(0f, 0.45f).second - 0.5f) < 0.001f)
  }

  @Test
  fun `the stretch the sound ends in counts too`() {
    // Two frames to a stretch: five full ones, and the eleventh frame on its own.
    val builder = WaveformBuilder(expectedFrames = 10, resolution = 4)
    builder.addShorts(ShortArray(11) { 32767 }, count = 11, channels = 1)
    assertEquals(6, builder.build().size)
  }

  @Test
  fun `silence and no sound at all have nothing to show`() {
    assertEquals(0f to 0f, WaveformBuilder().build().levels(0f, 1f))
    val silent = WaveformBuilder(expectedFrames = 100).apply { addShorts(ShortArray(100), 100, 1) }.build()
    assertEquals(0f to 0f, silent.levels(0f, 1f))
  }

  @Test
  fun `FFmpeg's codec names read the way people know them`() {
    assertEquals(
      listOf("PCM", "PCM", "MP3", "AAC", "Vorbis", "Opus", "FLAC", "ALAC", "WMA", "AC-3", "ADPCM", "TTA"),
      listOf("pcm_s16le", "pcm_f32be", "mp3", "aac", "vorbis", "opus", "flac", "alac", "wmav2", "ac3", "adpcm_ima_wav", "tta").map(::codecLabel),
    )
  }

  @Test
  fun `short sounds show tenths of a second, longer ones whole seconds`() {
    assertEquals("0:00.0", formatAudioTime(0, 1_200_000))
    assertEquals("0:01.2", formatAudioTime(1_290_000, 1_290_000))
    assertEquals("0:09.9", formatAudioTime(9_990_000, 9_990_000))
    assertEquals("0:12", formatAudioTime(12_500_000, 30_000_000))
    assertEquals("1:05:09", formatAudioTime(3_909_000_000, 4_000_000_000))
  }

  @Test
  fun `sides shown together share the longest one's time scale`() {
    val timeline = AudioTimeline()
    timeline.add("before", 1_000_000)
    assertEquals(1f, timeline.span(1_000_000))
    timeline.add("after", 2_000_000)
    assertEquals(0.5f, timeline.span(1_000_000))
    assertEquals(1f, timeline.span(2_000_000))
    timeline.remove("after")
    assertEquals(1f, timeline.span(1_000_000))
  }
}
