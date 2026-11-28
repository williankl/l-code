package dev.lemonadecode.platform.terminal

/** A process's parent, as the kernel tells it; see [processParent]. */
public sealed interface ProcessParent {
  /** The parent's pid: 0 for launchd, whose parent is the kernel. */
  public data class Parent(val pid: Long) : ProcessParent

  /** No such process, where `ProcessHandle.of` is empty too. */
  public data object NoProcess : ProcessParent

  /** The read wasn't whole, or this isn't macOS: only `ProcessHandle` can tell. */
  public data object Unknown : ProcessParent
}

/**
 * The parent of process [pid] in one read of the record `ProcessHandle.of`, `isAlive` and `parent`
 * each read on macOS: `sysctl(KERN_PROC_PID)`, which counts a zombie as there. Its parent is the
 * field `getppid()` returns, which `ProcessHandle` asks instead for this process. Elsewhere it's
 * always [ProcessParent.Unknown].
 */
public fun processParent(pid: Long): ProcessParent = DarwinProcess.parent(pid)
