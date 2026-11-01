package dev.lemonadecode.workspace.ui.preview

/** A Markdown document as blocks: what [MarkdownParser] reads and [MarkdownPreview] draws. */
internal sealed interface MarkdownBlock {
  data class Heading(val level: Int, val content: List<MarkdownInline>) : MarkdownBlock

  data class Paragraph(val content: List<MarkdownInline>) : MarkdownBlock

  /** A fenced or indented code block; [language] is the first word after the fence, or empty. */
  data class Code(val language: String, val text: String) : MarkdownBlock

  data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock

  /** [start] is the first number of an ordered list. A loose list spaces its items like paragraphs. */
  data class ListBlock(
    val ordered: Boolean,
    val start: Int,
    val loose: Boolean,
    val items: List<MarkdownListItem>,
  ) : MarkdownBlock

  /** Every row has as many cells as [header]. */
  data class Table(
    val alignments: List<MarkdownAlignment>,
    val header: List<List<MarkdownInline>>,
    val rows: List<List<List<MarkdownInline>>>,
  ) : MarkdownBlock

  /** Raw HTML, shown as written. */
  data class Html(val text: String) : MarkdownBlock

  data object ThematicBreak : MarkdownBlock
}

/** [checked] is null for a plain item, true or false for a task list item. */
internal data class MarkdownListItem(val checked: Boolean?, val blocks: List<MarkdownBlock>)

internal enum class MarkdownAlignment { Start, Center, End }

internal sealed interface MarkdownInline {
  data class Text(val text: String) : MarkdownInline

  data class Code(val text: String) : MarkdownInline

  data class Emphasis(val children: List<MarkdownInline>) : MarkdownInline

  data class Strong(val children: List<MarkdownInline>) : MarkdownInline

  data class Strikethrough(val children: List<MarkdownInline>) : MarkdownInline

  data class Link(val destination: String, val children: List<MarkdownInline>) : MarkdownInline

  data class Image(val source: String, val alt: String) : MarkdownInline

  data object SoftBreak : MarkdownInline

  data object HardBreak : MarkdownInline
}

/** The text of [inlines] without formatting, e.g. for an image's alt text. */
internal fun plainText(inlines: List<MarkdownInline>): String = buildString { appendPlain(inlines) }

private fun StringBuilder.appendPlain(inlines: List<MarkdownInline>) {
  inlines.forEach { inline ->
    when (inline) {
      is MarkdownInline.Text -> append(inline.text)
      is MarkdownInline.Code -> append(inline.text)
      is MarkdownInline.Emphasis -> appendPlain(inline.children)
      is MarkdownInline.Strong -> appendPlain(inline.children)
      is MarkdownInline.Strikethrough -> appendPlain(inline.children)
      is MarkdownInline.Link -> appendPlain(inline.children)
      is MarkdownInline.Image -> append(inline.alt)
      MarkdownInline.SoftBreak, MarkdownInline.HardBreak -> append(' ')
    }
  }
}

/**
 * A small CommonMark reader with the GitHub additions READMEs lean on: tables, task lists,
 * strikethrough and bare links. It isn't complete; it aims to draw ordinary documents the way
 * GitHub does and to never fail on odd ones.
 */
internal object MarkdownParser {
  fun parse(text: String): List<MarkdownBlock> {
    val lines = text.replace('\u0000', '\uFFFD').lines().map { expandIndentTabs(it) }
    val frontMatter = frontMatterEnd(lines)
    val body = if (frontMatter > 0) lines.subList(frontMatter + 1, lines.size) else lines
    val definitions = HashMap<String, String>()
    // Links may use definitions further down, so a first pass collects them all.
    BlockReader(definitions) { emptyList() }.read(body, depth = 0)
    val inlines = InlineReader(definitions)
    val blocks = BlockReader(definitions, inlines::read).read(body, depth = 0)
    if (frontMatter <= 0) return blocks
    return listOf(MarkdownBlock.Code("yaml", lines.subList(1, frontMatter).joinToString("\n"))) + blocks
  }
}

/** YAML front matter (`---` … `---` at the very top) is shown as a code block, as GitHub does. */
private fun frontMatterEnd(lines: List<String>): Int {
  if (lines.firstOrNull()?.trimEnd() != "---") return -1
  for (i in 1 until lines.size) {
    val line = lines[i].trimEnd()
    if (line == "---" || line == "...") return i
  }
  return -1
}

/** Containers nested deeper than this are read as plain text, so odd input can't overflow the stack. */
private const val MaxDepth = 32

/** A block read from the lines, or null for one that shows nothing, and the line after it. */
private class Read(val block: MarkdownBlock?, val next: Int)

