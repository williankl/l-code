package dev.lemonadecode.platform.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.core.LemonadeShadow
import com.teya.lemonade.lemonadeShadow

/**
 * Lemonade's segmented control (medium size) with free-form tab content. The library version only
 * takes a label or an icon; Lemonade Code's view switcher needs a status dot, diff stats and a badge
 * inside each tab, so this mirrors its track, thumb and hover tints with a content slot.
 */
@Composable
public fun SlotSegmentedControl(
  tabCount: Int,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
  tab: @Composable RowScope.(index: Int, selected: Boolean) -> Unit,
) {
  val pill = RoundedCornerShape(percent = 50)
  val density = LocalDensity.current
  val offsets = remember { mutableStateMapOf<Int, Dp>() }
  val widths = remember { mutableStateMapOf<Int, Dp>() }
  val measured = offsets.containsKey(selectedIndex) && widths.containsKey(selectedIndex)
  val spec = if (measured) LemonadeCodeAnimation.spec<Dp>(durationMillis = 150) else snap()
  val indicatorOffset by animateDpAsState(offsets[selectedIndex] ?: 0.dp, spec)
  val indicatorWidth by animateDpAsState(widths[selectedIndex] ?: 0.dp, spec)

  Box(
    modifier = modifier
      .height(LemonadeTheme.sizes.size1000)
      .background(color = LemonadeTheme.colors.background.bgElevated, shape = pill)
      .clip(pill)
      .padding(LemonadeTheme.spaces.spacing100),
  ) {
    if (measured) {
      Box(
        modifier = Modifier
          .offset(x = indicatorOffset)
          .width(indicatorWidth)
          .fillMaxHeight()
          .lemonadeShadow(shadow = LemonadeShadow.Xsmall, shape = pill)
          .background(color = LemonadeTheme.colors.background.bgDefault, shape = pill),
      )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      repeat(tabCount) { index ->
        val selected = index == selectedIndex
        val interactionSource = remember { MutableInteractionSource() }
        val hovered by interactionSource.collectIsHoveredAsState()
        val pressed by interactionSource.collectIsPressedAsState()
        val tint by animateColorAsState(
          animationSpec = LemonadeCodeAnimation.spec(durationMillis = LemonadeCodeAnimation.FadeMillis),
          targetValue = when {
            selected -> LemonadeTheme.colors.background.bgDefault.copy(alpha = 0f)
            pressed -> LemonadeTheme.colors.interaction.bgDefaultPressed
            hovered -> LemonadeTheme.colors.interaction.bgDefaultInteractive
            else -> LemonadeTheme.colors.background.bgDefault.copy(alpha = 0f)
          },
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing200, Alignment.CenterHorizontally),
          modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .onPlaced { coordinates ->
              with(density) {
                offsets[index] = coordinates.positionInParent().x.toDp()
                widths[index] = coordinates.size.width.toDp()
              }
            }
            .clip(pill)
            .background(tint)
            .clickable(
              interactionSource = interactionSource,
              indication = null,
              role = Role.Tab,
              onClick = { onSelect(index) },
            )
            .padding(horizontal = LemonadeTheme.spaces.spacing300),
        ) {
          tab(index, selected)
        }
      }
    }
  }
}
