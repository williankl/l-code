package dev.lemonadecode.workspace.ui.preview

/**
 * What the block reader finds before HTML is put together: a Markdown block, or a piece of raw
 * HTML. READMEs open a `<div>` in one piece and close it in another, with Markdown in between, so
 * the pieces only make sense together; [assembleHtml] nests them the way a browser does.
 */
internal sealed interface MarkdownItem {
  class Block(val block: MarkdownBlock) : MarkdownItem

  class Html(val text: String) : MarkdownItem
}

/** Tags with no content and no closing tag. */
internal val VoidHtmlTags = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

/** Tags whose content is text rather than HTML. */
private val RawTextTags = setOf("pre", "textarea", "script", "style")

/** Tags that show nothing, together with everything inside them. */
private val HiddenHtmlTags = setOf("script", "style", "template", "head", "title", "meta", "link", "base", "source", "track", "param", "wbr", "col", "colgroup")

/** Elements that lay out as blocks; any other tag flows with the text around it. */
private val BlockHtmlTags = setOf(
  "address", "article", "aside", "blockquote", "caption", "center", "dd", "details", "dialog", "div", "dl", "dt", "fieldset",
  "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hgroup", "hr", "li", "main", "nav",
  "ol", "p", "pre", "section", "summary", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "ul",
)

/** HTML nested deeper than this is read as if it were flat, so odd input can't overflow the stack. */
private const val MaxHtmlDepth = 48

private val HtmlOpenTag = Regex("<($TagName)((?:$Attribute)*)\\s*(/?)>")
private val HtmlCloseTag = Regex("</($TagName)\\s*>")
private val HtmlAttribute = Regex("([A-Za-z_:][A-Za-z0-9_.:-]*)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+)))?")
private val HtmlWhitespace = Regex("[ \\t\\n\\r\\u000C]+")
private val AnyTag = Regex("<[^>]*>")
private val TextAlign = Regex("text-align\\s*:\\s*([a-z]+)", RegexOption.IGNORE_CASE)
private val CodeLanguage = Regex("lang(?:uage)?-([A-Za-z0-9_+#.-]+)")

/** A tag's attributes by lowercase name, values unquoted and with entities decoded. */
internal fun htmlAttributes(text: String): Map<String, String> {
  if (text.isBlank()) return emptyMap()
  val attributes = LinkedHashMap<String, String>()
  HtmlAttribute.findAll(text).forEach { match ->
    val name = match.groupValues[1].lowercase()
    val value = match.groupValues[2].ifEmpty { match.groupValues[3] }.ifEmpty { match.groupValues[4] }
    if (name !in attributes) attributes[name] = decodeEntities(value)
  }
  return attributes
}

/** `&amp;`, `&#169;` and the like, decoded; anything else stays as written. */
internal fun decodeEntities(text: String): String = if ('&' !in text) text else EntityPattern.replace(text) { decodeEntity(it.value) ?: it.value }

/**
 * An HTML element as inlines, given its [children] already as inlines. Used both for HTML inside
 * a paragraph and for the text of HTML blocks. Unknown tags just show what's in them.
 */
internal fun htmlInline(
  name: String,
  attributes: Map<String, String>,
  children: List<MarkdownInline>,
): List<MarkdownInline> {
  fun wrap(make: (List<MarkdownInline>) -> MarkdownInline) = if (children.isEmpty()) emptyList() else listOf(make(children))
  return when (name) {
    "a" -> attributes["href"]?.takeIf { it.isNotBlank() }?.let { listOf(MarkdownInline.Link(it, children)) } ?: children
    "b", "strong" -> wrap { MarkdownInline.Strong(it) }
    "i", "em", "cite", "dfn", "var" -> wrap { MarkdownInline.Emphasis(it) }
    "code", "kbd", "samp", "tt" -> plainText(children).takeIf { it.isNotEmpty() }?.let { listOf(MarkdownInline.Code(it)) }.orEmpty()
    "s", "del", "strike" -> wrap { MarkdownInline.Strikethrough(it) }
    "u", "ins" -> wrap { MarkdownInline.Underline(it) }
    "sub" -> wrap { MarkdownInline.Small(it, MarkdownTextShift.Down) }
    "sup" -> wrap { MarkdownInline.Small(it, MarkdownTextShift.Up) }
    "small" -> wrap { MarkdownInline.Small(it, MarkdownTextShift.None) }
    "img" -> image(attributes)
    "br" -> listOf(MarkdownInline.HardBreak)
    "q" -> listOf(MarkdownInline.Text("“")) + children + MarkdownInline.Text("”")
    "input" -> if (attributes["type"].equals("checkbox", ignoreCase = true)) {
      listOf(MarkdownInline.Text(if ("checked" in attributes) "☑ " else "☐ "))
    } else {
      emptyList()
    }
    in HiddenHtmlTags -> emptyList()
    else -> children
  }
}

