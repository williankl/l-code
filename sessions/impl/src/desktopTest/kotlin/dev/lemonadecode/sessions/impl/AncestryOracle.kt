package dev.lemonadecode.sessions.impl

/**
 * How `ClaudeAgentLocator` told whether a process runs under a shell before it read the kernel
 * itself, kept exactly as it was: `ProcessHandle.of`, `isAlive`, then up to 8 `parent()`s. What
 * [runsUnder], [kernelWalk] and [handleWalk] must agree with.
 */
internal object AncestryOracle {
  fun descendsFrom(pid: Long, ancestor: Long): Boolean {
    var current = ProcessHandle.of(pid).orElse(null)?.takeIf { it.isAlive } ?: return false
    repeat(MaxDepth) {
      val parent = current.parent().orElse(null) ?: return false
      if (parent.pid() == ancestor) return true
      current = parent
    }
    return false
  }

  private const val MaxDepth = 8
}
