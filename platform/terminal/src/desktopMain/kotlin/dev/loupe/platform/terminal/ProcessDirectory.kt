package dev.loupe.platform.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/** Reads a running process's current folder: `/proc` on Linux, `lsof` on macOS. */
internal object ProcessDirectory {
  private val os = System.getProperty("os.name").orEmpty()

  fun of(pid: Long): String? = runCatching {
    when {
      os.startsWith("Linux") -> Files.readSymbolicLink(Paths.get("/proc/$pid/cwd")).toString()
      os.startsWith("Mac") -> fromLsof(pid)
      else -> null
    }
  }.getOrNull()

  private fun fromLsof(pid: Long): String? {
    val process = ProcessBuilder("lsof", "-a", "-d", "cwd", "-p", pid.toString(), "-Fn")
      .redirectErrorStream(true)
      .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
      .start()
    val output = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    return output.lineSequence().firstOrNull { it.startsWith("n/") }?.removePrefix("n")
  }
}