private fun image(attributes: Map<String, String>): List<MarkdownInline> {
  val alt = attributes["alt"].orEmpty()
  val source = attributes["src"]?.trim()?.takeIf { it.isNotEmpty() }
    ?: return if (alt.isEmpty()) emptyList() else listOf(MarkdownInline.Text(alt))
  return listOf(MarkdownInline.Image(source, alt, lengthOf(attributes["width"]), lengthOf(attributes["height"])))
}

internal fun lengthOf(value: String?): MarkdownLength? {
  val text = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
  return if (text.endsWith('%')) {
    text.dropLast(1).trim().toFloatOrNull()?.takeIf { it > 0f }?.let { MarkdownLength.Percent(minOf(it, 100f)) }
  } else {
    text.removeSuffix("px").trim().toFloatOrNull()?.takeIf { it > 0f }?.let { MarkdownLength.Pixels(it) }
  }
}

private fun alignmentOf(attributes: Map<String, String>): MarkdownAlignment? {
  val value = attributes["align"] ?: attributes["style"]?.let { TextAlign.find(it)?.groupValues?.get(1) } ?: return null
  return when (value.trim().lowercase()) {
    "center", "middle" -> MarkdownAlignment.Center
    "right", "end" -> MarkdownAlignment.End
    "left", "start", "justify" -> MarkdownAlignment.Start
    else -> null
  }
}

private fun verticalAlignmentOf(attributes: Map<String, String>): MarkdownVerticalAlignment? = when (attributes["valign"]?.trim()?.lowercase()) {
  "top", "baseline" -> MarkdownVerticalAlignment.Top
  "middle", "center" -> MarkdownVerticalAlignment.Middle
  "bottom" -> MarkdownVerticalAlignment.Bottom
  else -> null
}

private sealed interface HtmlToken {
  class Open(val name: String, val attributes: Map<String, String>, val empty: Boolean) : HtmlToken

  class Close(val name: String) : HtmlToken

  /** [raw] text comes from inside `<pre>`, so its spaces and line breaks are kept. */
  class Text(val text: String, val raw: Boolean = false) : HtmlToken
}

private fun tokenizeHtml(html: String): List<HtmlToken> {
  val tokens = mutableListOf<HtmlToken>()
  val text = StringBuilder()
  fun flush() {
    if (text.isEmpty()) return
    tokens += HtmlToken.Text(text.toString())
    text.clear()
  }
  fun after(marker: String, from: Int): Int = html.indexOf(marker, from).let { if (it < 0) html.length else it + marker.length }
  var i = 0
  while (i < html.length) {
    if (html[i] != '<') {
      text.append(html[i])
      i++
      continue
    }
    when {
      html.startsWith("<!--", i) -> {
        i = after("-->", i + 4)
        continue
      }
      html.startsWith("<![CDATA[", i) -> {
        val end = html.indexOf("]]>", i + 9).let { if (it < 0) html.length else it }
        text.append(html, i + 9, end)
        i = minOf(html.length, end + 3)
        continue
      }
      html.startsWith("<?", i) -> {
        i = after("?>", i + 2)
        continue
      }
      html.startsWith("<!", i) -> {
        i = after(">", i + 2)
        continue
      }
    }
    val close = HtmlCloseTag.matchAt(html, i)
    if (close != null) {
      flush()
      tokens += HtmlToken.Close(close.groupValues[1].lowercase())
      i += close.value.length
      continue
    }
    val open = HtmlOpenTag.matchAt(html, i)
    if (open == null) {
      text.append('<')
      i++
      continue
    }
    flush()
    val name = open.groupValues[1].lowercase()
    val attributes = htmlAttributes(open.groupValues[2])
    val empty = open.groupValues[3] == "/" || name in VoidHtmlTags
    i += open.value.length
    if (!empty && name in RawTextTags) {
      val end = Regex("</$name\\s*>", RegexOption.IGNORE_CASE).find(html, i)
      if (name == "pre" || name == "textarea") {
        tokens += HtmlToken.Open(name, attributes, empty = false)
        tokens += HtmlToken.Text(html.substring(i, end?.range?.first ?: html.length), raw = true)
        tokens += HtmlToken.Close(name)
      }
      i = end?.range?.last?.plus(1) ?: html.length
      continue
    }
    tokens += HtmlToken.Open(name, attributes, empty)
  }
  flush()
  return tokens
}

