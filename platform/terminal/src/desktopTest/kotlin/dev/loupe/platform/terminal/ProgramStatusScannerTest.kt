package dev.loupe.platform.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class ProgramStatusScannerTest {
  private val replies = mutableListOf<String>()
  private val reports = mutableListOf<ProgramStatus>()
  private val scanner = ProgramStatusScanner(reply = { replies += it }, report = { reports += it })

  private fun feed(vararg chunks: String) = chunks.forEach { scanner.scan(it.toCharArray(), 0, it.length) }

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
    assertEquals(emptyList(), reports)
    assertEquals(emptyList(), replies)
  }
}
