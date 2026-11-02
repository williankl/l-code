package dev.lemonadecode.platform.power

/**
 * Stops the computer from sleeping, and its screen from dimming or locking, while held.
 * Holding twice is the same as holding once.
 */
public interface KeepAwake {
  public val isHeld: Boolean

  public fun setHeld(held: Boolean)
}
