package dev.lemonadecode.platform.architecture

/** Ends the app the way closing its window does: sessions are saved, then the process exits. */
public interface AppExit {
  public fun exit()
}
