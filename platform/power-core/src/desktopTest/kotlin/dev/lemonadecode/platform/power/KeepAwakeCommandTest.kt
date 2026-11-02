package dev.lemonadecode.platform.power

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeepAwakeCommandTest {
  @Test
  fun `macOS keeps the system and the display awake`() {
    assertEquals(listOf("caffeinate", "-d", "-i", "-w", "42"), keepAwakeCommand("Mac OS X", 42))
    assertNull(keepAwakeCommand("FreeBSD", 42))
  }

  @Test
  fun `Linux holds logind's idle and sleep inhibitor, and GNOME's where it can`() {
    val linux = keepAwakeCommand("Linux", 42).orEmpty()
    assertEquals(listOf("systemd-inhibit", "--what=idle:sleep"), linux.take(2))
    assertEquals(listOf("--mode=block", "sh", "-c"), linux.drop(4).dropLast(1))
    val script = linux.last()
    val wait = "while kill -0 42 2>/dev/null; do sleep 5; done"
    assertTrue("gnome-session-inhibit --inhibit idle:suspend" in script, script)
    assertTrue("sh -c '$wait' && exit 0" in script, "GNOME's inhibitor waits for the app: $script")
    assertTrue(script.endsWith("fi; $wait"), "Without GNOME the plain wait holds logind's inhibitor: $script")
  }

  @Test
  fun `Windows asks PowerShell to hold the assertion until the app exits`() {
    val command = keepAwakeCommand("Windows 11", 4242).orEmpty()
    assertEquals(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command"), command.dropLast(1))
    val script = command.last()
    // 0x80000003: ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED.
    assertEquals(0x80000003L, 2147483651L)
    assertTrue("SetThreadExecutionState([uint32]2147483651)" in script, script)
    assertTrue("[DllImport(' + \$q + 'kernel32.dll' + \$q + ')]" in script, script)
    assertTrue(script.endsWith("; Wait-Process -Id 4242"), script)
    assertFalse('"' in script, "Java doesn't escape double quotes for the Windows command line")
    assertEquals(script.count { it == '(' }, script.count { it == ')' })
    assertEquals(0, script.count { it == '\'' } % 2)
  }
}
