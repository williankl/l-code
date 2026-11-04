package dev.lemonadecode.platform.sound

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.LineUnavailableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaSoundPlayerTest {
  @Test
  fun `playing returns at once while the sound plays in the background`() {
    val release = CountDownLatch(1)
    val played = CountDownLatch(1)
    val player = BackgroundSoundPlayer {
      check(release.await(5, TimeUnit.SECONDS))
      played.countDown()
    }

    val started = System.nanoTime()
    player.play(Chime.Ding)
    assertTrue(System.nanoTime() - started < 1_000_000_000L, "play waited for the sound")

    release.countDown()
    assertTrue(played.await(5, TimeUnit.SECONDS))
  }

  @Test
  fun `a burst plays the chime that's ringing and one more, not every one`() {
    val release = CountDownLatch(1)
    val played = CopyOnWriteArrayList<Chime>()
    val player = BackgroundSoundPlayer { chime ->
      check(release.await(5, TimeUnit.SECONDS))
      played += chime
    }

    player.play(Chime.Ding)
    // The first one is on the background thread before the rest come in.
    Thread.sleep(100)
    repeat(10) { player.play(Chime.DoubleDing) }
    release.countDown()

    Thread.sleep(300)
    assertEquals(listOf(Chime.Ding, Chime.DoubleDing), played.toList())
  }

  @Test
  fun `a sound output that fails costs only that chime`() {
    val played = CountDownLatch(1)
    var first = true
    val player = BackgroundSoundPlayer {
      if (first) {
        first = false
        throw LineUnavailableException("the output went away")
      }
      played.countDown()
    }

    player.play(Chime.Ding)
    player.play(Chime.Ding)

    assertTrue(played.await(5, TimeUnit.SECONDS), "the next chime didn't play")
  }

  @Test
  fun `with no sound output, nothing plays and nothing fails`() {
    val asked = mutableListOf<Int>()
    val output = JavaSoundOutput { format ->
      asked += format.channels
      throw IllegalArgumentException("No line matching interface SourceDataLine is supported.")
    }

    assertFalse(output.play(Chime.DoubleDing))
    // Mono first, then stereo for outputs that only take that.
    assertEquals(listOf(1, 1, 2, 2), asked)
  }
}
