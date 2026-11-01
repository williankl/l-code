package dev.lemonadecode.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.teya.lemonade.LemonadeTheme
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.sessions_resize
import org.jetbrains.compose.resources.stringResource

/** The left-right resize cursor. */
internal expect val HorizontalResizePointerIcon: PointerIcon

/**
 * The gap between the sessions sidebar and the main panel. Dragging it moves the sidebar's edge
 * through [onResize] as the pointer goes; letting go reports the final width to [onResizeFinished].
 */
@Composable
internal fun SidebarResizeHandle(
  sidebarWidth: Dp,
  minWidth: Dp,
  maxWidth: Dp,
  onResize: (Dp) -> Unit,
  onResizeFinished: (Dp) -> Unit,
  modifier: Modifier = Modifier,
) {
  val density = LocalDensity.current
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val dragged by interaction.collectIsDraggedAsState()
  // Where the pointer has pulled the edge to, limits ignored, so after overshooting a limit the
  // edge only comes back once the pointer does.
  var pulled by remember { mutableStateOf(sidebarWidth) }
  val state = rememberDraggableState { delta ->
    pulled += with(density) { delta.toDp() }
    onResize(pulled.coerceIn(minWidth, maxWidth))
  }
  val label = stringResource(Res.string.sessions_resize)

  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .fillMaxHeight()
      .semantics { contentDescription = label }
      .pointerHoverIcon(HorizontalResizePointerIcon)
      .hoverable(interaction)
      .draggable(
        state = state,
        orientation = Orientation.Horizontal,
        interactionSource = interaction,
        onDragStarted = { pulled = sidebarWidth },
        onDragStopped = { onResizeFinished(pulled.coerceIn(minWidth, maxWidth)) },
      ),
  ) {
    if (hovered || dragged) {
      val colors = LemonadeTheme.colors
      Box(
        modifier = Modifier
          .width(2.dp)
          .fillMaxHeight()
          .background(
            color = if (dragged) colors.content.contentBrand else colors.border.borderNeutralHigh,
            shape = LemonadeTheme.shapes.radiusFull,
          ),
      )
    }
  }
}
