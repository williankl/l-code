package dev.lemonadecode.platform.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class OscScannerTest {
  private val replies = mutableListOf<String>()
  private val reports = mutableListOf<ProgramStatus>()
  private val folders = mutableListOf<String>()

  private fun scanner(windows: Boolean = false) = OscScanner(
    reply = { replies += it },
    report = { reports += it },
    folder = { folders += it },
    windows = windows,
  )

  private val scanner = scanner()

  private fun feed(vararg chunks: String, into: OscScanner = scanner) = chunks.forEach { into.scan(it.toCharArray(), 0, it.length) }

  @Test
  fun `the probe is answered with the same terminator`() {
    feed("\u001b]7501;?\u0007", "\u001b]7501;?\u001b\\")
    assertEquals(listOf("\u001b]7501;?\u0007", "\u001b]7501;?\u001b\\"), replies)
  }

  @Test
  fun `reports are read across chunks, between other output`() {
    feed("hello \u001b]0;title\u0007 \u001b]75", "01;state=blocked:app=claude-code:kind=permission:title=aGk=\u0007 and ", "\u001b]7501;state=working:app=claude-code:id=a1b2\u001b\\")
    assertEquals(
      listOf(
        ProgramStatus(id = null, state = "blocked", kind = "permission", app = "claude-code"),
        ProgramStatus(id = "a1b2", state = "working", app = "claude-code"),
      ),
      reports,
    )
  }

  @Test
  fun `other sequences and broken ones are ignored`() {
    feed("\u001b]8;;https://x\u0007link\u001b]8;;\u0007", "\u001b]7501;app=claude-code\u0007", "\u001b]75x;state=working\u0007")
    feed("\u001b]9;a notification\u0007", "\u001b]9;4;1;50\u0007", "\u001b]7;\u0007", "\u001b]9;9;\u0007")
    assertEquals(emptyList(), reports)
    assertEquals(emptyList(), replies)
    assertEquals(emptyList(), folders)
  }

  @Test
  fun `folder reports are read across chunks, with either terminator`() {
    feed("$ \u001b]7;file://mac", "book.local/Users/me/my%20app\u001b", "\\$ cd ..\r\n\u001b]7;file://macbook.local/Users/me\u0007$ ")
    feed("\u001b]9;9;/srv/code\u001b\\")
    assertEquals(listOf("/Users/me/my app", "/Users/me", "/srv/code"), folders)
  }

  @Test
  fun `Windows folders come back with backslashes`() {
    val windows = scanner(windows = true)
    feed("\u001b]9;9;C:\\Users\\me\\code\u001b\\PS C:\\Users\\me\\code> ", into = windows)
    feed("\u001b]9;9;\"C:\\Program Files\"\u001b\\", into = windows)
    feed("\u001b]7;file://desktop/C:/Users/me/repo\u0007", into = windows)
    assertEquals(listOf("C:\\Users\\me\\code", "C:\\Program Files", "C:\\Users\\me\\repo"), folders)
  }

  @Test
  fun `status and folder reports interleave`() {
    feed("\u001b]7;file:///tmp\u0007\u001b]7501;state=idle\u0007\u001b]9;9;/var\u0007")
    assertEquals(listOf(ProgramStatus(id = null, state = "idle")), reports)
    assertEquals(listOf("/tmp", "/var"), folders)
  }
}
