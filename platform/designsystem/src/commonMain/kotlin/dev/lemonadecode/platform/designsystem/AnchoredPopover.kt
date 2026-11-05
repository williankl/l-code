package dev.lemonadecode.platform.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.core.LemonadeShadow
import com.teya.lemonade.lemonadeShadow

internal val PopoverShadowRoom = 24.dp

/**
 * Panel that opens under its anchor, aligned to the anchor's start or end edge. Place it inside the same
 * Box as the button that opens it. Clicking outside calls [onDismissRequest]; so does Escape, unless
 * [onEscape] is given, e.g. to step back a page first. Keep it composed and set [visible] to have it
 * grow out of its anchor as it opens and fade as it closes.
 */
@Composable
public fun AnchoredPopover(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  visible: Boolean = true,
  width: Dp = 344.dp,
  placement: TooltipPlacement = TooltipPlacement.BelowEnd,
  padding: Dp = LemonadeTheme.spaces.spacing400,
  spacing: Dp = LemonadeTheme.spaces.spacing400,
  onEscape: (() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val density = LocalDensity.current
  val gap = with(density) { 8.dp.roundToPx() }
  val inset = with(density) { PopoverShadowRoom.roundToPx() }
  AnimatedPopup(
    visible = visible,
    popupPositionProvider = remember(gap, inset, placement) {
      AnchorPositionProvider(placement = placement, gap = gap, inset = inset)
    },
    onDismissRequest = onDismissRequest,
    dismissOnBackPress = onEscape == null,
    origin = when (placement) {
      TooltipPlacement.BelowStart -> TransformOrigin(0f, 0f)
      TooltipPlacement.BelowEnd -> TransformOrigin(1f, 0f)
      TooltipPlacement.AboveStart -> TransformOrigin(0f, 1f)
      TooltipPlacement.End -> TransformOrigin(0f, 0.5f)
    },
    onPreviewKeyEvent = { event ->
      val escape = onEscape != null && event.key == Key.Escape
      if (escape && event.type == KeyEventType.KeyDown) onEscape()
      escape
    },
  ) {
    val shape = LemonadeTheme.shapes.radius500
    Column(
      verticalArrangement = Arrangement.spacedBy(spacing),
      modifier = Modifier
        .padding(PopoverShadowRoom)
        .then(modifier)
        .width(width)
        .lemonadeShadow(shadow = LemonadeShadow.Xlarge, shape = shape)
        .background(color = LemonadeTheme.colors.background.bgDefault, shape = shape)
        .border(
          width = LemonadeTheme.borderWidths.base.border25,
          color = LemonadeTheme.colors.border.borderNeutralMedium,
          shape = shape,
        )
        .padding(padding),
      content = content,
    )
  }
}

/**
 * A focusable [Popup] that opens with [LemonadeCodeAnimation.popoverIn] while [visible] and stays
 * up for [LemonadeCodeAnimation.popoverOut] after. While it closes it takes neither the keyboard nor
 * clicks, so typing straight after Escape reaches the app.
 */
@Composable
internal fun AnimatedPopup(
  visible: Boolean,
  popupPositionProvider: PopupPositionProvider,
  onDismissRequest: () -> Unit,
  origin: TransformOrigin,
  dismissOnBackPress: Boolean = true,
  onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
  content: @Composable () -> Unit,
) {
  val state = remember { MutableTransitionState(false) }
  state.targetState = visible
  if (!visible && !state.currentState && state.isIdle) return
  Popup(
    popupPositionProvider = popupPositionProvider,
    onDismissRequest = if (visible) onDismissRequest else null,
    properties = PopupProperties(focusable = visible, dismissOnClickOutside = visible, dismissOnBackPress = visible && dismissOnBackPress),
    onPreviewKeyEvent = { event -> visible && onPreviewKeyEvent(event) },
  ) {
    AnimatedVisibility(
      visibleState = state,
      enter = LemonadeCodeAnimation.popoverIn(origin),
      exit = LemonadeCodeAnimation.popoverOut(origin),
    ) {
      content()
    }
  }
}
