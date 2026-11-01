package dev.lemonadecode.platform.power

import dev.lemonadecode.platform.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Holds an OS sleep assertion with a helper process: `caffeinate` on macOS, `systemd-inhibit` on
 * Linux and PowerShell on Windows. Each ties the assertion to Lemonade Code's process id, so it ends
 * even if Lemonade Code is killed.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
internal class ProcessKeepAwake : KeepAwake {
  private var helper: Process? = null

  override val isHeld: Boolean
    @Synchronized get() = helper?.isAlive == true

  @Synchronized
  override fun setHeld(held: Boolean) {
    if (held == isHeld) return
    if (held) {
      helper = command()?.let { runCatching { ProcessBuilder(it).start() }.getOrNull() }
    } else {
      helper?.destroy()
      helper = null
    }
  }

  private fun command(): List<String>? = keepAwakeCommand(System.getProperty("os.name").orEmpty(), ProcessHandle.current().pid())
}

/** The helper that keeps [os] from idle-sleeping while process [pid] runs; null where there is none. */
internal fun keepAwakeCommand(os: String, pid: Long): List<String>? = when {
  os.startsWith("Mac") -> listOf("caffeinate", "-i", "-w", pid.toString())
  os.startsWith("Linux") -> listOf(
    "systemd-inhibit",
    "--what=idle",
    "--who=Lemonade Code",
    "--why=Agents are working",
    "--mode=block",
    "sh",
    "-c",
    "while kill -0 $pid 2>/dev/null; do sleep 5; done",
  )
  os.startsWith("Windows") -> listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", windowsKeepAwake(pid))
  else -> null
}

/**
 * `SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)`, which holds until the thread
 * that called it ends, then waits on that thread for process [pid] to exit. The flags, 0x80000001,
 * are written in decimal: Windows PowerShell reads that hex as a negative Int32. No double quotes
 * in it, since Java passes them on unescaped: `[char]34` stands in for the one C# needs.
 */
private fun windowsKeepAwake(pid: Long): String = "\$q = [char]34; " +
  "\$power = Add-Type -Namespace LemonadeCode -Name Power -PassThru -MemberDefinition " +
  "('[DllImport(' + \$q + 'kernel32.dll' + \$q + ')] public static extern uint SetThreadExecutionState(uint flags);'); " +
  "[void]\$power::SetThreadExecutionState([uint32]2147483649); " +
  "Wait-Process -Id $pid"
