package dev.lemonadecode.mobile.impl

import dev.lemonadecode.platform.architecture.LemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.architecture.defaultCoroutineDispatchers
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.process.BinaryProcessOutput
import dev.lemonadecode.platform.process.HomeDirectory
import dev.lemonadecode.platform.process.ProcessOutput
import dev.lemonadecode.platform.process.ProcessRunner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Runs real processes, so it's skipped on Windows, which has no `sh`. */
class ProcessDeviceCommandsTest {
  private val dispatchers: LemonadeCodeCoroutineDispatchers = defaultCoroutineDispatchers()
  private val commands = ProcessDeviceCommands(UnusedRunner, TempHome, dispatchers)
  private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

  @Test
  fun `a stream emits each line and ends with the program`() = runBlocking<Unit> {
    if (isWindows) return@runBlocking
    assertEquals(listOf("one", "two", ""), commands.lines(listOf("sh", "-c", "printf 'one\\ntwo\\r\\n\\n'")).toList())
  }

  @Test
  fun `a program that fails ends the stream with what it printed`() = runBlocking<Unit> {
    if (isWindows) return@runBlocking
    val failure = assertFailsWith<CommandExitException> {
      commands.lines(listOf("sh", "-c", "echo partial; echo 'error: device offline' >&2; exit 3")).toList()
    }
    assertEquals(3, failure.exitCode)
    assertEquals("error: device offline", failure.message)
  }

  @Test
  fun `the program is stopped when nobody reads it any more`() = runBlocking<Unit> {
    if (isWindows) return@runBlocking
    val pidFile = File(Files.createTempDirectory("lemonade-code-stream").toFile(), "pid")
    val first = withTimeout(10_000) {
      commands.lines(listOf("sh", "-c", "echo $$ > '${pidFile.path}'; echo ready; exec sleep 60")).first()
    }
    assertEquals("ready", first)
    val pid = pidFile.readText().trim().toLong()
    withTimeout(10_000) {
      while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(50)
    }
    assertTrue(ProcessHandle.of(pid).map { !it.isAlive }.orElse(true))
  }

  private object TempHome : HomeDirectory {
    override val path: String = System.getProperty("java.io.tmpdir")
  }

  private object UnusedRunner : ProcessRunner {
    override suspend fun run(command: List<String>, workingDirectory: String, timeout: Duration): LemonadeCodeResult<ProcessOutput, Throwable> = error("not used")

    override suspend fun runForBytes(command: List<String>, workingDirectory: String, timeout: Duration): LemonadeCodeResult<BinaryProcessOutput, Throwable> = error("not used")
  }
}
