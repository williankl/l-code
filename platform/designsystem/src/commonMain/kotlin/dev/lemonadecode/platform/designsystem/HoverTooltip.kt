package dev.lemonadecode.platform.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.Text
import com.teya.lemonade.core.LemonadeShadow
import com.teya.lemonade.lemonadeShadow

public enum class TooltipPlacement { BelowStart, BelowEnd, End }

/**
 * Small inverse label shown while the pointer rests on [content]. Lemonade's TooltipHost is a
 * spotlight/coach-mark overlay, so plain hover labels are drawn here instead.
 */
@Composable
public fun HoverTooltip(
  text: String,
  modifier: Modifier = Modifier,
  placement: TooltipPlacement = TooltipPlacement.BelowStart,
  enabled: Boolean = true,
  content: @Composable () -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val hovered by interactionSource.collectIsHoveredAsState()
  Box(modifier = modifier.hoverable(interactionSource)) {
    content()
    if (hovered && enabled) {
      val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
      Popup(
        popupPositionProvider = remember(placement, gap) { AnchorPositionProvider(placement, gap) },
        properties = PopupProperties(focusable = false),
      ) {
        TooltipBubble(text = text)
      }
    }
  }
}

@Composable
private fun TooltipBubble(text: String) {
  val shape = LemonadeTheme.shapes.radius200
  LemonadeUi.Text(
    text = text,
    textStyle = LemonadeTheme.typography.bodyXSmallMedium,
    color = LemonadeTheme.colors.content.contentPrimaryInverse,
    maxLines = 1,
    modifier = Modifier
      .lemonadeShadow(shadow = LemonadeShadow.Large, shape = shape)
      .background(color = LemonadeTheme.colors.background.bgDefaultInverse, shape = shape)
      .padding(
        horizontal = LemonadeTheme.spaces.spacing200,
        vertical = LemonadeTheme.spaces.spacing100,
      ),
  )
}

/** Places a popup next to its anchor and keeps it inside the window. */
internal class AnchorPositionProvider(
  private val placement: TooltipPlacement,
  private val gap: Int,
  private val inset: Int = 0,
) : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    val raw = when (placement) {
      TooltipPlacement.BelowStart -> IntOffset(anchorBounds.left - inset, anchorBounds.bottom + gap - inset)
      TooltipPlacement.BelowEnd -> IntOffset(
        anchorBounds.right - popupContentSize.width + inset,
        anchorBounds.bottom + gap - inset,
      )
      TooltipPlacement.End -> IntOffset(
        anchorBounds.right + gap - inset,
        anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2,
      )
    }
    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
    val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
    return IntOffset(raw.x.coerceIn(0, maxX), raw.y.coerceIn(0, maxY))
  }
}
