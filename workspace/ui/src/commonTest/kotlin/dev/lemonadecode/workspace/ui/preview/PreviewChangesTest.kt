package dev.lemonadecode.workspace.ui.preview

import dev.lemonadecode.workspace.ui.preview.MarkdownBlock.Heading
import dev.lemonadecode.workspace.ui.preview.MarkdownBlock.ListBlock
import dev.lemonadecode.workspace.ui.preview.MarkdownBlock.Paragraph
import dev.lemonadecode.workspace.ui.preview.MarkdownBlock.Section
import dev.lemonadecode.workspace.ui.preview.MarkdownInline.Link
import dev.lemonadecode.workspace.ui.preview.MarkdownInline.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PreviewChangesTest {
  private fun changes(text: String, vararg lines: IntRange) = MarkdownParser.changes(text.trimIndent(), ChangedLines(lines.toList()))

  private val document = """
    # Title

    First paragraph.

    Second paragraph.

    Third paragraph.
  """

  @Test
  fun `a changed paragraph shows on its own, with the lines left out around it`() {
    val parts = changes(document, 5..5)
    val excerpt = parts.excerpts.single()
    assertEquals(listOf(Paragraph(listOf(Text("Second paragraph.")))), excerpt.blocks)
    assertEquals(4, excerpt.hiddenBefore)
    assertEquals(2, parts.hiddenAfter)
  }

  @Test
  fun `blocks with only blank lines between them show as one part`() {
    val parts = changes(document, 3..3, 5..5)
    val excerpt = parts.excerpts.single()
    assertEquals(2, excerpt.blocks.size)
    assertEquals(2, excerpt.hiddenBefore)
  }

  @Test
  fun `parts apart show apart`() {
    val parts = changes(document, 1..1, 7..7)
    assertEquals(2, parts.excerpts.size)
    assertEquals(0, parts.excerpts[0].hiddenBefore)
    assertEquals(5, parts.excerpts[1].hiddenBefore)
    assertEquals(0, parts.hiddenAfter)
  }

  @Test
  fun `a change to blank lines alone shows nothing`() {
    assertTrue(changes(document, 2..2).excerpts.isEmpty())
  }

  @Test
  fun `a changed list item shows alone, keeping its number`() {
    val parts = changes(
      """
      1. one
      2. two
      3. three
      4. four
      """,
      3..3,
    )
    val list = assertIs<ListBlock>(parts.excerpts.single().blocks.single())
    assertEquals(3, list.start)
    assertEquals(listOf(Paragraph(listOf(Text("three")))), list.items.single().blocks)
    assertEquals(2, parts.excerpts.single().hiddenBefore)
    assertEquals(1, parts.hiddenAfter)
  }

  @Test
  fun `a changed table row shows under the table's header`() {
    val parts = changes(
      """
      | a | b |
      |---|---|
      | 1 | 2 |
      | 3 | 4 |
      | 5 | 6 |
      """,
      4..4,
    )
    val table = assertIs<MarkdownBlock.Table>(parts.excerpts.single().blocks.single())
    assertEquals(listOf(listOf(Text("a")), listOf(Text("b"))), table.header)
    assertEquals(listOf(listOf(listOf(Text("3")), listOf(Text("4")))), table.rows)
    // The header shows, so only the row above is left out.
    assertEquals(1, parts.excerpts.single().hiddenBefore)
    assertEquals(1, parts.hiddenAfter)
  }

  @Test
  fun `a change inside an HTML block shows the whole block`() {
    val parts = changes(
      """
      <div align="center">

      # Name

      </div>

      After.
      """,
      3..3,
    )
    val section = assertIs<Section>(parts.excerpts.single().blocks.single())
    assertEquals(MarkdownAlignment.Center, section.align)
    assertEquals(Heading(1, listOf(Text("Name"))), section.blocks.single())
    assertEquals(2, parts.hiddenAfter)
  }

  @Test
  fun `links still find definitions in parts left out`() {
    val parts = changes(
      """
      See [the docs][docs].

      [docs]: https://example.com
      """,
      1..1,
    )
    val paragraph = assertIs<Paragraph>(parts.excerpts.single().blocks.single())
    assertEquals(Link("https://example.com", listOf(Text("the docs"))), paragraph.content[1])
  }

  @Test
  fun `changed front matter shows as its code block`() {
    val parts = changes(
      """
      ---
      title: New
      ---

      Body.
      """,
      2..2,
    )
    val code = assertIs<MarkdownBlock.Code>(parts.excerpts.single().blocks.single())
    assertEquals("yaml", code.language)
    assertEquals("title: New", code.text)
  }

  @Test
  fun `a delimited table shows its header and changed rows, numbered by line`() {
    val table = tableOf("a,b\n1,2\n3,4\n5,6\n", tsv = false, changes = ChangedLines(listOf(3..3)))
    assertEquals(listOf("a", "b"), table.header)
    assertEquals(listOf(listOf("3", "4")), table.rows)
    assertEquals(listOf(3), table.lineNumbers)
    // Rows left out before the changed row and after it.
    assertEquals(setOf(0, 1), table.gaps)
  }

  @Test
  fun `a row whose quoted field spans lines changes with any of them`() {
    val table = tableOf("a,b\n\"x\ny\",2\n3,4\n", tsv = false, changes = ChangedLines(listOf(3..3)))
    assertEquals(listOf(listOf("x ↵ y", "2")), table.rows)
    assertEquals(listOf(2), table.lineNumbers)
    assertEquals(setOf(1), table.gaps)
  }

  @Test
  fun `a delimited table whose change touches no row is unchanged`() {
    val table = tableOf("a,b\n1,2\n\n3,4\n", tsv = false, changes = ChangedLines(listOf(3..3)))
    assertTrue(table.unchanged)
  }
}
