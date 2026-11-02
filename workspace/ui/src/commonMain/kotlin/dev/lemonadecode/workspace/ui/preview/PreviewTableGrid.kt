package dev.lemonadecode.workspace.ui.preview

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.teya.lemonade.LemonadeTheme

private val MinColumnWidth = 40.dp
private val GridLine = 1.dp

/**
 * Cells in rows and columns, each column as wide as its widest cell up to [maxColumnWidth] and
 * each row as tall as its tallest. The first [headerRows] rows are the header. Draws its own grid
 * lines, header tint and zebra rows, at its natural width: put it in a horizontal scroll. Not
 * lazy, so callers cap how many cells they pass.
 *
 * With [columnWeights] the table fills the width it's given instead, each column taking its
 * share, the way an HTML table with `width` attributes does.
 */
@Composable
internal fun PreviewTableGrid(
  rows: Int,
  columns: Int,
  cellPadding: PaddingValues,
  modifier: Modifier = Modifier,
  maxColumnWidth: Dp = 320.dp,
  columnWeights: List<Float>? = null,
  headerRows: Int = 1,
  alignment: (row: Int, column: Int) -> Alignment.Horizontal = { _, _ -> Alignment.Start },
  verticalAlignment: (row: Int, column: Int) -> Alignment.Vertical = { _, _ -> Alignment.Top },
  cell: @Composable (row: Int, column: Int) -> Unit,
) {
  if (rows == 0 || columns == 0) return
  val colors = LemonadeTheme.colors
  val line = colors.border.borderNeutralLow
  val header = colors.background.bgElevated
  // Scales the token's own alpha: in the dark theme it is already a faint white.
  val stripe = header.copy(alpha = header.alpha * 0.45f)
  val shape = LemonadeTheme.shapes.radius200

  val rowBackgrounds = @Composable {
    repeat(rows) { row ->
      val fill = when {
        row < headerRows -> header
        (row - headerRows) % 2 == 1 -> stripe
        else -> Color.Transparent
      }
      val last = row == rows - 1
      // Each row also draws the line under it, in the gap the layout leaves between rows.
      Box(
        modifier = Modifier.drawBehind {
          val lineHeight = if (last) 0f else GridLine.toPx()
          drawRect(fill, size = Size(size.width, size.height - lineHeight))
          if (!last) drawRect(line, topLeft = Offset(0f, size.height - lineHeight), size = Size(size.width, lineHeight))
        },
      )
    }
  }
  val columnLines = @Composable {
    repeat(columns - 1) { Box(modifier = Modifier.drawBehind { drawRect(line) }) }
  }
  val cells = @Composable {
    repeat(rows) { row ->
      repeat(columns) { column ->
        Box(modifier = Modifier.padding(cellPadding)) { cell(row, column) }
      }
    }
  }

  Layout(
    contents = listOf(rowBackgrounds, columnLines, cells),
    modifier = modifier.clip(shape).border(GridLine, line, shape),
  ) { (backgroundMeasurables, lineMeasurables, cellMeasurables), constraints ->
    val gap = GridLine.roundToPx().coerceAtLeast(1)
    val shares = columnWeights?.let { weights ->
      val available = (if (constraints.hasBoundedWidth) constraints.maxWidth else FallbackFillWidth.roundToPx()) - gap * (columns - 1)
      fillWidths(available.coerceAtLeast(columns), List(columns) { weights.getOrElse(it) { 1f } })
    }
    val cellConstraints = Constraints(maxWidth = maxColumnWidth.roundToPx())
    val placeables = cellMeasurables.mapIndexed { index, measurable ->
      measurable.measure(shares?.let { Constraints.fixedWidth(it[index % columns]) } ?: cellConstraints)
    }
    val widths = shares ?: IntArray(columns) { MinColumnWidth.roundToPx() }
    val heights = IntArray(rows)
    placeables.forEachIndexed { index, placeable ->
      val row = index / columns
      val column = index % columns
      if (shares == null) widths[column] = maxOf(widths[column], placeable.width)
      heights[row] = maxOf(heights[row], placeable.height)
    }
    val width = widths.sum() + gap * (columns - 1)
    val height = heights.sum() + gap * (rows - 1)
    val backgrounds = backgroundMeasurables.mapIndexed { row, measurable ->
      measurable.measure(Constraints.fixed(width, heights[row] + if (row < rows - 1) gap else 0))
    }
    val lines = lineMeasurables.map { it.measure(Constraints.fixed(gap, height)) }

    layout(width, height) {
      var y = 0
      backgrounds.forEachIndexed { row, placeable ->
        placeable.place(0, y)
        y += heights[row] + gap
      }
      var x = 0
      lines.forEachIndexed { column, placeable ->
        x += widths[column]
        placeable.place(x, 0)
        x += gap
      }
      y = 0
      for (row in 0 until rows) {
        x = 0
        for (column in 0 until columns) {
          val placeable = placeables[row * columns + column]
          val offset = alignment(row, column).align(placeable.width, widths[column], layoutDirection)
          placeable.place(x + offset, y + verticalAlignment(row, column).align(placeable.height, heights[row]))
          x += widths[column] + gap
        }
        y += heights[row] + gap
      }
    }
  }
}

/** Width a filling table takes when it's given no limit, as in a horizontal scroll. */
private val FallbackFillWidth = 720.dp

/** [available] split by [weights], every column at least 1 px, the rounding left to the last. */
private fun fillWidths(available: Int, weights: List<Float>): IntArray {
  val total = weights.sum().takeIf { it > 0f } ?: weights.size.toFloat()
  val widths = IntArray(weights.size) { maxOf(1, (available * (weights[it].takeIf { w -> w > 0f } ?: (total / weights.size)) / total).toInt()) }
  widths[widths.lastIndex] = maxOf(1, available - widths.sum() + widths.last())
  return widths
}
