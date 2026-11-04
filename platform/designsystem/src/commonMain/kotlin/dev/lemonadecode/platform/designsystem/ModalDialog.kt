package dev.lemonadecode.platform.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.core.LemonadeShadow
import com.teya.lemonade.lemonadeShadow

/**
 * A question that must be answered before anything else: a panel styled like [AnchoredPopover],
 * centred over the whole window on the flat scrim. The window behind takes no clicks or keys while
 * it is open. Escape calls [onDismissRequest]; a click on the scrim does nothing, so a stray one
 * can't answer for the user.
 */
@Composable
public fun ModalDialog(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  width: Dp = 560.dp,
  spacing: Dp = LemonadeTheme.spaces.spacing400,
  content: @Composable ColumnScope.() -> Unit,
) {
  Popup(
    popupPositionProvider = WindowOrigin,
    onDismissRequest = onDismissRequest,
    // Escape is handled below, where the focused panel's keys arrive.
    properties = PopupProperties(focusable = true, dismissOnClickOutside = false, dismissOnBackPress = false),
  ) {
    val shape = LemonadeTheme.shapes.radius500
    val focus = remember { FocusRequester() }
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .fillMaxSize()
        .onPreviewKeyEvent { event ->
          val escape = event.key == Key.Escape
          if (escape && event.type == KeyEventType.KeyDown) onDismissRequest()
          escape
        }
        .background(LemonadeCodeTheme.scrim)
        // Whatever the panel leaves, the scrim keeps from the window behind.
        .pointerInput(Unit) {
          awaitPointerEventScope {
            while (true) awaitPointerEvent().changes.forEach { it.consume() }
          }
        }
        .padding(PopoverShadowRoom),
    ) {
      Column(
        verticalArrangement = Arrangement.spacedBy(spacing),
        modifier = Modifier
          .widthIn(max = width)
          .fillMaxWidth()
          .then(modifier)
          .semantics { dialog() }
          // Keys go to the dialog, not to whatever had focus behind it.
          .focusRequester(focus)
          .focusable()
          .lemonadeShadow(shadow = LemonadeShadow.Xlarge, shape = shape)
          .background(color = LemonadeTheme.colors.background.bgDefault, shape = shape)
          .border(
            width = LemonadeTheme.borderWidths.base.border25,
            color = LemonadeTheme.colors.border.borderNeutralMedium,
            shape = shape,
          )
          .padding(LemonadeTheme.spaces.spacing600),
        content = content,
      )
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
  }
}

/**
 * The buttons that answer a [ModalDialog], in the order they read: in one row at the end when they
 * fit, else one under another at full width, the last (the main action) on top.
 */
@Composable
public fun ModalDialogActions(
  modifier: Modifier = Modifier,
  spacing: Dp = LemonadeTheme.spaces.spacing200,
  content: @Composable () -> Unit,
) {
  Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
    val gap = spacing.roundToPx()
    val gaps = gap * (measurables.size - 1).coerceAtLeast(0)
    val width = constraints.maxWidth
    val fits = measurables.sumOf { it.maxIntrinsicWidth(constraints.maxHeight) } + gaps <= width
    if (fits) {
      val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
      val height = placeables.maxOfOrNull { it.height } ?: 0
      layout(width, height) {
        var x = width - placeables.sumOf { it.width } - gaps
        placeables.forEach { placeable ->
          placeable.placeRelative(x, (height - placeable.height) / 2)
          x += placeable.width + gap
        }
      }
    } else {
      val placeables = measurables.reversed().map { it.measure(Constraints.fixedWidth(width)) }
      layout(width, placeables.sumOf { it.height } + gaps) {
        var y = 0
        placeables.forEach { placeable ->
          placeable.placeRelative(0, y)
          y += placeable.height + gap
        }
      }
    }
  }
}

/** Puts the popup at the window's top-left; it fills the window. */
private object WindowOrigin : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset = IntOffset.Zero
}