private class BlockReader(
  private val definitions: MutableMap<String, String>,
  private val inline: (String) -> List<MarkdownInline>,
) {
  fun read(lines: List<String>, depth: Int): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    fun closeParagraph() {
      if (paragraph.isEmpty()) return
      paragraphText(paragraph)?.let { blocks += MarkdownBlock.Paragraph(inline(it)) }
      paragraph.clear()
    }

    var i = 0
    while (i < lines.size) {
      val line = lines[i]
      if (line.isBlank()) {
        closeParagraph()
        i++
        continue
      }
      if (paragraph.isNotEmpty()) {
        val indent = indentOf(line)
        val level = if (indent < 4) setextLevel(line.substring(indent)) else 0
        if (level > 0) {
          val text = paragraphText(paragraph)
          paragraph.clear()
          if (text != null) {
            blocks += MarkdownBlock.Heading(level, inline(text))
            i++
            continue
          }
        }
      }
      val read = readBlock(lines, i, interrupting = paragraph.isNotEmpty(), depth = depth)
      if (read != null) {
        closeParagraph()
        read.block?.let { blocks += it }
        i = read.next
      } else {
        paragraph += line
        i++
      }
    }
    closeParagraph()
    return blocks
  }

  private fun readBlock(lines: List<String>, start: Int, interrupting: Boolean, depth: Int): Read? {
    val line = lines[start]
    val indent = indentOf(line)
    if (indent >= 4) return if (interrupting) null else readIndentedCode(lines, start)
    val rest = line.substring(indent)
    fenceOf(rest)?.let { return readFence(lines, start, indent, it) }
    atxHeading(rest)?.let { (level, text) -> return Read(MarkdownBlock.Heading(level, inline(text)), start + 1) }
    if (isThematicBreak(rest)) return Read(MarkdownBlock.ThematicBreak, start + 1)
    if (depth < MaxDepth && rest.startsWith('>')) return readQuote(lines, start, depth)
    if (depth < MaxDepth) {
      listMarker(line)?.let { marker ->
        if (!interrupting || marker.canInterrupt) return readList(lines, start, marker, depth)
      }
    }
    if (startsHtml(rest, interrupting)) return readHtml(lines, start)
    return readTable(lines, start)
  }

  private fun readIndentedCode(lines: List<String>, start: Int): Read {
    var end = start
    var i = start
    while (i < lines.size && (lines[i].isBlank() || indentOf(lines[i]) >= 4)) {
      if (lines[i].isNotBlank()) end = i + 1
      i++
    }
    val text = lines.subList(start, end).joinToString("\n") { it.drop(4) }
    return Read(MarkdownBlock.Code("", text), end)
  }

  private fun readFence(lines: List<String>, start: Int, indent: Int, fence: Fence): Read {
    val code = mutableListOf<String>()
    var i = start + 1
    while (i < lines.size) {
      val line = lines[i]
      val lineIndent = indentOf(line)
      i++
      if (lineIndent < 4 && closesFence(line.substring(lineIndent), fence)) break
      code += line.drop(minOf(indent, lineIndent))
    }
    val language = fence.info.substringBefore(' ').substringBefore('\t')
    return Read(MarkdownBlock.Code(language, code.joinToString("\n")), i)
  }

  private fun readQuote(lines: List<String>, start: Int, depth: Int): Read {
    val inner = mutableListOf<String>()
    val lazy = LazyTracker()
    var i = start
    while (i < lines.size) {
      val line = lines[i]
      val indent = indentOf(line)
      if (indent < 4 && line.getOrNull(indent) == '>') {
        var content = line.substring(indent + 1)
        if (content.startsWith(' ') || content.startsWith('\t')) content = content.substring(1)
        content = expandIndentTabs(content)
        inner += content
        lazy.add(content)
        i++
      } else if (line.isNotBlank() && lazy.inParagraph && !startsBlock(line)) {
        // A lazy line: it carries on the quoted paragraph without the marker.
        inner += line
        i++
      } else {
        break
      }
    }
    return Read(MarkdownBlock.Quote(read(inner, depth + 1)), i)
  }

  private fun readList(lines: List<String>, start: Int, first: ListMarker, depth: Int): Read {
    val items = mutableListOf<MarkdownListItem>()
    var loose = false
    var marker = first
    var i = start
    while (true) {
      val content = mutableListOf<String>()
      val lazy = LazyTracker()
      val head = if (marker.empty) "" else marker.line.substring(marker.contentIndent)
      content += head
      lazy.add(head)
      i++
      var blanks = 0
      var blankInside = false
      while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) {
          // An item can start with at most one blank line.
          if (marker.empty && content.size == 1 && content[0].isEmpty()) break
          blanks++
          i++
          continue
        }
        val indent = indentOf(line)
        if (indent >= marker.contentIndent) {
          if (blanks > 0) {
            blankInside = true
            repeat(blanks) {
              content += ""
              lazy.add("")
            }
            blanks = 0
          }
          val inner = line.substring(marker.contentIndent)
          content += inner
          lazy.add(inner)
          i++
          continue
        }
        if (blanks > 0 || listMarker(line) != null) break
        if (lazy.inParagraph && !startsBlock(line)) {
          content += line.trimStart()
          i++
          continue
        }
        break
      }
      val task = TaskMarker.find(content[0])
      if (task != null) content[0] = content[0].substring(task.range.last + 1)
      val blocks = read(content, depth + 1)
      if (blankInside && blocks.size > 1) loose = true
      items += MarkdownListItem(checked = task?.let { it.groupValues[1] != " " }, blocks = blocks)

      var next = i
      while (next < lines.size && lines[next].isBlank()) next++
      val following = lines.getOrNull(next)?.let { listMarker(it) } ?: break
      if (!following.sameListAs(marker) || isThematicBreak(lines[next].trimStart())) break
      if (blanks > 0 || next > i) loose = true
      marker = following
      i = next
    }
    return Read(MarkdownBlock.ListBlock(first.ordered, first.number, loose, items), i)
  }

  private fun readHtml(lines: List<String>, start: Int): Read {
    val first = lines[start].trimStart()
    val rawTag = RawHtmlTag.find(first)?.groupValues?.get(1)?.lowercase()
    val end: ((String) -> Boolean)? = when {
      first.startsWith("<!--") -> { line -> "-->" in line }
      first.startsWith("<?") -> { line -> "?>" in line }
      first.startsWith("<![CDATA[") -> { line -> "]]>" in line }
      first.startsWith("<!") -> { line -> ">" in line }
      rawTag != null -> { line -> line.contains("</$rawTag>", ignoreCase = true) }
      else -> null
    }
    val text = mutableListOf<String>()
    var i = start
    while (i < lines.size) {
      val line = lines[i]
      if (end == null && line.isBlank()) break
      text += line
      i++
      if (end != null && end(line)) break
    }
    val html = text.joinToString("\n")
    // Comments aren't content; GitHub doesn't show them either.
    return Read(if (HtmlComment.matches(html.trim())) null else MarkdownBlock.Html(html), i)
  }

  private fun readTable(lines: List<String>, start: Int): Read? {
    val headerLine = lines[start].trim()
    if ('|' !in headerLine || start + 1 >= lines.size) return null
    val delimiter = lines[start + 1]
    if (indentOf(delimiter) >= 4) return null
    val alignments = delimiterRow(delimiter.trim()) ?: return null
    val header = splitTableRow(headerLine)
    if (header.size != alignments.size) return null
    val rows = mutableListOf<List<List<MarkdownInline>>>()
    var i = start + 2
    while (i < lines.size && lines[i].isNotBlank() && !startsBlock(lines[i])) {
      val cells = splitTableRow(lines[i].trim())
      rows += List(header.size) { inline(cells.getOrElse(it) { "" }) }
      i++
    }
    return Read(MarkdownBlock.Table(alignments, header.map(inline), rows), i)
  }

  /** The paragraph's text once any link reference definitions at its start are taken out. */
  private fun paragraphText(lines: List<String>): String? {
    var first = 0
    while (first < lines.size) {
      val match = Definition.matchEntire(lines[first].trim()) ?: break
      val label = normalizeLabel(match.groupValues[1])
      if (label.isNotEmpty() && label !in definitions) {
        definitions[label] = unescape(match.groupValues[2].removeSurrounding("<", ">"))
      }
      first++
    }
    if (first == lines.size) return null
    return lines.subList(first, lines.size).joinToString("\n") { it.trimStart() }.trimEnd()
  }
}

