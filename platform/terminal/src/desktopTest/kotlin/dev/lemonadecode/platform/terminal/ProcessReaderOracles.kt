package dev.lemonadecode.platform.terminal

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * How the app read a process's folder and groups on macOS before it asked the kernel itself, kept
 * exactly as it was: `lsof` and `ps`, started for every read. What [DarwinProcess], and the readers
 * built on it, must agree with.
 */
internal object ProcessReaderOracles {
  /** `ProcessDirectory.of` as it was on macOS. */
  fun directoryOf(pid: Long): String? = runCatching { fromLsof(pid) }.getOrNull()

  /** `ForegroundProcess.groupOf` as it was on macOS. */
  fun terminalGroupOf(pid: Long): Long? = runCatching { ps(pid, "tpgid=").trim().toLongOrNull() }.getOrNull()

  /** `ForegroundProcess.processGroupOf` as it was on macOS. */
  fun processGroupOf(pid: Long): Long? = runCatching { ps(pid, "pgid=").trim().toLongOrNull() }.getOrNull()

  private fun fromLsof(pid: Long): String? {
    val process = ProcessBuilder("lsof", "-a", "-d", "cwd", "-p", pid.toString(), "-Fn")
      .redirectErrorStream(true)
      .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
      .start()
    val output = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    return output.lineSequence().firstOrNull { it.startsWith("n/") }?.removePrefix("n")
  }

  private fun ps(pid: Long, field: String): String {
    val process = ProcessBuilder("ps", "-o", field, "-p", pid.toString())
      .redirectErrorStream(true)
      .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
      .start()
    val output = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    return output
  }
}
