package dev.lemonadecode.workspace.ui.preview

/** The rows kept from a delimited file, as read (rows can differ in length), and how many it has in all. */
internal class DelimitedText(
  val rows: List<List<String>>,
  val totalRows: Int,
  /** Each kept row's place among all the rows, from 0. */
  val indices: List<Int> = rows.indices.toList(),
  /** The lines each kept row spans, from 0: more than one when a quoted field holds line breaks. */
  val lines: List<IntRange> = emptyList(),
)

/**
 * Reads CSV as RFC 4180 has it: quoted fields hold separators, line breaks and `""` for a quote,
 * and lines end in CRLF or LF. TSV goes through the same rules with a tab. Being lenient:
 * blank lines are skipped, spaces before an opening quote are dropped, text after a closing
 * quote is kept, and a quote that never closes is just a character.
 */
internal object DelimitedTextParser {
  /**
   * Keeps the rows [keep] picks, by their place among all rows and the lines they span (both
   * from 0), up to [keepRows] of them.
   */
  fun parse(
    text: String,
    separator: Char,
    keepRows: Int = Int.MAX_VALUE,
    keep: (index: Int, lines: IntRange) -> Boolean = { _, _ -> true },
  ): DelimitedText {
    val rows = mutableListOf<List<String>>()
    val indices = mutableListOf<Int>()
    val rowLines = mutableListOf<IntRange>()
    var total = 0
    var row = mutableListOf<String>()
    val field = StringBuilder()
    // Whether the current line has anything in it: a character, a separator or a quoted field.
    var rowStarted = false
    var line = 0
    var rowStart = 0
    var i = 0
    var literalQuoteAt = -1

    fun startRow() {
      if (rowStarted) return
      rowStarted = true
      rowStart = line
    }

    fun endRow() {
      if (rowStarted) {
        row += field.toString()
        if (keep(total, rowStart..line) && rows.size < keepRows) {
          rows += row
          indices += total
          rowLines += rowStart..line
        }
        total++
      }
      row = mutableListOf()
      field.clear()
      rowStarted = false
    }

    while (i < text.length) {
      val c = text[i]
      when {
        c == '"' && i != literalQuoteAt && field.isBlank() -> {
          val close = closingQuote(text, i + 1)
          if (close < 0) {
            // Never closed: read it as a plain character instead.
            literalQuoteAt = i
            continue
          }
          field.clear()
          appendUnquoted(field, text, i + 1, close)
          startRow()
          line += lineBreaks(text, i + 1, close)
          i = close + 1
        }
        c == separator -> {
          row += field.toString()
          field.clear()
          startRow()
          i++
        }
        c == '\n' || c == '\r' -> {
          endRow()
          line++
          i += if (c == '\r' && text.getOrNull(i + 1) == '\n') 2 else 1
        }
        else -> {
          field.append(c)
          startRow()
          i++
        }
      }
    }
    endRow()
    return DelimitedText(rows, total, indices, rowLines)
  }

  /** The line breaks from [start] until [end], a CRLF counting as one. */
  private fun lineBreaks(text: String, start: Int, end: Int): Int {
    var count = 0
    for (i in start until end) {
      val c = text[i]
      if (c == '\n' || (c == '\r' && text.getOrNull(i + 1) != '\n')) count++
    }
    return count
  }

  /** The index of the quote that ends a field opened just before [start], or -1. */
  private fun closingQuote(text: String, start: Int): Int {
    var i = start
    while (i < text.length) {
      if (text[i] == '"') {
        if (text.getOrNull(i + 1) == '"') {
          i += 2
          continue
        }
        return i
      }
      i++
    }
    return -1
  }

  private fun appendUnquoted(out: StringBuilder, text: String, start: Int, end: Int) {
    var i = start
    while (i < end) {
      val c = text[i]
      out.append(c)
      i += if (c == '"' && text.getOrNull(i + 1) == '"') 2 else 1
    }
  }
}
