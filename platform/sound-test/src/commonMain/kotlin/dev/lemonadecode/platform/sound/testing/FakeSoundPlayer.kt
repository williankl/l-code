package dev.lemonadecode.platform.sound.testing

import dev.lemonadecode.platform.sound.Chime
import dev.lemonadecode.platform.sound.SoundPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Plays nothing, and keeps every chime it was asked to play, in order. */
public class FakeSoundPlayer : SoundPlayer {
  private val chimes = MutableStateFlow(emptyList<Chime>())

  public val played: List<Chime> get() = chimes.value

  override fun play(chime: Chime) {
    chimes.update { it + chime }
  }
}
