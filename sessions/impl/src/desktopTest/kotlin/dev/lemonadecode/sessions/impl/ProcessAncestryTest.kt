package dev.lemonadecode.sessions.impl

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The walks up a process's parents against the walk as it was ([AncestryOracle]), on real
 * processes. Each pair is read through the old walk just before and just after the new ones: when
 * those two disagree, the processes changed meanwhile and the pair is set aside, not compared.
 */
class ProcessAncestryTest {
  private val mac = System.getProperty("os.name").orEmpty().startsWith("Mac")
  private val started = mutableListOf<Process>()

  @AfterTest
  fun tearDown() {
    started.forEach { process ->
      process.descendants().forEach { it.destroyForcibly() }
      process.destroyForcibly()
    }
  }

  private class Comparison(private val mac: Boolean) {
    var compared = 0
    var changed = 0
    val mismatches = mutableListOf<String>()

    fun check(pid: Long, ancestor: Long) {
      val before = AncestryOracle.descendsFrom(pid, ancestor)
      val kernel = kernelWalk(pid, ancestor, MaxDepth)
      val handle = handleWalk(pid, ancestor, MaxDepth)
      val walk = runsUnder(pid, ancestor, MaxDepth)
      val after = AncestryOracle.descendsFrom(pid, ancestor)
      if (before != after) {
        changed++
        return
      }
      compared++
      // pid 0, the kernel's own, and anything out of a pid's range are `ProcessHandle`'s to walk.
      if (mac && pid in 1..Int.MAX_VALUE && kernel == null) mismatches += "$pid under $ancestor: the kernel didn't answer"
      if (kernel != null && kernel != before) mismatches += "$pid under $ancestor: kernel walk $kernel, as it was $before"
      if (handle != before) mismatches += "$pid under $ancestor: ProcessHandle walk $handle, as it was $before"
      if (walk != before) mismatches += "$pid under $ancestor: runsUnder $walk, as it was $before"
    }

    fun assertSame() = assertTrue(mismatches.isEmpty(), "${mismatches.size} of $compared differ: ${mismatches.take(5)}")
  }

  private val self = ProcessHandle.current().pid()
  private val selfParent = ProcessHandle.current().parent().map { it.pid() }.orElse(0L)
  private val unused = (99_000L downTo 2L).first { !ProcessHandle.of(it).isPresent }

  /** `sh` running `sleep`, and that `sleep`, once it has started. */
  private fun shellWithChild(): Pair<Long, Long> {
    val shell = ProcessBuilder("/bin/sh", "-c", "sleep 60; true").start().also(started::add)
    repeat(250) {
      shell.toHandle().children().findFirst().orElse(null)?.let { return shell.pid() to it.pid() }
      Thread.sleep(20)
    }
    error("the shell never started sleep")
  }

  @Test
  fun `this process and each of its ancestors, as process and as ancestor`() {
    val chain = generateSequence(ProcessHandle.current()) { it.parent().orElse(null) }.map { it.pid() }.toList()
    assertTrue(chain.size >= 2, "chain: $chain")
    val comparison = Comparison(mac)
    for (pid in chain + listOf(1L, unused)) {
      for (ancestor in chain + listOf(1L, unused, 0L)) comparison.check(pid, ancestor)
    }
    comparison.assertSame()
    assertEquals(0, comparison.changed, "these processes don't come and go")
    assertTrue(runsUnder(self, selfParent, MaxDepth))
  }

  @Test
  fun `a child, a grandchild, an ended child, a zombie, launchd and an unused pid`() {
    val (shell, grandchild) = shellWithChild()
    val ended = ProcessBuilder("true").start().also { it.waitFor() }.pid()
    // `sleep 0.1` ends while its parent, become `sleep 60`, never waits for it.
    val zombieParent = ProcessBuilder("/bin/sh", "-c", "sleep 0.1 & echo \$!; exec sleep 60").start().also(started::add)
    val zombie = zombieParent.inputStream.bufferedReader().readLine().trim().toLong()
    Thread.sleep(600)
    val pids = listOf(shell, grandchild, ended, zombie, zombieParent.pid(), 1L, unused, self, selfParent)
    val ancestors = pids + listOf(0L, -1L)
    val comparison = Comparison(mac)
    for (pid in pids) {
      for (ancestor in ancestors) comparison.check(pid, ancestor)
    }
    comparison.assertSame()
    assertEquals(0, comparison.changed, "these processes don't come and go")
    // The cases mean what they say.
    assertTrue(runsUnder(grandchild, shell, MaxDepth))
    assertTrue(runsUnder(grandchild, self, MaxDepth))
    assertTrue(runsUnder(zombie, zombieParent.pid(), MaxDepth), "a zombie is still under its parent")
    assertTrue(!runsUnder(ended, self, MaxDepth))
    assertTrue(!runsUnder(shell, grandchild, MaxDepth))
    assertTrue(!runsUnder(unused, 1L, MaxDepth))
  }

  /** Runs `sh` nested [levels] deep, each waiting for the next, the innermost becoming `sleep`. */
  private val nest = File.createTempFile("lemonade-code-nest", ".sh").apply {
    deleteOnExit()
    writeText("if [ \"\$1\" -gt 0 ]; then /bin/sh \"\$0\" \$((\$1 - 1)); true; else exec sleep 60; fi\n")
  }

