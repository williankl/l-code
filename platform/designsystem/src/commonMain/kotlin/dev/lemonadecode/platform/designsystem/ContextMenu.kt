package dev.lemonadecode.platform.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import com.teya.lemonade.Icon
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.Text
import com.teya.lemonade.core.LemonadeAssetSize
import com.teya.lemonade.core.LemonadeIcons
import com.teya.lemonade.core.LemonadeShadow
import com.teya.lemonade.lemonadeShadow

/**
 * A right-click menu, styled like [AnchoredPopover]. It opens at [offset] from the top-left of the
 * layout it is placed in (where the pointer was) and flips to stay in the window. Clicking outside
 * or pressing Escape calls [onDismissRequest]. Lemonade's list items are sized for touch lists, so
 * the rows are drawn here: start with a [ContextMenuHeader], then [ContextMenuItem]s. Keep it
 * composed and set [visible] to have it grow out of the pointer as it opens and fade as it closes.
 */
@Composable
public fun ContextMenuPopover(
  offset: IntOffset,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  visible: Boolean = true,
  width: Dp = 296.dp,
  content: @Composable ColumnScope.() -> Unit,
) {
  val inset = with(LocalDensity.current) { PopoverShadowRoom.roundToPx() }
  AnimatedPopup(
    visible = visible,
    popupPositionProvider = remember(offset, inset) { PointerPositionProvider(offset, inset) },
    onDismissRequest = onDismissRequest,
    origin = TransformOrigin(0f, 0f),
  ) {
    val shape = LemonadeTheme.shapes.radius400
    Column(
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
        .padding(LemonadeTheme.spaces.spacing200),
      content = content,
    )
  }
}

/**
 * Names what the menu acts on: [title] in bold and [detail], e.g. a folder and branch, in monospace
 * under it, wrapping rather than cut short. Followed by a divider.
 */
@Composable
public fun ContextMenuHeader(
  title: String,
  detail: String?,
) {
  val spaces = LemonadeTheme.spaces
  Column(
    verticalArrangement = Arrangement.spacedBy(2.dp),
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = spaces.spacing300, end = spaces.spacing300, top = spaces.spacing200, bottom = spaces.spacing200),
  ) {
    LemonadeUi.Text(
      text = title,
      textStyle = LemonadeTheme.typography.bodySmallSemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (detail != null) {
      LemonadeUi.Text(
        text = detail,
        textStyle = LemonadeCodeTheme.typography.codeSmall.copy(fontSize = 12.sp, lineHeight = 16.sp, color = LemonadeTheme.colors.content.contentSecondary),
      )
    }
  }
  ContextMenuDivider()
}

/**
 * One row of a [ContextMenuPopover]: an icon, the label and an optional second line under it. A
 * [critical] row, for an action that can't be undone, draws its icon and label in Lemonade's critical
 * content colour.
 */
@Composable
public fun ContextMenuItem(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: LemonadeIcons? = null,
  detail: String? = null,
  enabled: Boolean = true,
  critical: Boolean = false,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val hovered by interactionSource.collectIsHoveredAsState()
  val focused by interactionSource.collectIsFocusedAsState()
  val colors = LemonadeTheme.colors
  val spaces = LemonadeTheme.spaces
  val content = colors.content
  val iconTint = when {
    !enabled -> content.contentTertiary
    critical -> content.contentCritical
    else -> content.contentSecondary
  }
  val labelColor = when {
    !enabled -> content.contentTertiary
    critical -> content.contentCritical
    else -> content.contentPrimary
  }
  Row(
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(spaces.spacing300),
    modifier = modifier
      .fillMaxWidth()
      .clip(LemonadeTheme.shapes.radius300)
      .background(if (enabled && (hovered || focused)) colors.background.bgElevated else Color.Transparent)
      .hoverable(interactionSource, enabled)
      .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
      .padding(horizontal = spaces.spacing300, vertical = spaces.spacing200),
  ) {
    if (icon != null) {
      LemonadeUi.Icon(
        icon = icon,
        contentDescription = null,
        size = LemonadeAssetSize.Small,
        tint = iconTint,
        modifier = Modifier.padding(top = 2.dp),
      )
    }
    Column(modifier = Modifier.weight(1f)) {
      LemonadeUi.Text(
        text = label,
        textStyle = LemonadeTheme.typography.bodySmallMedium,
        color = labelColor,
      )
      if (detail != null) {
        LemonadeUi.Text(
          text = detail,
          textStyle = LemonadeTheme.typography.bodyXSmallRegular,
          color = if (enabled) colors.content.contentSecondary else colors.content.contentTertiary,
        )
      }
    }
  }
}

/** Separates groups of [ContextMenuItem]s with a hairline. */
@Composable
public fun ContextMenuDivider() {
  Box(
    modifier = Modifier
      .padding(horizontal = LemonadeTheme.spaces.spacing200, vertical = LemonadeTheme.spaces.spacing100)
      .fillMaxWidth()
      .height(1.dp)
      .background(LemonadeTheme.colors.border.borderNeutralLow),
  )
}

/**
 * Opens a popup at [offset] inside its anchor, down and to the right of it, flipping up or left
 * where the window runs out. [inset] is the transparent room around the panel for its shadow.
 */
private class PointerPositionProvider(
  private val offset: IntOffset,
  private val inset: Int,
) : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    val x = anchorBounds.left + offset.x
    val y = anchorBounds.top + offset.y
    val left = if (x - inset + popupContentSize.width > windowSize.width) x - popupContentSize.width + inset else x - inset
    val top = if (y - inset + popupContentSize.height > windowSize.height) y - popupContentSize.height + inset else y - inset
    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
    val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
    return IntOffset(left.coerceIn(0, maxX), top.coerceIn(0, maxY))
  }
}