/** Whether [line] starts a block that ends a paragraph, so it can't continue one lazily. */
private fun startsBlock(line: String): Boolean {
  val indent = indentOf(line)
  if (indent >= 4) return false
  val rest = line.substring(indent)
  return fenceOf(rest) != null ||
    atxHeading(rest) != null ||
    isThematicBreak(rest) ||
    rest.startsWith('>') ||
    listMarker(line)?.canInterrupt == true ||
    startsHtml(rest, interrupting = true)
}

/** Follows the lines a container collects, to tell whether the last one is paragraph text. */
private class LazyTracker {
  private var fence: Fence? = null
  var inParagraph: Boolean = false
    private set

  fun add(line: String) {
    val indent = indentOf(line)
    val rest = line.substring(minOf(indent, line.length))
    val open = fence
    if (open != null) {
      if (indent < 4 && closesFence(rest, open)) fence = null
      return
    }
    if (line.isBlank()) {
      inParagraph = false
      return
    }
    // Indented text carries on a paragraph, or else is code.
    if (indent >= 4) return
    val opened = fenceOf(rest)
    if (opened != null) {
      fence = opened
      inParagraph = false
      return
    }
    inParagraph = atxHeading(rest) == null && !isThematicBreak(rest) && !(inParagraph && setextLevel(rest) > 0)
  }
}

private class Fence(val char: Char, val length: Int, val info: String)

private fun fenceOf(rest: String): Fence? {
  val char = rest.firstOrNull() ?: return null
  if (char != '`' && char != '~') return null
  val length = leadingCount(rest, char)
  if (length < 3) return null
  val info = rest.substring(length).trim()
  if (char == '`' && '`' in info) return null
  return Fence(char, length, info)
}

