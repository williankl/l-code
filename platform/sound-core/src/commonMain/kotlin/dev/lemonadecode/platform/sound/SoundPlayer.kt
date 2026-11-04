package dev.lemonadecode.platform.sound

/** The short sounds Lemonade Code plays to say something happened. */
public enum class Chime {
  /** One soft, bell-like ding. */
  Ding,

  /** Two dings close together, the second a little higher. */
  DoubleDing,
}

/**
 * Plays [Chime]s on the computer's sound output. [play] returns at once and the sound plays in the
 * background; where there's no sound output, nothing plays and nothing fails.
 */
public interface SoundPlayer {
  public fun play(chime: Chime)
}
