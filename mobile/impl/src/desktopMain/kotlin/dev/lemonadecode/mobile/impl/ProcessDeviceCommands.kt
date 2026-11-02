package dev.lemonadecode.mobile.impl

import dev.lemonadecode.platform.architecture.LemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.platform.process.BinaryProcessOutput
import dev.lemonadecode.platform.process.HomeDirectory
import dev.lemonadecode.platform.process.ProcessOutput
import dev.lemonadecode.platform.process.ProcessRunner
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.time.Duration

/** One-shot commands through [ProcessRunner]; long-running ones as processes of their own, stopped when no longer read. */
@ContributesBinding(AppScope::class)
@Inject
internal class ProcessDeviceCommands(
  private val processes: ProcessRunner,
  private val home: HomeDirectory,
  private val dispatchers: LemonadeCodeCoroutineDispatchers,
) : DeviceCommands {
  private val folder: String get() = home.path.ifEmpty { System.getProperty("java.io.tmpdir") }

  override suspend fun run(command: List<String>, timeout: Duration): LemonadeCodeResult<ProcessOutput, Throwable> = processes.run(command, folder, timeout)

  override suspend fun runForBytes(command: List<String>, timeout: Duration): LemonadeCodeResult<BinaryProcessOutput, Throwable> = processes.runForBytes(command, folder, timeout)

  override fun lines(command: List<String>): Flow<String> = callbackFlow {
    val process = ProcessBuilder(command)
      .directory(File(folder))
      .redirectInput(ProcessBuilder.Redirect.from(File(NullDevice)))
      .start()
    val errors = async(dispatchers.io) { process.errorStream.lastLines() }
    val _ = launch(dispatchers.io) {
      try {
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach { send(it.removeSuffix("\r")) } }
      } catch (_: IOException) {
        // The process was stopped while being read.
      }
      val exitCode = runInterruptible { process.waitFor() }
      if (exitCode == 0) {
        close()
      } else {
        close(CommandExitException(exitCode, errors.await().ifBlank { "${File(command.first()).name} exited with $exitCode" }))
      }
    }
    awaitClose { process.destroyWithChildren() }
  }.flowOn(dispatchers.io)

  /** The last few lines a stream gives, for an error message. */
  private fun InputStream.lastLines(): String {
    val tail = ArrayDeque<String>()
    try {
      bufferedReader().useLines { lines ->
        lines.forEach {
          tail.addLast(it)
          if (tail.size > ErrorLines) {
            val _ = tail.removeFirst()
          }
        }
      }
    } catch (_: IOException) {
      // The process was stopped while being read.
    }
    return tail.joinToString("\n").trim()
  }

  private fun Process.destroyWithChildren() {
    descendants().forEach { val _ = it.destroy() }
    destroy()
  }

  private companion object {
    const val ErrorLines = 20
    val NullDevice: String = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "NUL" else "/dev/null"
  }
}
