package dev.lemonadecode.workspace.ui.preview

import dev.lemonadecode.workspace.ui.preview.MarkdownBlock.Alert
import dev.lemonadecode.workspace.ui.preview.MarkdownBlock.Details
import dev.lemonadecode.workspace.ui.preview.MarkdownBlock.Heading
import dev.lemonadecode.workspace.ui.preview.MarkdownBlock.HtmlTable
import dev.lemonadecode.workspace.ui.preview.MarkdownBlock.Paragraph
import dev.lemonadecode.workspace.ui.preview.MarkdownBlock.Section
import dev.lemonadecode.workspace.ui.preview.MarkdownInline.HardBreak
import dev.lemonadecode.workspace.ui.preview.MarkdownInline.Image
import dev.lemonadecode.workspace.ui.preview.MarkdownInline.Link
import dev.lemonadecode.workspace.ui.preview.MarkdownInline.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MarkdownHtmlTest {
  private fun parse(text: String) = MarkdownParser.parse(text.trimIndent())

  @Test
  fun `a centered div holds the Markdown between its tags`() {
    val blocks = parse(
      """
      <div align="center">

      <img src="icon.svg" width="112" alt="The icon">

      # Lemonade Code

      **Your terminal**

      </div>
      """,
    )
    val section = assertIs<Section>(blocks.single())
    assertEquals(MarkdownAlignment.Center, section.align)
    assertEquals(Paragraph(listOf(Image("icon.svg", "The icon", width = MarkdownLength.Pixels(112f)))), section.blocks[0])
    assertEquals(Heading(1, listOf(Text("Lemonade Code"))), section.blocks[1])
    assertIs<Paragraph>(section.blocks[2])
  }

  @Test
  fun `html tables keep their cells, widths and alignment`() {
    val blocks = parse(
      """
      <table>
      <tr>
      <td width="50%"><img src="a.png" alt="A"></td>
      <td width="50%"><img src="b.png" alt="B"></td>
      </tr>
      <tr>
      <td align="center"><sub><b>To the same agent</b>, which picks up</sub></td>
      <td valign="top">plain</td>
      </tr>
      </table>
      """,
    )
    val table = assertIs<HtmlTable>(blocks.single())
    assertEquals(2, table.rows.size)
    val first = table.rows[0][0]
    assertEquals(MarkdownLength.Percent(50f), first.width)
    assertEquals(listOf(Paragraph(listOf(Image("a.png", "A")))), first.blocks)
    val caption = table.rows[1][0]
    assertEquals(MarkdownAlignment.Center, caption.align)
    assertEquals(
      listOf(
        Paragraph(
          listOf(
            MarkdownInline.Small(listOf(MarkdownInline.Strong(listOf(Text("To the same agent"))), Text(", which picks up")), MarkdownTextShift.Down),
          ),
        ),
      ),
      caption.blocks,
    )
    assertEquals(MarkdownVerticalAlignment.Top, table.rows[1][1].verticalAlign)
    assertEquals(MarkdownVerticalAlignment.Middle, table.rows[0][1].verticalAlign)
  }

  @Test
  fun `a cell can hold Markdown, and an unclosed cell is closed by the next`() {
    val blocks = parse(
      """
      <table>
      <tr>
      <td>

      ### Real terminals
      Each session is your own shell.

      <td>second
      </tr>
      </table>
      """,
    )
    val row = assertIs<HtmlTable>(blocks.single()).rows.single()
    assertEquals(2, row.size)
    assertEquals(Heading(3, listOf(Text("Real terminals"))), row[0].blocks[0])
    assertEquals(listOf(Paragraph(listOf(Text("second")))), row[1].blocks)
  }

  @Test
  fun `pictures, breaks and formatting in a cell become one paragraph`() {
    val cell = assertIs<HtmlTable>(
      parse("""<table><tr><td><img src="k.png" alt="K"><br><b>Keep awake</b><br><sub>No sleep.</sub></td></tr></table>""").single(),
    ).rows.single().single()
    assertEquals(
      listOf(
        Paragraph(
          listOf(
            Image("k.png", "K"),
            HardBreak,
            MarkdownInline.Strong(listOf(Text("Keep awake"))),
            HardBreak,
            MarkdownInline.Small(listOf(Text("No sleep.")), MarkdownTextShift.Down),
          ),
        ),
      ),
      cell.blocks,
    )
  }

  @Test
  fun `a br on its own leaves an empty line`() {
    assertEquals(listOf(Paragraph(listOf(Text("a"))), MarkdownBlock.Spacer(1), Paragraph(listOf(Text("b")))), parse("a\n\n<br>\n\nb"))
  }

  @Test
  fun `a newline after br doesn't start the next line with a space`() {
    val paragraph = assertIs<Paragraph>(parse("one<br>\ntwo").single())
    assertEquals(listOf(Text("one"), HardBreak, Text("two")), paragraph.content)
  }

  @Test
  fun `inline tags format the text between them`() {
    val paragraph = assertIs<Paragraph>(parse("Press <kbd>Ctrl</kbd> and <b>**bold**</b>, <a href=\"https://x.dev\">link</a>, <span>plain</span>").single())
    assertEquals(
      listOf(
        Text("Press "),
        MarkdownInline.Code("Ctrl"),
        Text(" and "),
        MarkdownInline.Strong(listOf(MarkdownInline.Strong(listOf(Text("bold"))))),
        Text(", "),
        Link("https://x.dev", listOf(Text("link"))),
        Text(", plain"),
      ),
      paragraph.content,
    )
  }

  @Test
  fun `an inline img keeps its size`() {
    val paragraph = assertIs<Paragraph>(parse("Icon <img src=\"i.png\" height=\"16\" alt=\"a > b\"> here").single())
    assertEquals(listOf(Text("Icon "), Image("i.png", "a > b", height = MarkdownLength.Pixels(16f)), Text(" here")), paragraph.content)
  }

  @Test
  fun `an unclosed inline tag shows its text`() {
    assertEquals(listOf(Text("a b")), assertIs<Paragraph>(parse("a <b>b").single()).content)
  }

  @Test
  fun `badges in links stay pictures in links`() {
    val paragraph = assertIs<Paragraph>(parse("[![Free](https://img.shields.io/badge/free)](#get-it)\n[![CI](ci.svg)](https://ci.dev)").single())
    assertEquals(
      listOf(
        Link("#get-it", listOf(Image("https://img.shields.io/badge/free", "Free"))),
        MarkdownInline.SoftBreak,
        Link("https://ci.dev", listOf(Image("ci.svg", "CI"))),
      ),
      paragraph.content,
    )
  }

  @Test
  fun `a quote starting with a marker is an alert`() {
    val alert = assertIs<Alert>(parse("> [!WARNING]\n> Don't build while it runs.").single())
    assertEquals(MarkdownAlertKind.Warning, alert.kind)
    assertEquals(listOf(Paragraph(listOf(Text("Don't build while it runs.")))), alert.blocks)
    assertIs<MarkdownBlock.Quote>(parse("> [!NOTE] with text after it").single())
  }

  @Test
  fun `details fold their content under the summary`() {
    val details = assertIs<Details>(
      parse(
        """
        <details>
        <summary>Click to <b>expand</b></summary>

        Hidden **markdown**.

        </details>
        """,
      ).single(),
    )
    assertEquals(false, details.open)
    assertEquals(listOf(Text("Click to "), MarkdownInline.Strong(listOf(Text("expand")))), details.summary)
    assertIs<Paragraph>(details.blocks.single())
    assertEquals(true, assertIs<Details>(parse("<details open><summary>S</summary>x</details>").single()).open)
  }

  @Test
  fun `html headings, lists and pre become their Markdown blocks`() {
    assertEquals(Heading(2, listOf(Text("Title")), MarkdownAlignment.Center), parse("<h2 align=\"center\">Title</h2>").single())
    val list = assertIs<MarkdownBlock.ListBlock>(parse("<ol start=\"3\">\n<li>one</li>\n<li>two</li>\n</ol>").single())
    assertEquals(3, list.start)
    assertEquals(2, list.items.size)
    assertEquals(
      MarkdownBlock.Code("kotlin", "fun main() = println(\"a & b\")"),
      parse("<pre><code class=\"language-kotlin\">fun main() = println(\"a &amp; b\")</code></pre>").single(),
    )
  }

  @Test
  fun `comments, scripts and stray closing tags show nothing`() {
    assertEquals(emptyList(), parse("<!-- note -->\n\n<script>alert(1)</script>\n\n</div>"))
  }

  @Test
  fun `entities in html text are decoded and spaces collapse`() {
    assertEquals(listOf(Paragraph(listOf(Text("Tom & Jerry © 2026")))), parse("<p>\n  Tom &amp;   Jerry\n  &copy; 2026\n</p>"))
  }

  @Test
  fun `picture addresses are read against the document`() {
    assertEquals(ImageAddress.Checkout("docs/images/hero.png"), resolveImage("README.md", "docs/images/hero.png"))
    assertEquals(ImageAddress.Checkout("docs/images/a b.png"), resolveImage("docs/GUIDE.md", "./images/a%20b.png?raw=true#x"))
    assertEquals(ImageAddress.Checkout("icons/app.svg"), resolveImage("docs/GUIDE.md", "../icons/app.svg"))
    assertEquals(ImageAddress.Checkout("icons/app.svg"), resolveImage("docs/deep/GUIDE.md", "/icons/app.svg"))
    assertEquals(ImageAddress.Web("https://img.shields.io/badge/x"), resolveImage("README.md", "https://img.shields.io/badge/x"))
    assertEquals(ImageAddress.Web("https://cdn.dev/a.png"), resolveImage("README.md", "//cdn.dev/a.png"))
    assertNull(resolveImage("README.md", "../outside.png"))
    assertNull(resolveImage("README.md", "file:///etc/passwd"))
    assertNull(resolveImage("README.md", "javascript:alert(1)"))
    assertNull(resolveImage(null, "relative.png"))
    val inline = assertIs<ImageAddress.Inline>(resolveImage("README.md", "data:image/svg+xml;base64,PHN2Zy8+"))
    assertEquals("<svg/>", inline.bytes.decodeToString())
  }

  @Test
  fun `html lengths read pixels and percentages`() {
    assertEquals(MarkdownLength.Pixels(112f), lengthOf("112"))
    assertEquals(MarkdownLength.Pixels(20f), lengthOf("20px"))
    assertEquals(MarkdownLength.Percent(100f), lengthOf("100%"))
    assertNull(lengthOf("auto"))
    assertNull(lengthOf("-3"))
  }
}
