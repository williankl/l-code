package dev.loupe.sessions.impl

import dev.loupe.platform.process.HomeDirectory
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClaudeAgentLocatorTest {
  private val home = Files.createTempDirectory("loupe-claude-home").toFile()
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

  private fun claudeSession(pid: Long, id: String, kind: String = "interactive", cwd: String = "/Users/you/code/store.front") {
    File(sessions, "$pid.json").writeText("""{"pid":$pid,"sessionId":"$id","cwd":"$cwd","startedAt":1,"kind":"$kind"}""")
  }

  /** Claude writes a conversation to disk once there is something in it, in a folder named after where it runs. */
  private fun transcript(id: String) {
    File(home, ".claude/projects/-Users-you-code-store-front").apply { mkdirs() }.resolve("$id.jsonl").writeText("{}\n")
  }

  @Test
  fun `the conversation running under the shell is the one to resume`() {
    claudeSession(program(), "0b7c3a2e-1111-4222-8333-944455556666")
    transcript("0b7c3a2e-1111-4222-8333-944455556666")
    claudeSession(elsewhere.pid(), "someone-elses")
    assertEquals("claude --resume 0b7c3a2e-1111-4222-8333-944455556666", locator.resumeCommand(shell.pid()))
  }

  @Test
  fun `claude open with nothing said yet starts claude again`() {
    claudeSession(program(), "0b7c3a2e-1111-4222-8333-944455556666")
    assertEquals("claude", locator.resumeCommand(shell.pid()))
  }

  @Test
  fun `nothing to resume without a conversation under the shell`() {
    claudeSession(elsewhere.pid(), "someone-elses")
    assertNull(locator.resumeCommand(shell.pid()))
  }

  @Test
  fun `an id that isn't plain never reaches the shell`() {
    claudeSession(program(), "x; touch pwned")
    assertNull(locator.resumeCommand(shell.pid()))
  }
}
