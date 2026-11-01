package dev.lemonadecode.sessions.impl

import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.platform.process.UserShell
import dev.lemonadecode.platform.terminal.TerminalLaunch
import dev.lemonadecode.platform.terminal.TerminalSession
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/** Starts the terminal behind a session. An interface so tests can run something harmless. */
internal interface ShellLauncher {
  val shellName: String

  /** Starts a shell in [directory], showing [restoredOutput] above its first prompt. */
  fun start(directory: String, restoredOutput: List<String> = emptyList()): TerminalSession
}

/** The user's own login shell, exactly what a new terminal window would run. */
@ContributesBinding(AppScope::class)
@Inject
internal class LoginShellLauncher(
  private val shell: UserShell,
) : ShellLauncher {
  override val shellName: String get() = shell.name

  override fun start(directory: String, restoredOutput: List<String>): TerminalSession {
    val start = shellCommand(shell.path, shell.name, IsWindows, System.getenv())
    return TerminalSession(
      TerminalLaunch(command = start.command, workingDirectory = directory, environment = start.environment, restoredOutput = restoredOutput),
    )
  }
}

/** What starts a shell: its command line, and variables to set on top of the app's own. */
internal data class ShellCommand(val command: List<String>, val environment: Map<String, String> = emptyMap())

/**
 * How to start the shell at [path]: `<shell> -l` on macOS and Linux, as a terminal window does.
 * Windows shells have no login flag. There the OS can't tell a shell's current folder, so
 * PowerShell and cmd.exe get a prompt that also reports it (OSC 9;9, as Windows Terminal reads it)
 * around the user's own prompt; [environment] is the app's, for cmd.exe's `PROMPT`.
 */
internal fun shellCommand(path: String, name: String, windows: Boolean, environment: Map<String, String>): ShellCommand = when {
  !windows -> ShellCommand(listOf(path, "-l"))
  name == "pwsh" || name == "powershell" -> ShellCommand(listOf(path, "-NoLogo", "-NoExit", "-Command", PowerShellPrompt))
  name == "cmd" -> {
    val key = environment.keys.firstOrNull { it.equals("PROMPT", ignoreCase = true) } ?: "PROMPT"
    val prompt = environment[key]?.takeIf { it.isNotEmpty() } ?: "\$P\$G"
    ShellCommand(listOf(path), mapOf(key to if (prompt.startsWith(CmdFolderReport)) prompt else CmdFolderReport + prompt))
  }
  else -> ShellCommand(listOf(path))
}

/**
 * Runs after the user's profile and wraps whatever prompt it set, so each prompt also writes the
 * folder. The original runs first, so it still sees `$?` of the last command, and what it returns
 * is returned as is. The folder goes through the host, which writes Unicode, unlike `[Console]`;
 * if that fails the prompt still shows. No double quotes: the command line passes them on unescaped.
 */
internal const val PowerShellPrompt: String = "\$global:__LemonadeCodePrompt = \$function:prompt; " +
  "function global:prompt { " +
  "\$text = & \$global:__LemonadeCodePrompt; " +
  "try { " +
  "\$location = \$executionContext.SessionState.Path.CurrentLocation; " +
  "if (\$location.Provider.Name -eq 'FileSystem') { " +
  "\$e = [string][char]27; \$Host.UI.Write(\$e + ']9;9;' + \$location.ProviderPath + \$e + '\\') " +
  "} " +
  "} catch { }; " +
  "\$text " +
  "}"

/** cmd.exe's prompt codes for `ESC ] 9 ; 9 ; <current folder> ESC \`. */
private const val CmdFolderReport = "\$E]9;9;\$P\$E\\"

internal val IsWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")
