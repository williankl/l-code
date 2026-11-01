package dev.lemonadecode.platform.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalSelectionTest {
  private val lines = listOf(
    LineText("\$ git status          ", wrapped = false),
    LineText("On branch feat/rate-li", wrapped = true),
    LineText("mit", wrapped = false),
    LineText("", wrapped = false),
    LineText("nothing to commit", wrapped = false),
  )

  private fun text(selection: TerminalSelection) = selection.text { lines.getOrNull(it) }

  @Test
  fun `a drag inside one line takes just those cells`() {
    assertEquals("git", text(TerminalSelection(TextPoint(0, 2), TextPoint(0, 5))))
  }

  @Test
  fun `dragging backwards selects the same text`() {
    assertEquals("git", text(TerminalSelection(TextPoint(0, 5), TextPoint(0, 2))))
  }

  @Test
  fun `lines end with a newline, padding is dropped and wrapped lines join`() {
    val selection = TerminalSelection(TextPoint(0, 0), TextPoint(4, 7))
    assertEquals("\$ git status\nOn branch feat/rate-limit\n\nnothing", text(selection))
  }

  @Test
  fun `the highlight covers whole lines in the middle`() {
    val selection = TerminalSelection(TextPoint(1, 3), TextPoint(3, 2))
    assertEquals(3 until 22, selection.columnsOn(1, width = 22))
    assertEquals(0 until 22, selection.columnsOn(2, width = 22))
    assertEquals(0 until 2, selection.columnsOn(3, width = 22))
    assertNull(selection.columnsOn(4, width = 22))
    assertNull(TerminalSelection(TextPoint(1, 3), TextPoint(1, 3)).columnsOn(1, width = 22))
  }

  @Test
  fun `a double click takes the word, paths included`() {
    assertEquals("feat/rate-li", text(TerminalSelection.word(1, column = 12, text = lines[1].text)))
    assertEquals("status", text(TerminalSelection.word(0, column = 7, text = lines[0].text)))
    assertTrue(TerminalSelection.word(0, column = 15, text = lines[0].text).let { it.end.column - it.start.column == 1 })
  }

  @Test
  fun `a triple click takes the line`() {
    assertEquals("nothing to commit", text(TerminalSelection.line(4, width = 22)))
  }
}
