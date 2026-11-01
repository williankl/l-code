package dev.lemonadecode.workspace.ui.preview

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
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

/** A CSV or TSV file as rows of cells; the first row is the header. */
@Composable
internal fun TablePreview(
  bytes: ByteArray,
  extension: String,
  modifier: Modifier = Modifier,
) {
  val tsv = extension.equals("tsv", ignoreCase = true)
  ParsedTextPreview(bytes = bytes, parse = { tableOf(it, tsv) }, modifier = modifier, key = tsv) { table ->
    Table(table)
  }
}

@Composable
private fun Table(table: TableData) {
  val colors = LemonadeTheme.colors
  val typography = LemonadeTheme.typography
  val spaces = LemonadeTheme.spaces
  val body = typography.bodySmallRegular.textStyle.copy(color = colors.content.contentPrimary)
  val number = body.copy(fontFeatureSettings = "tnum")
  val header = typography.bodySmallSemiBold.textStyle.copy(color = colors.content.contentPrimary)

  Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing200), modifier = Modifier.fillMaxWidth()) {
    Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
      PreviewTableGrid(
        rows = table.rows.size + 1,
        columns = table.columns,
        cellPadding = PaddingValues(horizontal = spaces.spacing300, vertical = spaces.spacing100),
        maxColumnWidth = MaxColumnWidth,
        alignment = { row, column -> if (table.isNumeric(row, column)) Alignment.End else Alignment.Start },
      ) { row, column ->
        LemonadeUi.Text(
          text = if (row == 0) table.header[column] else table.rows[row - 1][column],
          textStyle = when {
            row == 0 -> header
            table.isNumeric(row, column) -> number
            else -> body
          },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
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
) {
  val columns: Int get() = header.size

  fun isNumeric(row: Int, column: Int): Boolean = if (row == 0) numericColumns[column] else numericCells[row - 1][column]
}

internal fun tableOf(text: String, tsv: Boolean): TableData {
  val parsed = DelimitedTextParser.parse(text, separatorFor(text, tsv), keepRows = MaxRows + 1)
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
  return TableData(header, rows, (parsed.totalRows - 1 - rows.size).coerceAtLeast(0), numericCells, numericColumns)
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
