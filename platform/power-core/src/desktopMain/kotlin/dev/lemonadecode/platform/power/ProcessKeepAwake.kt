package dev.lemonadecode.platform.power

import dev.lemonadecode.platform.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Keeps the computer and its screen awake with a helper process: `caffeinate` on macOS,
 * `systemd-inhibit` (plus GNOME's own inhibitor) on Linux and PowerShell on Windows. Each ties the
 * assertion to Lemonade Code's process id, so it ends even if Lemonade Code is killed.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
internal class ProcessKeepAwake : KeepAwake by HelperProcessKeepAwake({ keepAwakeCommand(System.getProperty("os.name").orEmpty(), ProcessHandle.current().pid()) })

/** Holds the assertion while the process [command] starts is alive. */
internal class HelperProcessKeepAwake(private val command: () -> List<String>?) : KeepAwake {
  private var helper: Process? = null

  override val isHeld: Boolean
    @Synchronized get() = helper?.isAlive == true

  @Synchronized
  override fun setHeld(held: Boolean) {
    if (held == isHeld) return
    if (held) {
      helper = command()?.let { runCatching { ProcessBuilder(it).start() }.getOrNull() }
    } else {
      // The helpers run their waits as child processes, and on Linux the GNOME inhibitor lives in
      // one: stopping only the top process would leave it holding the screen awake.
      helper?.let { process ->
        process.descendants().forEach { it.destroy() }
        process.destroy()
      }
      helper = null
    }
  }
}

/**
 * The helper that keeps [os] and its screen awake while process [pid] runs; null where there is
 * none.
 */
internal fun keepAwakeCommand(os: String, pid: Long): List<String>? = when {
  // -d: no display sleep (so no screen saver or lock), -i: no idle system sleep.
  os.startsWith("Mac") -> listOf("caffeinate", "-d", "-i", "-w", pid.toString())
  os.startsWith("Linux") -> listOf(
    "systemd-inhibit",
    "--what=idle:sleep",
    "--who=Lemonade Code",
    "--why=Agents are working",
    "--mode=block",
    "sh",
    "-c",
    linuxKeepAwake(pid),
  )
  os.startsWith("Windows") -> listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", windowsKeepAwake(pid))
  else -> null
}

/**
 * Waits for process [pid] to exit. GNOME ignores logind's idle inhibitors and only asks its own
 * session manager before it dims, locks or suspends, so where `gnome-session-inhibit` exists the wait
 * runs under it too. If it can't inhibit (no GNOME session), the plain wait still holds the logind
 * inhibitor that the desktops which honour it, and logind's own suspend, respect.
 */
private fun linuxKeepAwake(pid: Long): String {
  val wait = "while kill -0 $pid 2>/dev/null; do sleep 5; done"
  return "if command -v gnome-session-inhibit >/dev/null 2>&1; then " +
    "gnome-session-inhibit --inhibit idle:suspend --app-id lemonade-code --reason 'Agents are working' sh -c '$wait' && exit 0; " +
    "fi; $wait"
}

/**
 * `SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED)`, which holds
 * until the thread that called it ends, then waits on that thread for process [pid] to exit. The
 * flags, 0x80000003, are written in decimal: Windows PowerShell reads that hex as a negative Int32.
 * No double quotes in it, since Java passes them on unescaped: `[char]34` stands in for the one C#
 * needs.
 */
private fun windowsKeepAwake(pid: Long): String = "\$q = [char]34; " +
  "\$power = Add-Type -Namespace LemonadeCode -Name Power -PassThru -MemberDefinition " +
  "('[DllImport(' + \$q + 'kernel32.dll' + \$q + ')] public static extern uint SetThreadExecutionState(uint flags);'); " +
  "[void]\$power::SetThreadExecutionState([uint32]2147483651); " +
  "Wait-Process -Id $pid"