private sealed interface HtmlNode {
  class Text(val text: String, val raw: Boolean) : HtmlNode

  /** Markdown that sat between pieces of HTML, like the heading inside a `<td>`. */
  class Block(val block: MarkdownBlock) : HtmlNode

  class Element(val name: String, val attributes: Map<String, String>) : HtmlNode {
    val children: MutableList<HtmlNode> = mutableListOf()
  }
}

/** Nests HTML tokens and Markdown blocks into elements, closing what HTML closes implicitly. */
private class HtmlTreeBuilder {
  val root = HtmlNode.Element("", emptyMap())
  private val open = mutableListOf(root)

  /** Whether every element opened so far has closed. */
  val balanced: Boolean get() = open.size == 1

  fun block(block: MarkdownBlock) {
    open.last().children += HtmlNode.Block(block)
  }

  fun token(token: HtmlToken) {
    when (token) {
      is HtmlToken.Text -> open.last().children += HtmlNode.Text(token.text, token.raw)
      is HtmlToken.Close -> {
        // Browsers read a stray `</br>` as a break.
        if (token.name == "br") {
          open.last().children += HtmlNode.Element("br", emptyMap())
          return
        }
        val index = open.indexOfLast { it.name == token.name }
        if (index > 0) closeFrom(index)
      }
      is HtmlToken.Open -> {
        closeImplied(token.name)
        val element = HtmlNode.Element(token.name, token.attributes)
        open.last().children += element
        if (!token.empty && open.size < MaxHtmlDepth) open += element
      }
    }
  }

  /** A new cell ends the one before it, a new row the row before it, and so on. */
  private fun closeImplied(name: String) {
    when (name) {
      "td", "th" -> closeWithin(setOf("td", "th"), boundary = setOf("tr", "table"))
      "tr" -> closeWithin(setOf("tr"), boundary = setOf("table"))
      "thead", "tbody", "tfoot" -> closeWithin(setOf("thead", "tbody", "tfoot"), boundary = setOf("table"))
      "li" -> closeWithin(setOf("li"), boundary = setOf("ul", "ol"))
      "dt", "dd" -> closeWithin(setOf("dt", "dd"), boundary = setOf("dl"))
    }
    if (name in BlockHtmlTags && open.last().name == "p") closeFrom(open.lastIndex)
  }

  private fun closeWithin(names: Set<String>, boundary: Set<String>) {
    for (index in open.lastIndex downTo 1) {
      val element = open[index]
      if (element.name in names) {
        closeFrom(index)
        return
      }
      if (element.name in boundary) return
    }
  }

  private fun closeFrom(index: Int) {
    while (open.size > index) open.removeAt(open.lastIndex)
  }
}

/** The document's blocks, with its pieces of HTML nested and turned into blocks of their own. */
internal fun assembleHtml(items: List<MarkdownItem>): List<MarkdownBlock> {
  if (items.none { it is MarkdownItem.Html }) return items.map { (it as MarkdownItem.Block).block }
  val tree = HtmlTreeBuilder()
  items.forEach { item ->
    when (item) {
      is MarkdownItem.Block -> tree.block(item.block)
      is MarkdownItem.Html -> tokenizeHtml(item.text).forEach(tree::token)
    }
  }
  return blocksOf(tree.root.children)
}

/**
 * [items] as runs that close every element they open, like a `<div>` and the Markdown up to its
 * `</div>`, so each run can be drawn on its own. Each run is a range of indices into [items].
 */
