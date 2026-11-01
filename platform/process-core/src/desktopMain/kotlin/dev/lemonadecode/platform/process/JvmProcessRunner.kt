package dev.lemonadecode.platform.process

import dev.lemonadecode.platform.architecture.LemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.core.safeSuspendRunCatchingOn
import dev.lemonadecode.platform.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration

@ContributesBinding(AppScope::class)
@Inject
internal class JvmProcessRunner(
  private val dispatchers: LemonadeCodeCoroutineDispatchers,
) : ProcessRunner {
  override suspend fun run(
    command: List<String>,
    workingDirectory: String,
    timeout: Duration,
  ): LemonadeCodeResult<ProcessOutput, Throwable> = safeSuspendRunCatchingOn(dispatchers.io) {
    val (exitCode, stdout, stderr) = execute(command, workingDirectory, timeout)
    ProcessOutput(exitCode = exitCode, stdout = stdout.decodeToString(), stderr = stderr)
  }

  override suspend fun runForBytes(
    command: List<String>,
    workingDirectory: String,
    timeout: Duration,
  ): LemonadeCodeResult<BinaryProcessOutput, Throwable> = safeSuspendRunCatchingOn(dispatchers.io) {
    val (exitCode, stdout, stderr) = execute(command, workingDirectory, timeout)
    BinaryProcessOutput(exitCode = exitCode, stdout = stdout, stderr = stderr)
  }

  private suspend fun execute(
    command: List<String>,
    workingDirectory: String,
    timeout: Duration,
  ): Triple<Int, ByteArray, String> {
    val process = ProcessBuilder(command)
      .directory(File(workingDirectory))
      .redirectInput(ProcessBuilder.Redirect.from(File(NullDevice)))
      .start()
    return coroutineScope {
      val stdout = async(dispatchers.io) { process.inputStream.readBytes() }
      val stderr = async(dispatchers.io) { process.errorStream.bufferedReader().readText() }
      if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly()
        throw TimeoutException("${command.first()} took longer than $timeout")
      }
      Triple(process.exitValue(), stdout.await(), stderr.await())
    }
  }

  private companion object {
    val NullDevice: String = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "NUL" else "/dev/null"
  }
}

@ContributesBinding(AppScope::class)
@Inject
internal class EnvironmentUserShell : UserShell {
  override val path: String = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
}

@ContributesBinding(AppScope::class)
@Inject
internal class SystemHomeDirectory : HomeDirectory {
  override val path: String = System.getProperty("user.home").orEmpty()
}
