package dev.loupe.platform.power

import dev.loupe.platform.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Holds an OS sleep assertion with a helper process: `caffeinate` on macOS and
 * `systemd-inhibit` on Linux. Both tie the assertion to Loupe's process id, so it ends even if
 * Loupe is killed.
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

  private fun command(): List<String>? {
    val pid = ProcessHandle.current().pid().toString()
    val os = System.getProperty("os.name").orEmpty()
    return when {
      os.startsWith("Mac") -> listOf("caffeinate", "-i", "-w", pid)
      os.startsWith("Linux") -> listOf(
        "systemd-inhibit",
        "--what=idle",
        "--who=Loupe",
        "--why=Agents are working",
        "--mode=block",
        "sh",
        "-c",
        "while kill -0 $pid 2>/dev/null; do sleep 5; done",
      )
      else -> null
    }
  }
}
