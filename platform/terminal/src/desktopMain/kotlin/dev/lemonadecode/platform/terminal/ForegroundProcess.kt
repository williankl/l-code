package dev.lemonadecode.platform.terminal

import java.io.File
import java.util.concurrent.TimeUnit

/** Tells whether a shell has its terminal to itself, or a program it started is in the foreground, and which. */
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

  /** The short name of process [pid], e.g. `claude`; null when it can't be told. */
  fun nameOf(pid: Long): String? = runCatching {
    when {
      os.startsWith("Linux") -> File("/proc/$pid/comm").readText()
      os.startsWith("Mac") -> ps(pid, "comm=").substringAfterLast('/')
      else -> null
    }
  }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

  private fun fromPs(pid: Long): Long? = ps(pid, "tpgid=").trim().toLongOrNull()

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
