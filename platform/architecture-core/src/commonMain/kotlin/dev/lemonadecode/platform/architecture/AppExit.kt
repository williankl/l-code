package dev.lemonadecode.platform.architecture

/** Ends the app the way closing its window does: sessions are saved, then the process exits. */
public interface AppExit {
  public fun exit()
}

/** The time of day, for text like "checked 2 h ago". Tests pass a fixed one. */
public fun interface WallClock {
  /** Milliseconds since the epoch. */
  public fun nowMillis(): Long
}
