package dev.loupe.sessions.impl

import dev.loupe.platform.di.AppScope
import dev.loupe.platform.process.UserShell
import dev.loupe.platform.terminal.TerminalLaunch
import dev.loupe.platform.terminal.TerminalSession
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

  override fun start(directory: String, restoredOutput: List<String>): TerminalSession = TerminalSession(
    TerminalLaunch(command = listOf(shell.path, "-l"), workingDirectory = directory, restoredOutput = restoredOutput),
  )
}
