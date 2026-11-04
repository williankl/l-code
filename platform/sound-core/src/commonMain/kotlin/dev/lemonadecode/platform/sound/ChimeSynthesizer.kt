package dev.lemonadecode.platform.sound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws the chimes in code, as 16-bit mono PCM, so there are no sound files to ship.
 *
 * A ding is a sine with two soft overtones, each fading away faster than the one below it, the way
 * a small bell rings out. A few milliseconds of fade-in at the start and a short fade-out at the end
 * keep it from clicking. A double ding is a second, higher ding starting while the first still rings.
 */
internal object ChimeSynthesizer {
  /** How long one ding lasts. */
  const val DingMillis: Int = 340

  /** How long after the first ding of a double ding the second one starts. */
  const val SecondDingDelayMillis: Int = 150

  /** The loudest a chime gets, as a fraction of full scale: easy to notice, never loud. */
  const val Peak: Double = 0.25

  /** C6, for a single ding and the first of two. */
  private const val LowHz = 1046.5

  /** E6, a major third up, for the second of two. */
  private const val HighHz = 1318.5

  private const val AttackMillis = 4
  private const val ReleaseMillis = 40

  private val Partials = listOf(
    Partial(ratio = 1.0, level = 1.0, decaySeconds = 0.12),
    Partial(ratio = 2.0, level = 0.25, decaySeconds = 0.07),
    // A little off a whole multiple, like a real bell's: the brief shimmer of the strike.
    Partial(ratio = 2.76, level = 0.1, decaySeconds = 0.035),
  )

  /** [chime] at [sampleRate] samples a second. */
  fun pcm(chime: Chime, sampleRate: Int): ShortArray {
    val signal = when (chime) {
      Chime.Ding -> ding(LowHz, sampleRate)
      Chime.DoubleDing -> {
        val first = ding(LowHz, sampleRate)
        val second = ding(HighHz, sampleRate)
        val offset = samples(SecondDingDelayMillis, sampleRate)
        DoubleArray(offset + second.size) { i -> first.getOrElse(i) { 0.0 } + second.getOrElse(i - offset) { 0.0 } }
      }
    }
    val loudest = signal.maxOf { abs(it) }
    val gain = if (loudest > 0) Peak / loudest else 0.0
    return ShortArray(signal.size) { i -> (signal[i] * gain * Short.MAX_VALUE).roundToInt().toShort() }
  }

  private fun ding(hz: Double, sampleRate: Int): DoubleArray {
    val length = samples(DingMillis, sampleRate)
    val attack = samples(AttackMillis, sampleRate)
    val release = samples(ReleaseMillis, sampleRate)
    return DoubleArray(length) { i ->
      val t = i.toDouble() / sampleRate
      val tone = Partials.sumOf { it.level * exp(-t / it.decaySeconds) * sin(2 * PI * hz * it.ratio * t) }
      tone * rise(i, attack) * rise(length - 1 - i, release)
    }
  }

  /** 0 at [i] = 0, rising smoothly to 1 at [i] = [length] - 1, and 1 from there on. */
  private fun rise(i: Int, length: Int): Double = if (i >= length - 1) 1.0 else 0.5 - 0.5 * cos(PI * i / (length - 1))

  private fun samples(millis: Int, sampleRate: Int): Int = (sampleRate.toLong() * millis / 1000).toInt()

  /** One overtone: its pitch as a multiple of the ding's, its loudness, and how fast it fades (seconds to 1/e). */
  private class Partial(val ratio: Double, val level: Double, val decaySeconds: Double)
}
