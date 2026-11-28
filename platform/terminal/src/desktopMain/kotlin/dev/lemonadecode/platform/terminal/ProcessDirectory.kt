package dev.lemonadecode.platform.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Reads a running process's current folder: `/proc` on Linux; on macOS what `lsof` names, from the
 * kernel as `lsof` reads it (see [DarwinProcess]): nothing for a process that doesn't exist, and from
 * `lsof` itself whenever that can't be told the same way.
 */
internal object ProcessDirectory {
  private val os = System.getProperty("os.name").orEmpty()

  /** Whether this OS can be asked at all. Elsewhere (Windows) [of] is always null. */
  val isSupported: Boolean = os.startsWith("Linux") || os.startsWith("Mac")

  fun of(pid: Long): String? = runCatching {
    when {
      os.startsWith("Linux") -> Files.readSymbolicLink(Paths.get("/proc/$pid/cwd")).toString()
      os.startsWith("Mac") -> when (val read = DarwinProcess.currentDirectory(pid)) {
        is DarwinProcess.DirectoryRead.Folder -> read.path
        DarwinProcess.DirectoryRead.NoProcess -> null
        DarwinProcess.DirectoryRead.AskLsof -> fromLsof(pid)
      }
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