private fun closesFence(rest: String, fence: Fence): Boolean = rest.firstOrNull() == fence.char && leadingCount(rest, fence.char) >= fence.length && rest.trimEnd().all { it == fence.char }

private fun atxHeading(rest: String): Pair<Int, String>? {
  val level = leadingCount(rest, '#')
  if (level !in 1..6) return null
  if (rest.length > level && rest[level] != ' ' && rest[level] != '\t') return null
  var text = rest.substring(level).trim()
  val unclosed = text.trimEnd('#')
  if (unclosed.isEmpty()) {
    text = ""
  } else if (unclosed.last() == ' ' || unclosed.last() == '\t') {
    text = unclosed.trimEnd()
  }
  return level to text
}

private fun setextLevel(rest: String): Int {
  val line = rest.trimEnd()
  return when {
    line.isEmpty() -> 0
    line.all { it == '=' } -> 1
    line.all { it == '-' } -> 2
    else -> 0
  }
}

private fun isThematicBreak(rest: String): Boolean {
  val char = rest.firstOrNull() ?: return false
  if (char != '*' && char != '-' && char != '_') return false
  var count = 0
  for (c in rest) {
    when (c) {
      char -> count++
      ' ', '\t' -> Unit
      else -> return false
    }
  }
  return count >= 3
}

private class ListMarker(
  val ordered: Boolean,
  /** The bullet (`-`, `+`, `*`) or the character after the number (`.`, `)`). */
  val delimiter: Char,
  val number: Int,
  /** The column the item's content starts at; continuation lines are indented this far. */
  val contentIndent: Int,
  val empty: Boolean,
  /** The line, with tabs after the marker expanded. */
  val line: String,
) {
  /** A list can break into a paragraph only with a non-empty item, and an ordered one only from 1. */
  val canInterrupt: Boolean get() = !empty && (!ordered || number == 1)

  fun sameListAs(other: ListMarker): Boolean = ordered == other.ordered && delimiter == other.delimiter
}

private fun listMarker(raw: String): ListMarker? {
  val indent = indentOf(raw)
  if (indent >= 4) return null
  val first = raw.getOrNull(indent) ?: return null
  val ordered: Boolean
  val delimiter: Char
  val number: Int
  val markerEnd: Int
  if (first == '-' || first == '+' || first == '*') {
    ordered = false
    delimiter = first
    number = 0
    markerEnd = indent + 1
  } else {
    var digits = 0
    while (raw.getOrNull(indent + digits)?.isAsciiDigit() == true) digits++
    if (digits !in 1..9) return null
    val after = raw.getOrNull(indent + digits)
    if (after != '.' && after != ')') return null
    ordered = true
    delimiter = after
    number = raw.substring(indent, indent + digits).toInt()
    markerEnd = indent + digits + 1
  }
  val line = raw.substring(0, markerEnd) + expandIndentTabs(raw.substring(markerEnd), column = markerEnd)
  val rest = line.substring(markerEnd)
  if (rest.isNotEmpty() && rest[0] != ' ') return null
  if (rest.isBlank()) return ListMarker(ordered, delimiter, number, markerEnd + 1, empty = true, line = line)
  val spaces = leadingCount(rest, ' ')
  // Five or more spaces: one belongs to the marker and the rest make an indented code block.
  val contentIndent = if (spaces > 4) markerEnd + 1 else markerEnd + spaces
  return ListMarker(ordered, delimiter, number, contentIndent, empty = false, line = line)
}

private val TaskMarker = Regex("^\\[([ xX])](?:[ \\t]+|$)")

private const val TagName = "[A-Za-z][A-Za-z0-9-]*"
private const val Attribute = "\\s+[A-Za-z_:][A-Za-z0-9_.:-]*(?:\\s*=\\s*(?:[^\\s\"'=<>`]+|'[^']*'|\"[^\"]*\"))?"
private val OpenTag = "<$TagName(?:$Attribute)*\\s*/?>"
private val CloseTag = "</$TagName\\s*>"
private val HtmlComment = Regex("<!--[\\s\\S]*?-->")
private val HtmlTagStart = Regex("^</?($TagName)(?=[\\s/>]|$)")
private val CompleteTagLine = Regex("^(?:$OpenTag|$CloseTag)\\s*$")
private val RawHtmlTag = Regex("^<(pre|script|style|textarea)(?=[\\s>]|$)", RegexOption.IGNORE_CASE)
private val HtmlBlockTags = setOf(
  "address", "article", "aside", "base", "basefont", "blockquote", "body", "caption", "center", "col", "colgroup",
  "dd", "details", "dialog", "dir", "div", "dl", "dt", "fieldset", "figcaption", "figure", "footer", "form", "frame",
  "frameset", "h1", "h2", "h3", "h4", "h5", "h6", "head", "header", "hr", "html", "iframe", "legend", "li", "link",
  "main", "menu", "menuitem", "nav", "noframes", "ol", "optgroup", "option", "p", "param", "pre", "script", "section",
  "source", "style", "summary", "table", "tbody", "td", "textarea", "tfoot", "th", "thead", "title", "tr", "track", "ul",
)

