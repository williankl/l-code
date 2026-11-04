package dev.lemonadecode.platform.sound

import dev.lemonadecode.platform.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** Plays chimes through Java Sound, on a background thread of its own. */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
internal class JavaSoundPlayer : SoundPlayer by BackgroundSoundPlayer(JavaSoundOutput(AudioSystem::getSourceDataLine)::play)

/**
 * Hands each chime to [output] on one background thread, so [play] never waits on a sound. A chime
 * asked for while another plays waits its turn, and any more than that one are dropped, so a burst
 * can't keep ringing. If [output] fails, only that chime is lost.
 */
internal class BackgroundSoundPlayer(private val output: (Chime) -> Unit) : SoundPlayer {
  private val worker = ThreadPoolExecutor(
    1,
    1,
    0L,
    TimeUnit.MILLISECONDS,
    ArrayBlockingQueue(1),
    { task -> Thread(task, "lemonade-code-sounds").apply { isDaemon = true } },
    ThreadPoolExecutor.DiscardPolicy(),
  )

  override fun play(chime: Chime) {
    worker.execute {
      try {
        output(chime)
      } catch (_: Exception) {
        // The sound output went away: nothing plays this time, and the next chime tries again.
      } catch (_: LinkageError) {
        // Java Sound's native part is missing, as on a headless JDK.
      }
    }
  }
}

/** Plays a chime to its end on a line [lineFor] gives: mono if the output takes it, else stereo. */
internal class JavaSoundOutput(private val lineFor: (AudioFormat) -> SourceDataLine) {
  /** Plays [chime] and waits for it to end. False, with nothing played, where no output takes it. */
  @IgnorableReturnValue
  fun play(chime: Chime): Boolean {
    for ((rate, channels) in Formats) {
      val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
      val line = try {
        lineFor(format)
      } catch (_: Exception) {
        continue
      }
      try {
        line.open(format)
      } catch (_: Exception) {
        line.close()
        continue
      }
      try {
        val bytes = ChimeSynthesizer.pcm(chime, rate).toBytes(channels)
        line.start()
        val written = line.write(bytes, 0, bytes.size)
        line.drain()
        return written > 0
      } finally {
        line.close()
      }
    }
    return false
  }

  private companion object {
    /** Sample rate and channels, in the order they're tried: every output takes one of them. */
    val Formats = listOf(44_100 to 1, 48_000 to 1, 44_100 to 2, 48_000 to 2)
  }
}

/** Signed 16-bit little-endian frames, each sample repeated in every one of [channels]. */
private fun ShortArray.toBytes(channels: Int): ByteArray {
  val bytes = ByteArray(size * channels * 2)
  var at = 0
  for (sample in this) {
    repeat(channels) {
      bytes[at++] = (sample.toInt() and 0xFF).toByte()
      bytes[at++] = (sample.toInt() shr 8).toByte()
    }
  }
  return bytes
}
