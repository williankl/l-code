package dev.lemonadecode.sessions.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellCommandTest {
  private val pwsh = "C:\\Program Files\\PowerShell\\7\\pwsh.exe"
  private val cmd = "C:\\WINDOWS\\system32\\cmd.exe"

  @Test
  fun `macOS and Linux start a login shell`() {
    assertEquals(ShellCommand(listOf("/bin/zsh", "-l")), shellCommand("/bin/zsh", "zsh", windows = false, environment = mapOf("PROMPT" to "%~ ")))
    assertEquals(ShellCommand(listOf("/usr/bin/pwsh", "-l")), shellCommand("/usr/bin/pwsh", "pwsh", windows = false, environment = emptyMap()))
  }

  @Test
  fun `PowerShell keeps its profile and gets a prompt that reports the folder`() {
    val start = shellCommand(pwsh, "pwsh", windows = true, environment = emptyMap())
    assertEquals(listOf(pwsh, "-NoLogo", "-NoExit", "-Command", PowerShellPrompt), start.command)
    assertEquals(emptyMap(), start.environment)
    assertEquals("powershell.exe", shellCommand("powershell.exe", "powershell", windows = true, environment = emptyMap()).command.first())
    assertFalse("-NoProfile" in start.command)
  }

  @Test
  fun `the PowerShell prompt wraps the user's and survives the command line`() {
    // Calls the prompt the profile left, first, and hands back what it returned.
    assertTrue(PowerShellPrompt.startsWith("\$global:__LemonadeCodePrompt = \$function:prompt;"))
    assertTrue("function global:prompt { \$text = & \$global:__LemonadeCodePrompt;" in PowerShellPrompt)
    assertTrue(PowerShellPrompt.endsWith("\$text }"))
    assertTrue("']9;9;' + \$location.ProviderPath + \$e + '\\')" in PowerShellPrompt)
    // pty4j quotes an argument with spaces but leaves inner double quotes as they are.
    assertFalse('"' in PowerShellPrompt)
    assertFalse('\n' in PowerShellPrompt)
    assertEquals(PowerShellPrompt.count { it == '{' }, PowerShellPrompt.count { it == '}' })
    assertEquals(PowerShellPrompt.count { it == '(' }, PowerShellPrompt.count { it == ')' })
  }

  @Test
  fun `cmd reports the folder ahead of the user's prompt`() {
    assertEquals(ShellCommand(listOf(cmd), mapOf("PROMPT" to "\$E]9;9;\$P\$E\\\$P\$G")), shellCommand(cmd, "cmd", windows = true, environment = emptyMap()))
    assertEquals(
      ShellCommand(listOf(cmd), mapOf("Prompt" to "\$E]9;9;\$P\$E\\[\$T] \$P\$G")),
      shellCommand(cmd, "cmd", windows = true, environment = mapOf("Prompt" to "[\$T] \$P\$G")),
    )
    // Started from inside Lemonade Code, the prompt already has it.
    val nested = "\$E]9;9;\$P\$E\\\$P\$G"
    assertEquals(mapOf("PROMPT" to nested), shellCommand(cmd, "cmd", windows = true, environment = mapOf("PROMPT" to nested)).environment)
  }

  @Test
  fun `other Windows shells start as they are`() {
    assertEquals(ShellCommand(listOf("C:\\tools\\nu.exe")), shellCommand("C:\\tools\\nu.exe", "nu", windows = true, environment = emptyMap()))
  }

  @Test
  fun `a folder's name is its last part`() {
    assertEquals("app", folderName("/Users/me/app", windows = false))
    assertEquals("a\\b", folderName("/Users/me/a\\b", windows = false))
    assertEquals("app", folderName("C:\\Users\\me\\app", windows = true))
    assertEquals("sub-1", folderName("C:/Users/me/repo.worktrees/sub-1", windows = true))
    assertEquals("", folderName("C:\\", windows = true))
  }
}