private fun startsHtml(rest: String, interrupting: Boolean): Boolean {
  if (!rest.startsWith('<')) return false
  if (rest.startsWith("<!--") || rest.startsWith("<?") || rest.startsWith("<![CDATA[")) return true
  if (rest.length > 2 && rest[1] == '!' && rest[2].isLetter()) return true
  val name = HtmlTagStart.find(rest)?.groupValues?.get(1)?.lowercase() ?: return false
  if (name in HtmlBlockTags) return true
  // Any other tag only on a line of its own, and never in the middle of a paragraph.
  return !interrupting && CompleteTagLine.matches(rest)
}

private fun delimiterRow(line: String): List<MarkdownAlignment>? {
  if ('|' !in line || '-' !in line || line.any { it !in "|:- \t" }) return null
  return splitTableRow(line).map { cell ->
    if (!DelimiterCell.matches(cell)) return null
    val left = cell.startsWith(':')
    val right = cell.endsWith(':')
    when {
      left && right -> MarkdownAlignment.Center
      right -> MarkdownAlignment.End
      else -> MarkdownAlignment.Start
    }
  }
}

private val DelimiterCell = Regex(":?-+:?")

/** Cells of a table row, split at unescaped pipes; `\|` stays in the cell as `|`. */
private fun splitTableRow(row: String): List<String> {
  var line = row
  if (line.startsWith('|')) line = line.substring(1)
  if (line.endsWith('|') && !line.endsWith("\\|")) line = line.dropLast(1)
  val cells = mutableListOf<String>()
  val cell = StringBuilder()
  var i = 0
  while (i < line.length) {
    val c = line[i]
    if (c == '\\' && line.getOrNull(i + 1) == '|') {
      cell.append('|')
      i += 2
      continue
    }
    if (c == '|') {
      cells += cell.toString().trim()
      cell.clear()
    } else {
      cell.append(c)
    }
    i++
  }
  cells += cell.toString().trim()
  return cells
}

private val Definition = Regex(
  "^\\[((?:[^\\\\\\[\\]]|\\\\.)+)]:[ \\t]*(<[^<>]*>|\\S+)" +
    "(?:[ \\t]+(?:\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|\\((?:[^()\\\\]|\\\\.)*\\)))?[ \\t]*$",
)

private val Whitespace = Regex("\\s+")

private fun normalizeLabel(label: String): String = label.trim().replace(Whitespace, " ").lowercase()

private fun indentOf(line: String): Int = leadingCount(line, ' ')

private fun leadingCount(text: String, char: Char): Int {
  var count = 0
  while (count < text.length && text[count] == char) count++
  return count
}

/** Leading tabs become spaces up to the next multiple of four columns, counted from [column]. */
private fun expandIndentTabs(line: String, column: Int = 0): String {
  if (!line.startsWith('\t') && !line.startsWith(' ')) return line
  var end = 0
  while (end < line.length && (line[end] == ' ' || line[end] == '\t')) end++
  if ('\t' !in line.substring(0, end)) return line
  val indent = StringBuilder()
  var col = column
  for (i in 0 until end) {
    val width = if (line[i] == '\t') 4 - col % 4 else 1
    repeat(width) { indent.append(' ') }
    col += width
  }
  return indent.toString() + line.substring(end)
}

private class InlineReader(private val definitions: Map<String, String>) {
  fun read(text: String): List<MarkdownInline> = Scan(text).run()

  private sealed interface Piece {
    class Text(val text: String) : Piece

    /** A run of `*`, `_` or `~` that may open or close emphasis; [count] shrinks as it's used. */
    class Delimiter(
      val char: Char,
      var count: Int,
      val length: Int,
      val canOpen: Boolean,
      val canClose: Boolean,
    ) : Piece

    /** `[` or `![`, waiting for its `]`. */
    class Bracket(val image: Boolean, val labelStart: Int) : Piece {
      var active: Boolean = true
    }

    class Node(val inline: MarkdownInline) : Piece
  }

