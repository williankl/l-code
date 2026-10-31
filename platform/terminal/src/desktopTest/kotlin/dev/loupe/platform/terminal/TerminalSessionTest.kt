package dev.loupe.platform.terminal

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalSessionTest {
  @Test
  fun `output reaches the screen and the exit is reported`() = runBlocking {
    TerminalSession(
      TerminalLaunch(
        command = listOf("/bin/sh", "-c", "printf 'hello \\033[1mloupe\\033[0m'; sleep 0.3"),
        workingDirectory = System.getProperty("java.io.tmpdir"),
        columns = 40,
        rows = 5,
      ),
    ).use { session ->
      val exit = withTimeout(10_000) { session.signals.filterIsInstance<TerminalSignal.Exited>().first() }
      assertEquals(0, exit.exitCode)
      withTimeout(5_000) {
        while (!session.screenText().contains("hello loupe")) kotlinx.coroutines.delay(20)
      }
      assertTrue(session.screenText().startsWith("hello loupe"))
    }
  }

  @Test
  fun `typed input is echoed back`() = runBlocking<Unit> {
    TerminalSession(
      TerminalLaunch(
        command = listOf("/bin/cat"),
        workingDirectory = System.getProperty("java.io.tmpdir"),
        columns = 40,
        rows = 5,
      ),
    ).use { session ->
      session.send("ping\r")
      withTimeout(5_000) {
        while (!session.screenText().contains("ping")) kotlinx.coroutines.delay(20)
      }
    }
  }

  @Test
  fun `the current folder follows cd`() = runBlocking<Unit> {
    val start = java.nio.file.Files.createTempDirectory("loupe-cwd").toFile().canonicalFile
    val next = java.io.File(start, "inner").apply { mkdirs() }
    TerminalSession(TerminalLaunch(command = listOf("/bin/sh"), workingDirectory = start.path)).use { session ->
      withTimeout(5_000) {
        while (session.currentDirectory()?.let { java.io.File(it).canonicalPath } != start.path) kotlinx.coroutines.delay(50)
      }
      session.send("cd inner\r")
      withTimeout(5_000) {
        while (session.currentDirectory()?.let { java.io.File(it).canonicalPath } != next.canonicalPath) kotlinx.coroutines.delay(50)
      }
    }
  }

  @Test
  fun `the program status probe is answered`() = runBlocking<Unit> {
    // Reads the 9 bytes of the reply straight off the terminal and prints them in hex.
    val script = "stty raw -echo; printf '\\033]7501;?\\007'; head -c 9 | od -An -tx1"
    TerminalSession(TerminalLaunch(command = listOf("/bin/sh", "-c", "$script; sleep 1"), workingDirectory = System.getProperty("java.io.tmpdir"))).use { session ->
      withTimeout(5_000) {
        while (!session.screenText().split(Regex("\\s+")).joinToString(" ").contains("1b 5d 37 35 30 31 3b 3f 07")) delay(20)
      }
    }
  }

  @Test
  fun `program reports come through and stay off the screen`() = runBlocking<Unit> {
    val script = "sleep 0.5; printf 'before\\033]7501;state=working:app=claude-code:id=a1\\007after'; sleep 1"
    TerminalSession(TerminalLaunch(command = listOf("/bin/sh", "-c", script), workingDirectory = System.getProperty("java.io.tmpdir"))).use { session ->
      val report = withTimeout(5_000) { session.signals.filterIsInstance<TerminalSignal.Program>().first() }
      assertEquals(ProgramStatus(id = "a1", state = "working", app = "claude-code"), report.status)
      withTimeout(5_000) {
        while (!session.screenText().contains("beforeafter")) delay(20)
      }
    }
  }

  @Test
  fun `output read back comes back above a new terminal's own`() = runBlocking<Unit> {
    val long = "x".repeat(70)
    val first = TerminalLaunch(command = listOf("/bin/sh", "-c", "printf 'one\\n$long\\n\\nthree\\n'; sleep 30"), workingDirectory = System.getProperty("java.io.tmpdir"), columns = 40, rows = 10)
    val saved = TerminalSession(first).use { session ->
      withTimeout(5_000) {
        while (!session.screenText().contains("three")) delay(20)
      }
      session.recentOutput(maxLines = 100)
    }
    // The 70 characters wrapped at 40 columns, and still read back as one line.
    assertEquals(listOf("one", long, "", "three"), saved)

    val second = TerminalLaunch(command = listOf("/bin/sh", "-c", "printf 'fresh'; sleep 30"), workingDirectory = System.getProperty("java.io.tmpdir"), columns = 40, rows = 10, restoredOutput = saved)
    TerminalSession(second).use { session ->
      withTimeout(5_000) {
        while (!session.screenText().contains("fresh")) delay(20)
      }
      assertEquals(saved + "fresh", session.recentOutput(maxLines = 100))
    }
  }

  private fun TerminalSession.screenText(): String {
    textBuffer.lock()
    return try {
      textBuffer.getScreenLines()
    } finally {
      textBuffer.unlock()
    }
  }
}