internal fun htmlRuns(items: List<MarkdownItem>): List<IntRange> {
  val tree = HtmlTreeBuilder()
  val runs = mutableListOf<IntRange>()
  var start = 0
  items.forEachIndexed { index, item ->
    when (item) {
      is MarkdownItem.Block -> tree.block(item.block)
      is MarkdownItem.Html -> tokenizeHtml(item.text).forEach(tree::token)
    }
    if (tree.balanced) {
      runs += start..index
      start = index + 1
    }
  }
  if (start < items.size) runs += start..items.lastIndex
  return runs
}

/** Whether [node] flows with text, rather than standing as a block of its own. */
private fun flowsInline(node: HtmlNode): Boolean = when (node) {
  is HtmlNode.Text -> true
  is HtmlNode.Block -> false
  is HtmlNode.Element -> node.name !in BlockHtmlTags && node.children.all(::flowsInline)
}

/** Consecutive inline content becomes a paragraph; everything else becomes its own blocks. */
private fun blocksOf(nodes: List<HtmlNode>): List<MarkdownBlock> {
  val blocks = mutableListOf<MarkdownBlock>()
  val run = mutableListOf<HtmlNode>()
  fun flush() {
    if (run.isEmpty()) return
    blocks += paragraph(run, align = null)
    run.clear()
  }
  nodes.forEach { node ->
    if (flowsInline(node)) {
      run += node
    } else {
      flush()
      blocks += when (node) {
        is HtmlNode.Block -> listOf(node.block)
        is HtmlNode.Element -> element(node)
        is HtmlNode.Text -> paragraph(listOf(node), align = null)
      }
    }
  }
  flush()
  return blocks
}

private fun element(element: HtmlNode.Element): List<MarkdownBlock> {
  val align = alignmentOf(element.attributes)
  return when (element.name) {
    "h1", "h2", "h3", "h4", "h5", "h6" -> {
      val content = trimInlines(element.children.flatMap(::inlinesOf))
      if (content.isEmpty()) emptyList() else listOf(MarkdownBlock.Heading(element.name[1].digitToInt(), content, align))
    }
    "p" -> if (element.children.all(::flowsInline)) paragraph(element.children, align) else section(align, blocksOf(element.children))
    "center" -> section(MarkdownAlignment.Center, blocksOf(element.children))
    "table" -> table(element)
    "details" -> details(element)
    "ul", "ol" -> list(element)
    "blockquote" -> listOf(MarkdownBlock.Quote(blocksOf(element.children)))
    "hr" -> listOf(MarkdownBlock.ThematicBreak)
    "pre" -> preformatted(element)
    in HiddenHtmlTags -> emptyList()
    // div, section, figure, a link around Markdown, a cell outside a table: whatever is inside.
    else -> section(align, blocksOf(element.children))
  }
}

private fun section(align: MarkdownAlignment?, blocks: List<MarkdownBlock>): List<MarkdownBlock> = when {
  blocks.isEmpty() -> emptyList()
  align == null -> blocks
  else -> listOf(MarkdownBlock.Section(align, blocks))
}

/** Inline nodes as a paragraph; a run of nothing but `<br>`s leaves that many empty lines. */
private fun paragraph(nodes: List<HtmlNode>, align: MarkdownAlignment?): List<MarkdownBlock> {
  val content = trimInlines(nodes.flatMap(::inlinesOf))
  return when {
    content.isEmpty() -> emptyList()
    content.all { it == MarkdownInline.HardBreak } -> listOf(MarkdownBlock.Spacer(content.size))
    else -> listOf(MarkdownBlock.Paragraph(content, align))
  }
}

private fun inlinesOf(node: HtmlNode): List<MarkdownInline> = when (node) {
  is HtmlNode.Text -> listOf(MarkdownInline.Text(if (node.raw) node.text else decodeEntities(node.text).replace(HtmlWhitespace, " ")))
  // Markdown inside a heading or a summary keeps its text.
  is HtmlNode.Block -> when (val block = node.block) {
    is MarkdownBlock.Paragraph -> block.content
    is MarkdownBlock.Heading -> block.content
    else -> emptyList()
  }
  is HtmlNode.Element -> htmlInline(node.name, node.attributes, node.children.flatMap(::inlinesOf))
}

/**
 * Collapses spaces the way HTML does: none at the start or end, or around a line break, and
 * never two in a row.
 */