  private inner class Scan(private val src: String) {
    private val pieces = mutableListOf<Piece>()
    private val brackets = mutableListOf<Int>()
    private val buffer = StringBuilder()
    private var i = 0

    fun run(): List<MarkdownInline> {
      while (i < src.length) step()
      flush()
      processEmphasis(pieces)
      return finish(pieces)
    }

    private fun step() {
      when (val c = src[i]) {
        '\\' -> escape()
        '`' -> codeSpan()
        '*', '_', '~' -> delimiterRun(c)
        '!' -> if (src.getOrNull(i + 1) == '[') openBracket(image = true) else literal()
        '[' -> openBracket(image = false)
        ']' -> closeBracket()
        '<' -> if (!angle()) literal()
        '&' -> entity()
        '\n' -> lineBreak(hard = buffer.endsWith("  "))
        'h', 'H', 'w', 'W' -> if (!bareLink()) literal()
        else -> literal()
      }
    }

    private fun literal() {
      buffer.append(src[i])
      i++
    }

    private fun flush() {
      if (buffer.isEmpty()) return
      pieces += Piece.Text(buffer.toString())
      buffer.clear()
    }

    private fun add(inline: MarkdownInline) {
      flush()
      pieces += Piece.Node(inline)
    }

    private fun escape() {
      val next = src.getOrNull(i + 1)
      when {
        next == '\n' -> {
          i++
          lineBreak(hard = true)
        }
        next != null && next.isAsciiPunctuation() -> {
          buffer.append(next)
          i += 2
        }
        else -> literal()
      }
    }

    /** At a newline: a hard break after two spaces or a backslash, else a soft one. */
    private fun lineBreak(hard: Boolean) {
      while (buffer.isNotEmpty() && buffer.last() == ' ') buffer.setLength(buffer.length - 1)
      add(if (hard) MarkdownInline.HardBreak else MarkdownInline.SoftBreak)
      i++
      while (i < src.length && src[i] == ' ') i++
    }

    private fun codeSpan() {
      val run = runLength(i, '`')
      var k = i + run
      var close = -1
      while (k < src.length) {
        if (src[k] == '`') {
          val length = runLength(k, '`')
          if (length == run) {
            close = k
            break
          }
          k += length
        } else {
          k++
        }
      }
      if (close < 0) {
        buffer.append(src, i, i + run)
        i += run
        return
      }
      var code = src.substring(i + run, close).replace('\n', ' ')
      if (code.length >= 2 && code.first() == ' ' && code.last() == ' ' && code.isNotBlank()) {
        code = code.substring(1, code.length - 1)
      }
      add(MarkdownInline.Code(code))
      i = close + run
    }

    private fun delimiterRun(char: Char) {
      val run = runLength(i, char)
      if (char == '~' && run > 2) {
        buffer.append(src, i, i + run)
        i += run
        return
      }
      val before = if (i == 0) ' ' else src[i - 1]
      val after = src.getOrElse(i + run) { ' ' }
      val leftFlanking = !after.isWhitespace() && (!after.isPunctuation() || before.isWhitespace() || before.isPunctuation())
      val rightFlanking = !before.isWhitespace() && (!before.isPunctuation() || after.isWhitespace() || after.isPunctuation())
      val canOpen = if (char == '_') leftFlanking && (!rightFlanking || before.isPunctuation()) else leftFlanking
      val canClose = if (char == '_') rightFlanking && (!leftFlanking || after.isPunctuation()) else rightFlanking
      flush()
      pieces += Piece.Delimiter(char, run, run, canOpen, canClose)
      i += run
    }

    private fun openBracket(image: Boolean) {
      val width = if (image) 2 else 1
      flush()
      brackets += pieces.size
      pieces += Piece.Bracket(image, labelStart = i + width)
      i += width
    }

    private fun closeBracket() {
      val openerIndex = brackets.lastOrNull()
      val opener = openerIndex?.let { pieces[it] as Piece.Bracket }
      if (openerIndex == null || opener == null || !opener.active) {
        if (openerIndex != null) brackets.removeAt(brackets.lastIndex)
        literal()
        return
      }
      val label = src.substring(opener.labelStart, i)
      var end = i + 1
      var destination: String? = null
      val tail = inlineLinkTail(i + 1)
      if (tail != null) {
        destination = tail.first
        end = tail.second
      } else {
        val reference = referenceLabel(i + 1)
        val key = reference?.first?.takeIf { it.isNotBlank() } ?: label
        definitions[normalizeLabel(key)]?.let {
          destination = it
          end = reference?.second ?: (i + 1)
        }
      }
      brackets.removeAt(brackets.lastIndex)
      val target = destination
      if (target == null) {
        literal()
        return
      }
      flush()
      val inner = pieces.subList(openerIndex + 1, pieces.size)
      processEmphasis(inner)
      val children = finish(inner)
      inner.clear()
      pieces[openerIndex] = Piece.Node(
        if (opener.image) MarkdownInline.Image(target, plainText(children)) else MarkdownInline.Link(target, children),
      )
      // Links can't hold links, so earlier `[` can no longer make one.
      if (!opener.image) brackets.forEach { index -> (pieces[index] as Piece.Bracket).let { if (!it.image) it.active = false } }
      i = end
    }

    /** `(destination "title")` right after `]`: the destination and the index past `)`. */
    private fun inlineLinkTail(start: Int): Pair<String, Int>? {
      if (src.getOrNull(start) != '(') return null
      var k = skipWhitespace(start + 1)
      val destination: String
      if (src.getOrNull(k) == '<') {
        val close = src.indexOf('>', k + 1)
        if (close < 0 || src.substring(k + 1, close).any { it == '\n' || it == '<' }) return null
        destination = src.substring(k + 1, close)
        k = close + 1
      } else {
        val destinationStart = k
        var depth = 0
        while (k < src.length) {
          val c = src[k]
          if (c == '\\' && src.getOrNull(k + 1)?.isAsciiPunctuation() == true) {
            k += 2
            continue
          }
          if (c.isWhitespace() || c.code < 0x20) break
          if (c == '(') depth++
          if (c == ')') {
            if (depth == 0) break
            depth--
          }
          k++
        }
        if (depth != 0) return null
        destination = src.substring(destinationStart, k)
      }
      val beforeTitle = k
      k = skipWhitespace(k)
      val quote = src.getOrNull(k)
      if (k > beforeTitle && (quote == '"' || quote == '\'' || quote == '(')) {
        val close = if (quote == '(') ')' else quote
        var m = k + 1
        while (m < src.length && src[m] != close) {
          if (src[m] == '\\') m++
          m++
        }
        if (m >= src.length) return null
        k = skipWhitespace(m + 1)
      }
      if (src.getOrNull(k) != ')') return null
      return unescape(destination) to k + 1
    }

    /** `[label]` right after `]`: the label and the index past it. */
    private fun referenceLabel(start: Int): Pair<String, Int>? {
      if (src.getOrNull(start) != '[') return null
      var k = start + 1
      while (k < src.length && src[k] != ']') {
        if (src[k] == '[') return null
        if (src[k] == '\\') k++
        k++
      }
      if (k >= src.length) return null
      return src.substring(start + 1, k) to k + 1
    }

    /** An autolink, a `<br>` or an inline tag (kept as text); false when `<` is just a character. */
    private fun angle(): Boolean {
      if (src.startsWith("<!--", i)) {
        val close = src.indexOf("-->", i + 4)
        if (close < 0) return false
        flush()
        i = close + 3
        return true
      }
      val close = src.indexOf('>', i + 1)
      if (close < 0) return false
      val inner = src.substring(i + 1, close)
      when {
        UriAutolink.matches(inner) -> add(MarkdownInline.Link(inner, listOf(MarkdownInline.Text(inner))))
        EmailAutolink.matches(inner) -> add(MarkdownInline.Link("mailto:$inner", listOf(MarkdownInline.Text(inner))))
        BreakTag.matches(inner) -> {
          while (buffer.isNotEmpty() && buffer.last() == ' ') buffer.setLength(buffer.length - 1)
          add(MarkdownInline.HardBreak)
        }
        InlineTag.matches(src.substring(i, close + 1)) -> buffer.append(src, i, close + 1)
        else -> return false
      }
      i = close + 1
      return true
    }

    private fun entity() {
      val match = EntityPattern.matchAt(src, i)
      val decoded = match?.let { decodeEntity(it.value) }
      if (match == null || decoded == null) {
        literal()
        return
      }
      buffer.append(decoded)
      i += match.value.length
    }

    /** GitHub links bare `https://…`, `http://…` and `www.…` addresses. */
    private fun bareLink(): Boolean {
      if (brackets.isNotEmpty()) return false
      val before = if (i == 0) ' ' else src[i - 1]
      if (!before.isWhitespace() && before != '(' && before != '*' && before != '_' && before != '~') return false
      val prefix = when {
        src.startsWith("https://", i, ignoreCase = true) -> 8
        src.startsWith("http://", i, ignoreCase = true) -> 7
        src.startsWith("www.", i, ignoreCase = true) -> 4
        else -> return false
      }
      var end = i
      while (end < src.length && !src[end].isWhitespace() && src[end] != '<') end++
      var url = src.substring(i, end)
      while (url.isNotEmpty()) {
        val last = url.last()
        url = when {
          last in "?!.,:*_~'\"" -> url.dropLast(1)
          last == ')' && url.count { it == ')' } > url.count { it == '(' } -> url.dropLast(1)
          else -> break
        }
      }
      val host = url.drop(prefix).takeWhile { it != '/' && it != '?' && it != '#' }
      if (host.isEmpty() || (prefix == 4 && '.' !in host)) return false
      add(MarkdownInline.Link(if (prefix == 4) "https://$url" else url, listOf(MarkdownInline.Text(url))))
      i += url.length
      return true
    }

    private fun runLength(start: Int, char: Char): Int {
      var end = start
      while (end < src.length && src[end] == char) end++
      return end - start
    }

    private fun skipWhitespace(start: Int): Int {
      var k = start
      while (k < src.length && src[k].isWhitespace()) k++
      return k
    }
  }

