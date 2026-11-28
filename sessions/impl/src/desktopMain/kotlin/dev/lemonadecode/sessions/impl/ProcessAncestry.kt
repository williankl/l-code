package dev.lemonadecode.sessions.impl

import dev.lemonadecode.platform.process.ProcessParent
import dev.lemonadecode.platform.process.ProcessParents

/**
 * Whether process [pid] runs under [ancestor], at most [maxDepth] parents up, as the walk up its
 * parents through `ProcessHandle` ([handleWalk]) answers. On macOS the same walk reads each process
 * once through [parents] ([kernelWalk]), and `ProcessHandle` walks only when a read isn't whole.
 */
internal fun runsUnder(pid: Long, ancestor: Long, maxDepth: Int, parents: ProcessParents): Boolean = kernelWalk(pid, ancestor, maxDepth, parents) ?: handleWalk(pid, ancestor, maxDepth)

/**
 * The walk through `ProcessHandle`. Process 1, launchd or init, never has a parent, so it ends
 * there. `isAlive` reads again what `of` just read, through the same call with the same start-time
 * check, except on Windows, where `parent()` doesn't always repeat that check itself.
 */
internal fun handleWalk(pid: Long, ancestor: Long, maxDepth: Int): Boolean {
  var current = ProcessHandle.of(pid).orElse(null)?.takeIf { !IsWindows || it.isAlive } ?: return false
  repeat(maxDepth) {
    if (!IsWindows && current.pid() == 1L) return false
    val parent = current.parent().orElse(null) ?: return false
    if (parent.pid() == ancestor) return true
    current = parent
  }
  return false
}

/**
 * [handleWalk] reading each process once, on macOS; null when a read isn't whole, or elsewhere.
 *
 * There, `ProcessHandle.of`, `isAlive` and `parent` each read a process's `kinfo_proc` through
 * `sysctl(KERN_PROC_PID)`: it's present when the record is there, zombies included; `parent()`
 * reads the process again for its parent's pid (for this process it asks `getppid()`, the same
 * field), stops at a pid of 0 or less, and checks the start time, which only differs once the pid
 * has gone to another process; then it reads the parent. Read at the same moment, those records are
 * the same, so this reads each process once and goes on with the parent named in that read. As
 * before, the parent is read first and compared with [ancestor] only once it's there. No such
 * process ends the walk as `ProcessHandle` does; anything else hands it back to [handleWalk].
 */
internal fun kernelWalk(pid: Long, ancestor: Long, maxDepth: Int, parents: ProcessParents): Boolean? {
  var parent = when (val read = parents.of(pid)) {
    is ProcessParent.Parent -> read.pid
    ProcessParent.NoProcess -> return false
    ProcessParent.Unknown -> return null
  }
  var current = pid
  repeat(maxDepth) {
    if (current == 1L || parent <= 0) return false
    val next = when (val read = parents.of(parent)) {
      is ProcessParent.Parent -> read.pid
      ProcessParent.NoProcess -> return false
      ProcessParent.Unknown -> return null
    }
    if (parent == ancestor) return true
    current = parent
    parent = next
  }
  return false
}
