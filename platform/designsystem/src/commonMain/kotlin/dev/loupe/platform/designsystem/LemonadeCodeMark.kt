package dev.loupe.platform.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Lemonade's yellow-lime 900 and 500. The mark keeps its colours in both themes, like the app icon. */
private val MarkBackground = Color(0xFF28310B)
private val MarkLime = Color(0xFFE1E51A)

/**
 * The app's mark at small sizes: a prompt chevron followed by half a lemon slice, "> ◗", in lime
 * on olive. The small-size cut of the app icon (`apps/desktop/icons`), drawn on a 100-unit grid.
 */
@Composable
public fun LemonadeCodeMark(
  modifier: Modifier = Modifier,
  size: Dp = 28.dp,
) {
  Canvas(modifier = modifier.size(size).clearAndSetSemantics { }) {
    val unit = this.size.width / 100f
    drawRoundRect(color = MarkBackground, cornerRadius = CornerRadius(25f * unit))
    // M48,16 A34,34 0 0 1 48,84 Z: the right half of a circle around (48, 50).
    val slice = Path().apply {
      arcTo(
        rect = Rect(offset = Offset(14f * unit, 16f * unit), size = Size(68f * unit, 68f * unit)),
        startAngleDegrees = -90f,
        sweepAngleDegrees = 180f,
        forceMoveTo = true,
      )
      close()
    }
    drawPath(slice, MarkLime)
    val chevron = Path().apply {
      moveTo(16f * unit, 34f * unit)
      lineTo(32f * unit, 50f * unit)
      lineTo(16f * unit, 66f * unit)
    }
    drawPath(chevron, MarkLime, style = Stroke(width = 11f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
  }
}