  /** CommonMark's "process emphasis": pairs closers with the nearest matching opener, innermost first. */
  private fun processEmphasis(pieces: MutableList<Piece>) {
    var i = 0
    while (i < pieces.size) {
      val closer = pieces[i]
      if (closer !is Piece.Delimiter || !closer.canClose || closer.count == 0) {
        i++
        continue
      }
      var openerIndex = -1
      for (j in i - 1 downTo 0) {
        val opener = pieces[j]
        if (opener !is Piece.Delimiter || opener.char != closer.char || !opener.canOpen || opener.count == 0) continue
        val matches = if (closer.char == '~') {
          opener.count == closer.count
        } else {
          // The "rule of three": a run that can both open and close doesn't pair across a multiple of three.
          val bothWays = opener.canClose || closer.canOpen
          !(bothWays && (opener.length + closer.length) % 3 == 0 && !(opener.length % 3 == 0 && closer.length % 3 == 0))
        }
        if (matches) {
          openerIndex = j
          break
        }
      }
      if (openerIndex < 0) {
        i++
        continue
      }
      val opener = pieces[openerIndex] as Piece.Delimiter
      val used = when {
        closer.char == '~' -> closer.count
        opener.count >= 2 && closer.count >= 2 -> 2
        else -> 1
      }
      val inner = pieces.subList(openerIndex + 1, i)
      val children = finish(inner)
      inner.clear()
      val node = when {
        closer.char == '~' -> MarkdownInline.Strikethrough(children)
        used == 2 -> MarkdownInline.Strong(children)
        else -> MarkdownInline.Emphasis(children)
      }
      pieces.add(openerIndex + 1, Piece.Node(node))
      opener.count -= used
      closer.count -= used
      var closerIndex = openerIndex + 2
      if (opener.count == 0) {
        pieces.removeAt(openerIndex)
        closerIndex--
      }
      if (closer.count == 0) pieces.removeAt(closerIndex)
      i = closerIndex
    }
  }

