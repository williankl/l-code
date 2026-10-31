package dev.loupe.workspace.ui.preview

/** The first [rows] of a delimited file, as read (rows can differ in length), and how many it has in all. */
internal class DelimitedText(
  val rows: List<List<String>>,
  val totalRows: Int,
)

/**
 * Reads CSV as RFC 4180 has it: quoted fields hold separators, line breaks and `""` for a quote,
 * and lines end in CRLF or LF. TSV goes through the same rules with a tab. Being lenient:
 * blank lines are skipped, spaces before an opening quote are dropped, text after a closing
 * quote is kept, and a quote that never closes is just a character.
 */
internal object DelimitedTextParser {
  fun parse(text: String, separator: Char, keepRows: Int = Int.MAX_VALUE): DelimitedText {
    val rows = mutableListOf<List<String>>()
    var total = 0
    var row = mutableListOf<String>()
    val field = StringBuilder()
    // Whether the current line has anything in it: a character, a separator or a quoted field.
    var rowStarted = false
    var i = 0
    var literalQuoteAt = -1

    fun endRow() {
      if (rowStarted) {
        row += field.toString()
        if (total < keepRows) rows += row
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
          rowStarted = true
          i = close + 1
        }
        c == separator -> {
          row += field.toString()
          field.clear()
          rowStarted = true
          i++
        }
        c == '\n' || c == '\r' -> {
          endRow()
          i += if (c == '\r' && text.getOrNull(i + 1) == '\n') 2 else 1
        }
        else -> {
          field.append(c)
          rowStarted = true
          i++
        }
      }
    }
    endRow()
    return DelimitedText(rows, total)
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
