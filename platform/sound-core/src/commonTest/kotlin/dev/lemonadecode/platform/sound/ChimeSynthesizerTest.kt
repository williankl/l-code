package dev.lemonadecode.platform.sound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChimeSynthesizerTest {
  private val rate = 44_100

  @Test
  fun `a ding lasts 340 ms, and a double ding 150 ms more`() {
    assertEquals(14_994, ChimeSynthesizer.pcm(Chime.Ding, rate).size)
    assertEquals(6_615 + 14_994, ChimeSynthesizer.pcm(Chime.DoubleDing, rate).size)
    assertEquals(16_320, ChimeSynthesizer.pcm(Chime.Ding, 48_000).size)
  }

  @Test
  fun `chimes start and end at silence, with no click either way`() {
    Chime.entries.forEach { chime ->
      val pcm = ChimeSynthesizer.pcm(chime, rate)
      assertEquals(0, pcm.first().toInt(), "$chime starts at ${pcm.first()}")
      assertEquals(0, pcm.last().toInt(), "$chime ends at ${pcm.last()}")
      // It fades in and out: its first and last half millisecond stay under 2% of full scale.
      val edge = rate / 2000
      val edges = pcm.take(edge) + pcm.takeLast(edge)
      assertTrue(edges.all { abs(it.toInt()) < Short.MAX_VALUE / 50 }, "$chime is loud at its edges: ${edges.maxOf { abs(it.toInt()) }}")
    }
  }

  @Test
  fun `chimes are gentle, well below full scale, and still clearly there`() {
    Chime.entries.forEach { chime ->
      val peak = ChimeSynthesizer.pcm(chime, rate).maxOf { abs(it.toInt()) }
      assertTrue(peak < Short.MAX_VALUE / 2, "$chime peaks at $peak")
      assertTrue(peak > Short.MAX_VALUE / 10, "$chime peaks at only $peak")
    }
  }

  @Test
  fun `a ding fades away`() {
    val pcm = ChimeSynthesizer.pcm(Chime.Ding, rate)
    val tenth = pcm.size / 10
    assertTrue(rms(pcm, 0, tenth) > 4 * rms(pcm, pcm.size - 2 * tenth, pcm.size - tenth))
  }

  @Test
  fun `the second ding of two is higher than the first`() {
    val pcm = ChimeSynthesizer.pcm(Chime.DoubleDing, rate)
    val second = rate * ChimeSynthesizer.SecondDingDelayMillis / 1000
    val window = rate / 50
    val low = 1046.5
    val high = 1318.5
    assertTrue(strength(pcm, 0, window, low) > 4 * strength(pcm, 0, window, high), "the first ding isn't at its own pitch")
    assertTrue(strength(pcm, second, second + window, high) > 4 * strength(pcm, 0, window, high), "the second ding isn't higher")
  }

  private fun rms(pcm: ShortArray, from: Int, to: Int): Double = sqrt((from until to).sumOf { pcm[it].toDouble() * pcm[it] } / (to - from))

  /** The amplitude of [hz] in [pcm] between [from] and [to]: one bin of a Fourier transform. */
  private fun strength(pcm: ShortArray, from: Int, to: Int, hz: Double): Double {
    var re = 0.0
    var im = 0.0
    for (i in from until to) {
      val angle = 2 * PI * hz * i / rate
      re += pcm[i] * cos(angle)
      im += pcm[i] * sin(angle)
    }
    return sqrt(re * re + im * im) / (to - from)
  }
}