  /** Turns what's left into inlines: unmatched delimiters and brackets become plain text. */
  private fun finish(pieces: List<Piece>): List<MarkdownInline> {
    val out = mutableListOf<MarkdownInline>()
    fun text(value: String) {
      if (value.isEmpty()) return
      val last = out.lastOrNull()
      if (last is MarkdownInline.Text) out[out.lastIndex] = MarkdownInline.Text(last.text + value) else out += MarkdownInline.Text(value)
    }
    pieces.forEach { piece ->
      when (piece) {
        is Piece.Text -> text(piece.text)
        is Piece.Delimiter -> text(piece.char.toString().repeat(piece.count))
        is Piece.Bracket -> text(if (piece.image) "![" else "[")
        is Piece.Node -> out += piece.inline
      }
    }
    return out
  }
}

private val UriAutolink = Regex("[A-Za-z][A-Za-z0-9+.-]{1,31}:[^\\s<>]*")
private val EmailAutolink = Regex(
  "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?" +
    "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*",
)
private val BreakTag = Regex("br\\s*/?", RegexOption.IGNORE_CASE)
private val InlineTag = Regex("$OpenTag|$CloseTag")
private val EntityPattern = Regex("&(?:#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});")
private val NamedEntities = mapOf(
  "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
  "copy" to "©", "reg" to "®", "trade" to "™", "hellip" to "…", "mdash" to "—", "ndash" to "–",
  "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»",
  "bull" to "•", "middot" to "·", "times" to "×", "divide" to "÷", "deg" to "°", "plusmn" to "±",
  "para" to "¶", "sect" to "§", "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓",
  "harr" to "↔", "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢", "check" to "✓",
)

private fun decodeEntity(entity: String): String? {
  val body = entity.substring(1, entity.length - 1)
  if (!body.startsWith('#')) return NamedEntities[body]
  val hex = body.length > 1 && (body[1] == 'x' || body[1] == 'X')
  val code = (if (hex) body.substring(2).toIntOrNull(16) else body.substring(1).toIntOrNull()) ?: return null
  val codePoint = if (code == 0 || code > 0x10FFFF || code in 0xD800..0xDFFF) 0xFFFD else code
  if (codePoint < 0x10000) return codePoint.toChar().toString()
  val offset = codePoint - 0x10000
  return charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
}

/** Backslash escapes and entities resolved, for link destinations. */
private fun unescape(text: String): String {
  if ('\\' !in text && '&' !in text) return text
  val out = StringBuilder()
  var i = 0
  while (i < text.length) {
    val c = text[i]
    if (c == '\\' && text.getOrNull(i + 1)?.isAsciiPunctuation() == true) {
      out.append(text[i + 1])
      i += 2
      continue
    }
    if (c == '&') {
      val match = EntityPattern.matchAt(text, i)
      val decoded = match?.let { decodeEntity(it.value) }
      if (match != null && decoded != null) {
        out.append(decoded)
        i += match.value.length
        continue
      }
    }
    out.append(c)
    i++
  }
  return out.toString()
}

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

private fun Char.isAsciiPunctuation(): Boolean = this in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

private fun Char.isPunctuation(): Boolean = !isLetterOrDigit() && !isWhitespace()
