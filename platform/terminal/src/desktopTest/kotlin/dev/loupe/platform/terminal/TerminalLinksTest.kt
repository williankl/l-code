package dev.loupe.platform.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalLinksTest {
  private fun at(text: String, index: Int, explicit: List<String?> = List(text.length) { null }) = linkAt(text, index, explicit)

  @Test
  fun `a written url is found from any of its characters`() {
    val text = "see https://example.com/a?b=1 for more"
    val link = FoundLink("https://example.com/a?b=1", 4 until 29)
    assertEquals(link, at(text, 4))
    assertEquals(link, at(text, 28))
    assertNull(at(text, 3))
    assertNull(at(text, 29))
  }

  @Test
  fun `sentence punctuation after a url is left out, balanced brackets kept`() {
    assertEquals("https://example.com/docs", at("read https://example.com/docs.", 8)?.url)
    assertEquals("https://example.com/x", at("(see https://example.com/x)", 8)?.url)
    assertEquals("https://en.wikipedia.org/wiki/Loupe_(tool)", at("https://en.wikipedia.org/wiki/Loupe_(tool), ok", 3)?.url)
    assertEquals("mailto:dev@loupe.dev", at("mail mailto:dev@loupe.dev!", 7)?.url)
  }

  @Test
  fun `a www address without a scheme opens over https`() {
    assertEquals(FoundLink("https://www.google.com", 7 until 21), at("I open www.google.com.", 10))
    assertEquals("https://www.example.co.uk/a?b=1", at("www.example.co.uk/a?b=1", 0)?.url)
    assertNull(at("awww.google.com", 2))
    assertNull(at("www.", 1))
  }

  @Test
  fun `only web, mail and file links count`() {
    assertNull(at("javascript://alert(1)", 2))
    assertNull(at("ssh://host", 2))
    assertEquals("file:///tmp/a.txt", at("file:///tmp/a.txt", 2)?.url)
  }

  @Test
  fun `a link a program marked wins, however its text reads`() {
    val text = "open docs now"
    val explicit = List(text.length) { if (it in 5..8) "https://loupe.dev" else null }
    assertEquals(FoundLink("https://loupe.dev", 5..8), at(text, 6, explicit))
    assertNull(at(text, 1, explicit))
  }
}
