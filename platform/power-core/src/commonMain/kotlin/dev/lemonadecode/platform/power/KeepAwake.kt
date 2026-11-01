package dev.lemonadecode.platform.power

/**
 * Stops the computer from idle-sleeping while held. The display may still turn off.
 * Holding twice is the same as holding once.
 */
public interface KeepAwake {
  public val isHeld: Boolean

  public fun setHeld(held: Boolean)
}
