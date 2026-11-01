package dev.lemonadecode.platform.process

import kotlin.test.Test
import kotlin.test.assertEquals

class UserShellTest {
  private val systemRoot = "C:\\WINDOWS"
  private val pwsh = "C:\\Program Files\\PowerShell\\7\\pwsh.exe"
  private val windowsPowerShell = "C:\\WINDOWS\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
  private val comspec = "C:\\WINDOWS\\system32\\cmd.exe"

  private val environment = mapOf(
    "Path" to "C:\\WINDOWS\\system32;\"C:\\Program Files\\PowerShell\\7\\\";;C:\\Tools",
    "SystemRoot" to systemRoot,
    "ComSpec" to comspec,
  )

  @Test
  fun `PowerShell 7 on the PATH comes first`() {
    val files = setOf(pwsh, windowsPowerShell, comspec)
    assertEquals(pwsh, windowsShell(environment, files::contains))
  }

  @Test
  fun `then the Windows PowerShell that comes with Windows`() {
    val files = setOf(windowsPowerShell, comspec)
    assertEquals(windowsPowerShell, windowsShell(environment, files::contains))
  }

  @Test
  fun `then COMSPEC, then plain cmd`() {
    assertEquals(comspec, windowsShell(environment) { false })
    assertEquals("cmd.exe", windowsShell(emptyMap()) { false })
  }

  @Test
  fun `the PATH is looked through in order`() {
    val first = "C:\\Tools\\pwsh.exe"
    val env = mapOf("PATH" to "C:\\Tools\\;C:\\Program Files\\PowerShell\\7")
    assertEquals(first, windowsShell(env, setOf(first, pwsh)::contains))
  }

  @Test
  fun `short names drop the folder, and on Windows the exe`() {
    assertEquals("zsh", shellName("/bin/zsh"))
    assertEquals("fish", shellName("/opt/homebrew/bin/fish"))
    assertEquals("pwsh", shellName(pwsh))
    assertEquals("powershell", shellName(windowsPowerShell))
    assertEquals("cmd", shellName(comspec))
    assertEquals("cmd", shellName("C:\\WINDOWS\\system32\\CMD.EXE"))
    assertEquals("cmd", shellName("cmd.exe"))
  }

  @Test
  fun `the home folder shows as a tilde`() {
    assertEquals("~", abbreviateHome("/Users/me", "/Users/me"))
    assertEquals("~/code/app", abbreviateHome("/Users/me/code/app", "/Users/me"))
    assertEquals("/Users/meg/code", abbreviateHome("/Users/meg/code", "/Users/me"))
    assertEquals("/Users/Me/code", abbreviateHome("/Users/Me/code", "/Users/me"))
    assertEquals("/Users/me\\code", abbreviateHome("/Users/me\\code", "/Users/me"))
    assertEquals("/tmp", abbreviateHome("/tmp", ""))
  }

  @Test
  fun `a Windows home folder takes either separator and any case`() {
    val home = "C:\\Users\\me"
    assertEquals("~", abbreviateHome(home, home))
    assertEquals("~\\code", abbreviateHome("C:\\Users\\me\\code", home))
    assertEquals("~/code", abbreviateHome("C:\\Users\\me/code", home))
    assertEquals("~\\code", abbreviateHome("c:\\users\\me\\code", home))
    assertEquals("C:\\Users\\meg\\code", abbreviateHome("C:\\Users\\meg\\code", home))
    assertEquals("D:\\code", abbreviateHome("D:\\code", home))
  }
}
