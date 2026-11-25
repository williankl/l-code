package dev.lemonadecode.platform.terminal

import com.jediterm.terminal.util.CharUtils

/**
 * A boundary between cells in the terminal's whole text. [line] counts from the oldest history
 * line, so it stays on the same text as output scrolls; [column] 0 is before the first cell.
 */
internal data class TextPoint(val line: Int, val column: Int) : Comparable<TextPoint> {
  override fun compareTo(other: TextPoint): Int = compareValuesBy(this, other, TextPoint::line, TextPoint::column)
}

/** One line as the selection sees it: its cells, and whether it soft-wraps into the next line. */
internal data class LineText(val text: String, val wrapped: Boolean)

/** The text between where a selection started ([anchor]) and where it is now ([focus]). */
internal data class TerminalSelection(val anchor: TextPoint, val focus: TextPoint) {
  val start: TextPoint get() = minOf(anchor, focus)
  val end: TextPoint get() = maxOf(anchor, focus)
  val isEmpty: Boolean get() = anchor == focus

  /** The highlighted columns on [line], or null when none are. Lines in the middle are highlighted whole. */
  fun columnsOn(line: Int, width: Int): IntRange? {
    if (isEmpty || line < start.line || line > end.line) return null
    val from = if (line == start.line) start.column else 0
    val to = if (line == end.line) end.column else width
    return if (to > from) from until to else null
  }

  /**
   * The selected text as it would be copied: a newline after each line except soft-wrapped ones,
   * which join the next, and no padding past the end of what a line shows. With [trimIndent] the
   * lines lose the indentation they share, by the rules of Kotlin's `trimIndent`, with the first
   * line measured from the screen's left edge rather than from where the selection starts.
   */
  fun text(trimIndent: Boolean = false, lineAt: (Int) -> LineText?): String {
    val text = buildString {
      if (isEmpty) return@buildString
      for (line in start.line..end.line) {
        val content = lineAt(line) ?: LineText("", wrapped = false)
        val raw = content.text
        val from = (if (line == start.line) start.column else 0).coerceIn(0, raw.length)
        val to = (if (line == end.line) end.column else raw.length).coerceIn(from, raw.length)
        val piece = raw.substring(from, to).cells()
        append(if (raw.substring(to).isBlank()) piece.trimEnd() else piece)
        if (line < end.line && !content.wrapped) append('\n')
      }
    }
    return if (trimIndent) trimmed(text, lineAt) else text
  }

  private fun trimmed(text: String, lineAt: (Int) -> LineText?): String {
    val lines = text.split('\n')
    // Where the first line begins on screen: it counts as indented by that much.
    val offset = start.column.coerceIn(0, lineAt(start.line)?.text?.length ?: 0)
    fun indent(index: Int, line: String) = line.indexOfFirst { !it.isWhitespace() } + if (index == 0) offset else 0
    val shared = lines.withIndex().filter { it.value.isNotBlank() }.minOfOrNull { indent(it.index, it.value) } ?: 0
    return lines.mapIndexedNotNull { index, line ->
      when {
        (index == 0 || index == lines.lastIndex) && line.isBlank() -> null
        index == 0 -> line.drop((shared - offset).coerceAtLeast(0))
        else -> line.drop(shared)
      }
    }.joinToString("\n")
  }

  companion object {
    /** The word around [column]: letters, digits and the characters of paths and URLs. */
    fun word(line: Int, column: Int, text: String): TerminalSelection {
      if (column !in text.indices || !text[column].isWordChar()) return TerminalSelection(TextPoint(line, column), TextPoint(line, column + 1))
      var from = column
      while (from > 0 && text[from - 1].isWordChar()) from--
      var to = column + 1
      while (to < text.length && text[to].isWordChar()) to++
      return TerminalSelection(TextPoint(line, from), TextPoint(line, to))
    }

    fun line(line: Int, width: Int): TerminalSelection = TerminalSelection(TextPoint(line, 0), TextPoint(line, width))
  }
}

private const val WordPunctuation = "_-./~:@%+=#?&\\"

private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this in WordPunctuation || this == CharUtils.DWC

/** Cells to text: the placeholders behind wide characters go, empty cells become spaces. */
private fun String.cells(): String = replace(CharUtils.DWC.toString(), "").replace(CharUtils.NUL_CHAR, ' ')
