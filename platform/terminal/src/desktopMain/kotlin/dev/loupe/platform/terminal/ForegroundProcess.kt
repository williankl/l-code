package dev.loupe.platform.terminal

import java.io.File
import java.util.concurrent.TimeUnit

/** Tells whether a shell has its terminal to itself, or a program it started is in the foreground. */
internal object ForegroundProcess {
  private val os = System.getProperty("os.name").orEmpty()

  /** The terminal's foreground process group, read through the shell [pid]; null when it can't be told. */
  fun groupOf(pid: Long): Long? = runCatching {
    when {
      // Field 8 of /proc/<pid>/stat, counted after the parenthesised command name.
      os.startsWith("Linux") -> File("/proc/$pid/stat").readText().substringAfterLast(')').trim().split(' ')[5].toLong()
      os.startsWith("Mac") -> fromPs(pid)
      else -> null
    }
  }.getOrNull()

  private fun fromPs(pid: Long): Long? {
    val process = ProcessBuilder("ps", "-o", "tpgid=", "-p", pid.toString())
      .redirectErrorStream(true)
      .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
      .start()
    val output = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    return output.trim().toLongOrNull()
  }
}