  /** The processes from [top] down to the innermost, once all [levels] + 1 have started: innermost first. */
  private fun chainUnder(top: Long, levels: Int): List<Long> {
    repeat(500) {
      val down = generateSequence(ProcessHandle.of(top).orElse(null)) { it.children().findFirst().orElse(null) }.map { it.pid() }.toList()
      if (down.size == levels + 1 && ProcessHandle.of(down.last()).flatMap { it.info().command() }.orElse("").endsWith("/sleep")) return down.reversed()
      Thread.sleep(20)
    }
    error("the nest never reached $levels levels under $top")
  }

  /** A nest [levels] deep started from this process: innermost first, its outermost `sh` last. */
  private fun nested(levels: Int): List<Long> {
    val top = ProcessBuilder("/bin/sh", nest.path, levels.toString()).start().also(started::add)
    return chainUnder(top.pid(), levels)
  }

  /** A nest [levels] deep whose outermost `sh` launchd has taken over, its parent having ended. */
  private fun orphanedNest(levels: Int): List<Long> {
    val starter = ProcessBuilder("/bin/sh", "-c", "/bin/sh '${nest.path}' $levels >/dev/null 2>&1 & echo \$!").start()
    val top = starter.inputStream.bufferedReader().readLine().trim().toLong()
    starter.waitFor()
    repeat(250) {
      if (ProcessHandle.of(top).flatMap { it.parent() }.map { it.pid() }.orElse(-1L) == 1L) {
        val chain = chainUnder(top, levels)
        orphans += top
        return chain
      }
      Thread.sleep(20)
    }
    error("launchd never took over $top")
  }

  private val orphans = mutableListOf<Long>()

  @AfterTest
  fun endOrphans() {
    orphans.forEach { top ->
      ProcessHandle.of(top).ifPresent { handle ->
        handle.descendants().forEach { it.destroyForcibly() }
        handle.destroyForcibly()
      }
    }
  }

  @Test
  fun `a chain deeper than the walk goes, at the last step and one past it`() {
    // chain[k] is the k-th process up from the innermost; the walk goes 8 up.
    val chain = nested(10)
    val comparison = Comparison(mac)
    for (start in 0..3) {
      for (ancestor in chain) comparison.check(chain[start], ancestor)
    }
    comparison.assertSame()
    assertEquals(0, comparison.changed, "these processes don't come and go")
    assertTrue(runsUnder(chain[0], chain[7], MaxDepth))
    assertTrue(runsUnder(chain[0], chain[8], MaxDepth), "8 up is the last step")
    assertTrue(!runsUnder(chain[0], chain[9], MaxDepth), "9 up is past it")
    assertTrue(runsUnder(chain[2], chain[10], MaxDepth))
    assertTrue(!runsUnder(chain[1], chain[10], MaxDepth))
  }

  @Test
  fun `a chain under launchd, reaching it at the last step and one past it`() {
    if (!mac) return
    // launchd is the 8th process up from the innermost of a nest 7 deep under it, and the 9th of one 8 deep.
    val atLast = orphanedNest(7)
    val pastLast = orphanedNest(8)
    val comparison = Comparison(mac)
    for (chain in listOf(atLast, pastLast)) {
      for (start in chain.indices) {
        for (ancestor in chain + listOf(1L, self, unused)) comparison.check(chain[start], ancestor)
      }
    }
    comparison.assertSame()
    assertEquals(0, comparison.changed, "these processes don't come and go")
    assertTrue(runsUnder(atLast[0], 1L, MaxDepth), "launchd as the 8th up")
    assertTrue(!runsUnder(pastLast[0], 1L, MaxDepth), "launchd as the 9th up")
    assertTrue(runsUnder(pastLast[1], 1L, MaxDepth))
  }

  @Test
  fun `a pid beyond the int range, which the JDK reads as the pid it wraps to`() {
    val (shell, child) = shellWithChild()
    val wrapped = listOf((1L shl 32) + self, (1L shl 32) + child, (1L shl 32) + 1L, -(1L shl 32) + self)
    val comparison = Comparison(mac)
    for (pid in wrapped) {
      for (ancestor in listOf(self, selfParent, shell, 1L, 0L)) comparison.check(pid, ancestor)
    }
    comparison.assertSame()
    assertEquals(0, comparison.changed, "these processes don't come and go")
    assertTrue(runsUnder((1L shl 32) + self, selfParent, MaxDepth), "as the JDK, this process")
    assertTrue(runsUnder((1L shl 32) + child, shell, MaxDepth))
  }

  @Test
  fun `every process on the machine, under a few ancestors`() {
    val (shell, _) = shellWithChild()
    val pids = ProcessHandle.allProcesses().map { it.pid() }.toList()
    val comparison = Comparison(mac)
    for (pid in pids) {
      for (ancestor in listOf(self, selfParent, shell, 1L)) comparison.check(pid, ancestor)
    }
    println("compared ${comparison.compared} pairs over ${pids.size} processes; ${comparison.changed} changed while read")
    comparison.assertSame()
    assertTrue(comparison.compared > pids.size * 3, "compared ${comparison.compared} of ${pids.size * 4}")
  }

  private companion object {
    /** As deep as `ClaudeAgentLocator` walks. */
    const val MaxDepth = 8
  }
}
