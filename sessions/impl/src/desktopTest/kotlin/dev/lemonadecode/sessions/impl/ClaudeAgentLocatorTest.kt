package dev.lemonadecode.sessions.impl

import dev.lemonadecode.platform.process.HomeDirectory
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClaudeAgentLocatorTest {
  private val home = Files.createTempDirectory("lemonade-code-claude-home").toFile()
  private val sessions = File(home, ".claude/sessions").apply { mkdirs() }
  private val locator = ClaudeAgentLocator(
    object : HomeDirectory {
      override val path: String = home.path
    },
  )

  // A shell running a program, the way a terminal runs `claude`; `; true` keeps sh from exec'ing it.
  private val shell = ProcessBuilder("/bin/sh", "-c", "sleep 30; true").start()
  private val elsewhere = ProcessBuilder("/bin/sleep", "30").start()

  @AfterTest
  fun tearDown() {
    shell.descendants().forEach { it.destroy() }
    shell.destroy()
    elsewhere.destroy()
  }

  private fun program(): Long {
    repeat(100) {
      shell.toHandle().children().findFirst().orElse(null)?.let { return it.pid() }
      Thread.sleep(20)
    }
    error("the shell never started its program")
  }

  /** The two programs [shell] started, once both have. */
  private fun bothPrograms(shell: Process): List<Long> {
    repeat(100) {
      val pids = shell.toHandle().children().map { it.pid() }.toList()
      if (pids.size == 2) return pids
      Thread.sleep(20)
    }
    error("the shell never started both programs")
  }

  /** Where the made-up Claudes were started, unless a test says otherwise. */
  private val startedIn = "/Users/you/code/store.front"

  private fun claudeSession(pid: Long, id: String, kind: String = "interactive", cwd: String = startedIn, startedAt: Long = 1) {
    File(sessions, "$pid.json").writeText("""{"pid":$pid,"sessionId":"$id","cwd":"$cwd","startedAt":$startedAt,"kind":"$kind"}""")
  }

  /** Claude writes a conversation to disk once there is something in it, in a folder named after where it was started. */
  private fun transcript(id: String, text: String = "{}\n") {
    File(home, ".claude/projects/-Users-you-code-store-front").apply { mkdirs() }.resolve("$id.jsonl").appendText(text)
  }

  /** A transcript line, made up: the conversation wrote it in [cwd] at [at]. */
  private fun line(cwd: File, at: Long) = """{"isSidechain":false,"type":"assistant","cwd":"${cwd.path}","timestamp":"${Instant.ofEpochMilli(at)}"}""" + "\n"

  @Test
  fun `the conversation running under the shell is the one to resume`() {
    claudeSession(program(), "0b7c3a2e-1111-4222-8333-944455556666")
    transcript("0b7c3a2e-1111-4222-8333-944455556666")
    claudeSession(elsewhere.pid(), "someone-elses")
    assertEquals("claude --resume 0b7c3a2e-1111-4222-8333-944455556666", locator.find(shell.pid()).firstOrNull()?.resume)
  }

  @Test
  fun `claude open with nothing said yet starts claude again, and says nothing of where it works`() {
    val pid = program()
    claudeSession(pid, "0b7c3a2e-1111-4222-8333-944455556666")
    assertEquals(listOf(RunningAgent(pid, "claude", null, startedIn)), locator.find(shell.pid()))
  }

  @Test
  fun `each Claude names the folder it was started in, before it says where it works`() {
    val worktree = File(home, "code/store.front.worktrees/rate-limit").apply { mkdirs() }
    val pid = program()
    claudeSession(pid, "0b7c3a2e-1111-4222-8333-944455556666", cwd = worktree.path)
    val found = locator.find(shell.pid()).single()
    assertEquals(null, found.workingDirectory)
    assertEquals(worktree.path, found.startedIn)
  }

  @Test
  fun `where the conversation works comes from what this run wrote in its transcript`() {
    val id = "0b7c3a2e-1111-4222-8333-944455556666"
    val worktree = File(home, "code/store.front/.claude/worktrees/rate-limit").apply { mkdirs() }
    val docs = File(home, "code/store.front/docs").apply { mkdirs() }
    val pid = program()
    claudeSession(pid, id, startedAt = 1_000_000)
    // Written before this run, as a resumed conversation's history is.
    transcript(id, line(worktree, at = 999_000))
    assertEquals(listOf(RunningAgent(pid, "claude --resume $id", null, startedIn)), locator.find(shell.pid()))
    transcript(id, line(docs, at = 1_002_000))
    assertEquals(listOf(RunningAgent(pid, "claude --resume $id", docs.path, startedIn)), locator.find(shell.pid()))
  }

  @Test
  fun `every agent under the shell is found, the most recently started first, each with its own folder`() {
    val two = ProcessBuilder("/bin/sh", "-c", "sleep 30 & sleep 30; wait").start()
    try {
      val (first, second) = bothPrograms(two)
      val older = File(home, "code/store.front.worktrees/older").apply { mkdirs() }
      val newer = File(home, "code/store.front.worktrees/newer").apply { mkdirs() }
      claudeSession(first, "0b7c3a2e-1111-4222-8333-944455556666", startedAt = 1_000)
      claudeSession(second, "1c8d4b3f-2222-4333-8444-a55566667777", startedAt = 2_000)
      transcript("0b7c3a2e-1111-4222-8333-944455556666", line(older, at = 1_500))
      transcript("1c8d4b3f-2222-4333-8444-a55566667777", line(newer, at = 2_500))
      assertEquals(
        listOf(
          RunningAgent(second, "claude --resume 1c8d4b3f-2222-4333-8444-a55566667777", newer.path, startedIn),
          RunningAgent(first, "claude --resume 0b7c3a2e-1111-4222-8333-944455556666", older.path, startedIn),
        ),
        locator.find(two.pid()),
      )
    } finally {
      two.descendants().forEach { it.destroy() }
      two.destroy()
    }
  }

  @Test
  fun `a conversation found once is still resumed when Claude's own file names another folder`() {
    val id = "0b7c3a2e-1111-4222-8333-944455556666"
    val pid = program()
    claudeSession(pid, id)
    transcript(id)
    assertEquals("claude --resume $id", locator.find(shell.pid()).firstOrNull()?.resume)
    claudeSession(pid, id, cwd = "/Users/you/code/store.front/.claude/worktrees/rate-limit")
    assertEquals("claude --resume $id", locator.find(shell.pid()).firstOrNull()?.resume)
  }

  @Test
  fun `nothing to resume without a conversation under the shell`() {
    claudeSession(elsewhere.pid(), "someone-elses")
    assertNull(locator.find(shell.pid()).firstOrNull()?.resume)
  }

  @Test
  fun `an id that isn't plain never reaches the shell`() {
    claudeSession(program(), "x; touch pwned")
    assertNull(locator.find(shell.pid()).firstOrNull()?.resume)
  }

  @Test
  fun `an id Claude would read as an option never reaches it`() {
    claudeSession(program(), "--dangerously-skip-permissions")
    assertNull(locator.find(shell.pid()).firstOrNull()?.resume)
  }
}
