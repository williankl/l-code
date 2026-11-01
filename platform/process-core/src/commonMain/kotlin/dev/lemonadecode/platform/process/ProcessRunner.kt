package dev.lemonadecode.platform.process

import dev.lemonadecode.platform.core.LemonadeCodeResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public data class ProcessOutput(
  val exitCode: Int,
  val stdout: String,
  val stderr: String,
) {
  val isSuccess: Boolean get() = exitCode == 0
}

/** What a program printed when its standard output is binary, e.g. `git cat-file blob`. */
public class BinaryProcessOutput(
  public val exitCode: Int,
  public val stdout: ByteArray,
  public val stderr: String,
) {
  public val isSuccess: Boolean get() = exitCode == 0
}

/** Runs short-lived command-line programs and collects what they print. */
public interface ProcessRunner {
  public suspend fun run(
    command: List<String>,
    workingDirectory: String,
    timeout: Duration = 30.seconds,
  ): LemonadeCodeResult<ProcessOutput, Throwable>

  /** Like [run], but keeps standard output as raw bytes instead of decoding it as text. */
  public suspend fun runForBytes(
    command: List<String>,
    workingDirectory: String,
    timeout: Duration = 30.seconds,
  ): LemonadeCodeResult<BinaryProcessOutput, Throwable>
}

/** The user's login shell, used to start agents with the same PATH as their own terminal. */
public interface UserShell {
  /** Absolute path, e.g. `/bin/zsh`. */
  public val path: String

  /** Short name, e.g. `zsh`, or `pwsh` for `C:\Program Files\PowerShell\7\pwsh.exe`. */
  public val name: String get() = shellName(path)
}

/** The user's home folder, for showing paths as `~/…`. */
public interface HomeDirectory {
  public val path: String
}

/**
 * `/Users/me/code/app` → `~/code/app` when [home] is `/Users/me`. A Windows [home] (`C:\Users\me`)
 * takes either separator after it and ignores case, as Windows does: `C:\Users\me\code` → `~\code`.
 */
public fun abbreviateHome(path: String, home: String): String {
  if (home.isEmpty()) return path
  val windows = '\\' in home
  return when {
    path.equals(home, ignoreCase = windows) -> "~"
    path.startsWith("$home/", ignoreCase = windows) -> "~" + path.substring(home.length)
    windows && path.startsWith("$home\\", ignoreCase = true) -> "~" + path.substring(home.length)
    else -> path
  }
}

/** `/bin/zsh` → `zsh`. A Windows program loses its folder and `.exe`: `C:\Windows\System32\cmd.exe` → `cmd`. */
internal fun shellName(path: String): String {
  val file = path.substringAfterLast('/').substringAfterLast('\\')
  return if (file.endsWith(".exe", ignoreCase = true)) file.dropLast(".exe".length).lowercase() else file
}

/**
 * The shell a Windows session runs: PowerShell 7 (`pwsh.exe`) when it's on the PATH, else the
 * Windows PowerShell that comes with Windows, else `%COMSPEC%`, else `cmd.exe`. Variable names
 * are matched ignoring case, as Windows does; [isFile] says whether a path exists.
 */
internal fun windowsShell(environment: Map<String, String>, isFile: (String) -> Boolean): String {
  fun variable(name: String): String? = environment.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
  fun join(folder: String, file: String) = folder.trimEnd('\\', '/') + "\\" + file
  val pwsh = variable("PATH").orEmpty().split(';')
    .map { it.trim().removeSurrounding("\"") }
    .filter { it.isNotEmpty() }
    .map { join(it, "pwsh.exe") }
    .firstOrNull(isFile)
  val windowsPowerShell = variable("SystemRoot")?.let { join(it, "System32\\WindowsPowerShell\\v1.0\\powershell.exe") }?.takeIf(isFile)
  return pwsh ?: windowsPowerShell ?: variable("COMSPEC") ?: "cmd.exe"
}
