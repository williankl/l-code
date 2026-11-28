package dev.lemonadecode.platform.process

/** A process's parent, as the OS tells it; see [ProcessParents]. */
public sealed interface ProcessParent {
  /** The parent's pid: 0 for launchd, whose parent is the kernel. */
  public data class Parent(val pid: Long) : ProcessParent

  /** No such process, where `ProcessHandle.of` is empty too. */
  public data object NoProcess : ProcessParent

  /** It couldn't be read whole that way, or not on this system: only `ProcessHandle` can tell. */
  public data object Unknown : ProcessParent
}

/**
 * Processes' parents, each in one read of what `ProcessHandle` reads for them, without starting a
 * program. On macOS that's the process's `kinfo_proc` through `sysctl(KERN_PROC_PID)`, which counts
 * a zombie as there, and `getppid()` for this process. Elsewhere it can't tell, and says so.
 */
public interface ProcessParents {
  /** The parent of process [pid]. */
  public fun of(pid: Long): ProcessParent

  /** This process's parent, as `getppid()` gives it; null where it can't be asked. */
  public fun ofThisProcess(): Long?
}
