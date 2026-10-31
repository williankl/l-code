package dev.loupe.platform.process

import dev.loupe.platform.core.LoupeResult
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
  ): LoupeResult<ProcessOutput, Throwable>

  /** Like [run], but keeps standard output as raw bytes instead of decoding it as text. */
  public suspend fun runForBytes(
    command: List<String>,
    workingDirectory: String,
    timeout: Duration = 30.seconds,
  ): LoupeResult<BinaryProcessOutput, Throwable>
}

/** The user's login shell, used to start agents with the same PATH as their own terminal. */
public interface UserShell {
  /** Absolute path, e.g. `/bin/zsh`. */
  public val path: String

  /** Short name, e.g. `zsh`. */
  public val name: String get() = path.substringAfterLast('/')
}

/** The user's home folder, for showing paths as `~/…`. */
public interface HomeDirectory {
  public val path: String
}

/** `/Users/me/code/app` → `~/code/app` when [home] is `/Users/me`. */
public fun abbreviateHome(path: String, home: String): String = when {
  home.isEmpty() -> path
  path == home -> "~"
  path.startsWith("$home/") -> "~" + path.removePrefix(home)
  else -> path
}