private fun trimInlines(inlines: List<MarkdownInline>): List<MarkdownInline> {
  val out = mutableListOf<MarkdownInline>()
  fun trimLast() {
    val last = out.lastOrNull() as? MarkdownInline.Text ?: return
    val trimmed = last.text.trimEnd(' ')
    if (trimmed.isEmpty()) out.removeAt(out.lastIndex) else out[out.lastIndex] = MarkdownInline.Text(trimmed)
  }
  inlines.forEach { inline ->
    when (inline) {
      is MarkdownInline.Text -> {
        var text = inline.text
        val last = out.lastOrNull()
        if (last is MarkdownInline.Text) {
          out.removeAt(out.lastIndex)
          text = last.text + text
        }
        text = text.replace(Spaces, " ")
        val previous = out.lastOrNull()
        if (previous == null || previous == MarkdownInline.HardBreak) text = text.trimStart(' ')
        if (text.isNotEmpty()) out += MarkdownInline.Text(text)
      }
      MarkdownInline.HardBreak -> {
        trimLast()
        out += inline
      }
      else -> out += inline
    }
  }
  trimLast()
  return out
}

private val Spaces = Regex(" {2,}")

private fun table(element: HtmlNode.Element): List<MarkdownBlock> {
  val rows = mutableListOf<List<MarkdownTableCell>>()
  val captions = mutableListOf<MarkdownBlock>()
  fun cell(cell: HtmlNode.Element, row: HtmlNode.Element?) = MarkdownTableCell(
    header = cell.name == "th",
    align = alignmentOf(cell.attributes) ?: row?.let { alignmentOf(it.attributes) },
    verticalAlign = verticalAlignmentOf(cell.attributes) ?: row?.let { verticalAlignmentOf(it.attributes) } ?: MarkdownVerticalAlignment.Middle,
    width = lengthOf(cell.attributes["width"]),
    blocks = blocksOf(cell.children),
  )
  fun collect(parent: HtmlNode.Element) {
    parent.children.filterIsInstance<HtmlNode.Element>().forEach { child ->
      when (child.name) {
        "tr" -> rows += child.children.filterIsInstance<HtmlNode.Element>().filter { it.name == "td" || it.name == "th" }.map { cell(it, child) }
        "thead", "tbody", "tfoot" -> collect(child)
        "td", "th" -> rows += listOf(cell(child, null))
        "caption" -> captions += paragraph(child.children, MarkdownAlignment.Center)
      }
    }
  }
  collect(element)
  val filled = rows.filter { it.isNotEmpty() }
  if (filled.isEmpty()) return blocksOf(element.children)
  return captions + MarkdownBlock.HtmlTable(filled)
}

private fun details(element: HtmlNode.Element): List<MarkdownBlock> {
  val summary = element.children.firstOrNull { it is HtmlNode.Element && it.name == "summary" } as? HtmlNode.Element
  return listOf(
    MarkdownBlock.Details(
      summary = summary?.let { trimInlines(it.children.flatMap(::inlinesOf)) }.orEmpty(),
      open = "open" in element.attributes,
      blocks = blocksOf(element.children.filter { it !== summary }),
    ),
  )
}

private fun list(element: HtmlNode.Element): List<MarkdownBlock> {
  val items = element.children.filterIsInstance<HtmlNode.Element>().filter { it.name == "li" }
  if (items.isEmpty()) return blocksOf(element.children)
  return listOf(
    MarkdownBlock.ListBlock(
      ordered = element.name == "ol",
      start = element.attributes["start"]?.trim()?.toIntOrNull() ?: 1,
      loose = false,
      items = items.map { MarkdownListItem(checked = null, blocks = blocksOf(it.children)) },
    ),
  )
}

/** `<pre>`, as a code block: tags inside are dropped, a `language-*` class names the language. */
private fun preformatted(element: HtmlNode.Element): List<MarkdownBlock> {
  val raw = element.children.joinToString("") { if (it is HtmlNode.Text) it.text else "" }
  val language = element.attributes["lang"] ?: CodeLanguage.find(raw.substringBefore('>').plus(element.attributes["class"].orEmpty()))?.groupValues?.get(1)
  val text = decodeEntities(raw.replace(AnyTag, "")).removePrefix("\r").removePrefix("\n").trimEnd()
  return if (text.isEmpty()) emptyList() else listOf(MarkdownBlock.Code(language.orEmpty(), text))
}
