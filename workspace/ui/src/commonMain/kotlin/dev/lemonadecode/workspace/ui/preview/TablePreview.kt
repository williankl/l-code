package dev.lemonadecode.workspace.ui.preview

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.Text
import com.teya.lemonade.textStyle
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.preview_table_more_rows
import org.jetbrains.compose.resources.pluralStringResource

/** Rows drawn at most; wide tables get fewer, so the cell count stays near [CellBudget]. */
private const val MaxRows = 500
private const val MinRows = 20
private const val CellBudget = 10_000
private val MaxColumnWidth = 280.dp

private val LineBreak = Regex("\r\n|\r|\n")
private val NumberLike = Regex("[-+]?[$€£¥]?(?:\\d+|\\d{1,3}(?:,\\d{3})+)?(?:\\.\\d+)?(?:[eE][-+]?\\d+)?%?")

/**
 * A CSV or TSV file as rows of cells; the first row is the header. With [changes], only the
 * header and the rows they touch, numbered by line, and [nothing] when they touch none.
 */
@Composable
internal fun TablePreview(
  bytes: ByteArray,
  extension: String,
  modifier: Modifier = Modifier,
  changes: ChangedLines? = null,
  nothing: @Composable () -> Unit = {},
) {
  val tsv = extension.equals("tsv", ignoreCase = true)
  ParsedTextPreview(bytes = bytes, parse = { tableOf(it, tsv, changes) }, modifier = modifier, key = tsv to changes) { table ->
    if (table.unchanged) nothing() else Table(table)
  }
}

/** A line of the grid under the header: a row of the table, or rows left out between those shown. */
private sealed interface TableLine {
  class Row(val index: Int) : TableLine

  data object Gap : TableLine
}

@Composable
private fun Table(table: TableData) {
  val colors = LemonadeTheme.colors
  val typography = LemonadeTheme.typography
  val spaces = LemonadeTheme.spaces
  val body = typography.bodySmallRegular.textStyle.copy(color = colors.content.contentPrimary)
  val number = body.copy(fontFeatureSettings = "tnum")
  val header = typography.bodySmallSemiBold.textStyle.copy(color = colors.content.contentPrimary)
  val lineNumber = number.copy(color = colors.content.contentSecondary)
  val lineNumbers = table.lineNumbers
  // With line numbers, they take the first column and the table's own columns move along by one.
  val shift = if (lineNumbers != null) 1 else 0
  val grid = remember(table) {
    buildList {
      table.rows.indices.forEach { index ->
        if (index in table.gaps) add(TableLine.Gap)
        add(TableLine.Row(index))
      }
      if (table.rows.size in table.gaps) add(TableLine.Gap)
    }
  }

  Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing200), modifier = Modifier.fillMaxWidth()) {
    Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
      PreviewTableGrid(
        rows = grid.size + 1,
        columns = table.columns + shift,
        cellPadding = PaddingValues(horizontal = spaces.spacing300, vertical = spaces.spacing100),
        maxColumnWidth = MaxColumnWidth,
        alignment = { row, column ->
          val line = if (row == 0) null else grid[row - 1]
          when {
            column < shift -> Alignment.End
            row == 0 -> if (table.isNumeric(0, column - shift)) Alignment.End else Alignment.Start
            line is TableLine.Row && table.isNumeric(line.index + 1, column - shift) -> Alignment.End
            else -> Alignment.Start
          }
        },
      ) { row, column ->
        val line = if (row == 0) null else grid[row - 1]
        when {
          column < shift -> LemonadeUi.Text(
            text = when (line) {
              null -> ""
              TableLine.Gap -> "⋯"
              is TableLine.Row -> lineNumbers?.get(line.index)?.toString().orEmpty()
            },
            textStyle = lineNumber,
            maxLines = 1,
          )
          line == null -> LemonadeUi.Text(text = table.header[column - shift], textStyle = header, maxLines = 1, overflow = TextOverflow.Ellipsis)
          line is TableLine.Row -> LemonadeUi.Text(
            text = table.rows[line.index][column - shift],
            textStyle = if (table.isNumeric(line.index + 1, column - shift)) number else body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          // A gap leaves its cells empty: the ⋯ in the first column says rows were left out.
          else -> Unit
        }
      }
    }
    if (table.hiddenRows > 0) {
      LemonadeUi.Text(
        text = pluralStringResource(Res.plurals.preview_table_more_rows, table.hiddenRows, table.hiddenRows),
        textStyle = typography.bodyXSmallRegular,
        color = colors.content.contentSecondary,
      )
    }
  }
}

