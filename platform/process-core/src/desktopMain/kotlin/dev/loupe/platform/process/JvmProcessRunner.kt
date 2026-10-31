package dev.loupe.platform.process

import dev.loupe.platform.architecture.LoupeCoroutineDispatchers
import dev.loupe.platform.core.LoupeResult
import dev.loupe.platform.core.safeSuspendRunCatchingOn
import dev.loupe.platform.di.AppScope
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
  private val dispatchers: LoupeCoroutineDispatchers,
) : ProcessRunner {
  override suspend fun run(
    command: List<String>,
    workingDirectory: String,
    timeout: Duration,
  ): LoupeResult<ProcessOutput, Throwable> = safeSuspendRunCatchingOn(dispatchers.io) {
    val process = ProcessBuilder(command)
      .directory(File(workingDirectory))
      .redirectInput(ProcessBuilder.Redirect.from(File(NullDevice)))
      .start()
    coroutineScope {
      val stdout = async(dispatchers.io) { process.inputStream.bufferedReader().readText() }
      val stderr = async(dispatchers.io) { process.errorStream.bufferedReader().readText() }
      if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly()
        throw TimeoutException("${command.first()} took longer than $timeout")
      }
      ProcessOutput(exitCode = process.exitValue(), stdout = stdout.await(), stderr = stderr.await())
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
