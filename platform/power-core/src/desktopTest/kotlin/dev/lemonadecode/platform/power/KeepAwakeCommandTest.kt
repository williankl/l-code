package dev.lemonadecode.platform.power

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeepAwakeCommandTest {
  @Test
  fun `macOS and Linux keep their helpers`() {
    assertEquals(listOf("caffeinate", "-i", "-w", "42"), keepAwakeCommand("Mac OS X", 42))
    val linux = keepAwakeCommand("Linux", 42).orEmpty()
    assertEquals("systemd-inhibit", linux.first())
    assertEquals("while kill -0 42 2>/dev/null; do sleep 5; done", linux.last())
    assertNull(keepAwakeCommand("FreeBSD", 42))
  }

  @Test
  fun `Windows asks PowerShell to hold the assertion until the app exits`() {
    val command = keepAwakeCommand("Windows 11", 4242).orEmpty()
    assertEquals(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command"), command.dropLast(1))
    val script = command.last()
    // 0x80000001: ES_CONTINUOUS | ES_SYSTEM_REQUIRED.
    assertEquals(0x80000001L, 2147483649L)
    assertTrue("SetThreadExecutionState([uint32]2147483649)" in script, script)
    assertTrue("[DllImport(' + \$q + 'kernel32.dll' + \$q + ')]" in script, script)
    assertTrue(script.endsWith("; Wait-Process -Id 4242"), script)
    assertFalse('"' in script, "Java doesn't escape double quotes for the Windows command line")
    assertEquals(script.count { it == '(' }, script.count { it == ')' })
    assertEquals(0, script.count { it == '\'' } % 2)
  }
}