/** What the table draws, worked out off the main thread: cells padded, capped and ready to show. */
internal class TableData(
  val header: List<String>,
  val rows: List<List<String>>,
  /** Rows in the file past the ones drawn. */
  val hiddenRows: Int,
  private val numericCells: List<BooleanArray>,
  /** Columns whose filled cells are all numbers; their header is right-aligned too. */
  private val numericColumns: BooleanArray,
  /** When only changed rows show: the line each row starts on, from 1. */
  val lineNumbers: List<Int>? = null,
  /** Rows were left out before each of these rows; the row count itself stands for after the last. */
  val gaps: Set<Int> = emptySet(),
  /** Only changed rows were asked for, and none of the table changed. */
  val unchanged: Boolean = false,
) {
  val columns: Int get() = header.size

  fun isNumeric(row: Int, column: Int): Boolean = if (row == 0) numericColumns[column] else numericCells[row - 1][column]
}

/** The table in [text]; with [changes], just its header and the rows they touch. */
internal fun tableOf(text: String, tsv: Boolean, changes: ChangedLines? = null): TableData {
  var changedRows = 0
  var headerChanged = false
  val parsed = if (changes == null) {
    DelimitedTextParser.parse(text, separatorFor(text, tsv), keepRows = MaxRows + 1)
  } else {
    DelimitedTextParser.parse(text, separatorFor(text, tsv), keepRows = MaxRows + 1) { index, lines ->
      val changed = changes.any(lines)
      if (index == 0) {
        headerChanged = changed
      } else if (changed) {
        changedRows++
      }
      index == 0 || changed
    }
  }
  val columns = parsed.rows.maxOfOrNull { it.size } ?: 0
  val bodyRows = (CellBudget / columns.coerceAtLeast(1)).coerceIn(MinRows, MaxRows)
  fun cells(row: List<String>) = List(columns) { row.getOrElse(it) { "" }.replace(LineBreak, " ↵ ") }
  val header = parsed.rows.firstOrNull()?.let(::cells) ?: List(columns) { "" }
  val rows = parsed.rows.drop(1).take(bodyRows).map(::cells)
  val numericCells = rows.map { row -> BooleanArray(columns) { looksNumeric(row[it]) } }
  val numericColumns = BooleanArray(columns) { column ->
    val filled = rows.filter { it[column].isNotBlank() }
    filled.isNotEmpty() && filled.all { looksNumeric(it[column]) }
  }
  if (changes == null) {
    return TableData(header, rows, (parsed.totalRows - 1 - rows.size).coerceAtLeast(0), numericCells, numericColumns)
  }
  val indices = parsed.indices.drop(1).take(rows.size)
  val hiddenRows = (changedRows - rows.size).coerceAtLeast(0)
  val gaps = buildSet {
    indices.forEachIndexed { position, index -> if (index != (indices.getOrNull(position - 1) ?: 0) + 1) add(position) }
    // Past the cap, the note under the table says how many more changed rows there are.
    if (hiddenRows == 0 && (indices.lastOrNull() ?: 0) < parsed.totalRows - 1) add(rows.size)
  }
  return TableData(
    header = header,
    rows = rows,
    hiddenRows = hiddenRows,
    numericCells = numericCells,
    numericColumns = numericColumns,
    lineNumbers = parsed.lines.drop(1).take(rows.size).map { it.first + 1 },
    gaps = gaps,
    unchanged = rows.isEmpty() && !headerChanged,
  )
}

/** Commas for CSV, unless the first line only has semicolons (or tabs), as some spreadsheets write. */
private fun separatorFor(text: String, tsv: Boolean): Char {
  if (tsv) return '\t'
  val first = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
  return when {
    ',' in first -> ','
    ';' in first -> ';'
    '\t' in first -> '\t'
    else -> ','
  }
}

private fun looksNumeric(cell: String): Boolean {
  val value = cell.trim()
  return value.any { it.isDigit() } && NumberLike.matches(value)
}
