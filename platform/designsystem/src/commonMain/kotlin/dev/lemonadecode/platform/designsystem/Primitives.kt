package dev.lemonadecode.platform.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.Text

private val RingWidth = 2.dp

/** A filled status dot, optionally ringed in the surface colour so it reads on top of a tile. */
@Composable
public fun StatusDot(
  color: Color,
  modifier: Modifier = Modifier,
  size: Dp = 8.dp,
  ring: Color? = null,
) {
  val ringed = if (ring != null) {
    Modifier
      .size(size + RingWidth * 2)
      .background(color = ring, shape = CircleShape)
      .padding(RingWidth)
  } else {
    Modifier.size(size)
  }
  Box(modifier = modifier.then(ringed).background(color = color, shape = CircleShape))
}

/** A dot followed by a status word, the pattern every Lemonade Code status uses (never colour alone). */
@Composable
public fun DotLabel(
  text: String,
  dotColor: Color,
  textColor: Color,
  modifier: Modifier = Modifier,
  dotSize: Dp = 8.dp,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing100),
    modifier = modifier,
  ) {
    StatusDot(color = dotColor, size = dotSize)
    LemonadeUi.Text(
      text = text,
      textStyle = LemonadeTheme.typography.bodyXSmallMedium,
      color = textColor,
      maxLines = 1,
    )
  }
}

/** Keyboard shortcut hint drawn inside a button, e.g. ⌘↵. */
@Composable
public fun KeyHint(
  keys: String,
  color: Color,
  borderColor: Color,
  modifier: Modifier = Modifier,
) {
  LemonadeUi.Text(
    text = keys,
    textStyle = LemonadeTheme.typography.bodyXSmallSemiBold,
    color = color,
    maxLines = 1,
    modifier = modifier
      .border(
        width = LemonadeTheme.borderWidths.base.border25,
        color = borderColor,
        shape = LemonadeTheme.shapes.radius150,
      )
      .padding(horizontal = LemonadeTheme.spaces.spacing100),
  )
}

/** Thin rule for toolbars; Lemonade's dividers only come in the low-contrast border colour. */
@Composable
public fun Hairline(
  width: Dp,
  height: Dp,
  modifier: Modifier = Modifier,
  color: Color = LemonadeTheme.colors.border.borderNeutralMedium,
) {
  Box(modifier = modifier.size(width = width, height = height).background(color))
}

/** 1px rule along the top edge, drawn under the content. */
public fun Modifier.topHairline(color: Color, width: Dp = 1.dp): Modifier = drawBehind {
  drawRect(color = color, size = Size(size.width, width.toPx()))
}

/** 1px rule along the bottom edge, drawn under the content. */
public fun Modifier.bottomHairline(color: Color, width: Dp = 1.dp): Modifier = drawBehind {
  val stroke = width.toPx()
  drawRect(color = color, topLeft = Offset(0f, size.height - stroke), size = Size(size.width, stroke))
}

/** Dashed rounded outline, used for sub-session tiles. */
public fun Modifier.dashedBorder(
  width: Dp,
  color: Color,
  cornerRadius: Dp,
  dash: Dp = 4.dp,
  gap: Dp = 3.dp,
): Modifier = drawBehind {
  val stroke = width.toPx()
  val inset = stroke / 2f
  drawRoundRect(
    color = color,
    topLeft = Offset(inset, inset),
    size = Size(size.width - stroke, size.height - stroke),
    cornerRadius = CornerRadius((cornerRadius.toPx() - inset).coerceAtLeast(0f)),
    style = Stroke(
      width = stroke,
      pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash.toPx(), gap.toPx())),
    ),
  )
}

/** Width of one character cell in [style], for laying out monospace columns in `ch` units. */
@Composable
public fun rememberCharWidth(style: TextStyle): Dp {
  val measurer = rememberTextMeasurer()
  val density = LocalDensity.current
  return remember(style, density) {
    with(density) { (measurer.measure(text = "0".repeat(100), style = style, softWrap = false).size.width / 100f).toDp() }
  }
}
