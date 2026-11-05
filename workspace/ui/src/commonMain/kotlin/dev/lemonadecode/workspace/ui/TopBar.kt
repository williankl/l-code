package dev.lemonadecode.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teya.lemonade.Icon
import com.teya.lemonade.IconButton
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.Tag
import com.teya.lemonade.Text
import com.teya.lemonade.core.LemonadeAssetSize
import com.teya.lemonade.core.LemonadeButtonSize
import com.teya.lemonade.core.LemonadeButtonType
import com.teya.lemonade.core.LemonadeButtonVariant
import com.teya.lemonade.core.LemonadeIcons
import com.teya.lemonade.core.TagVoice
import dev.lemonadecode.platform.designsystem.Hairline
import dev.lemonadecode.platform.designsystem.HoverTooltip
import dev.lemonadecode.platform.designsystem.LemonadeCodeMark
import dev.lemonadecode.platform.designsystem.StatusDot
import dev.lemonadecode.platform.designsystem.TooltipPlacement
import dev.lemonadecode.platform.localization.resolve
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.app_name
import dev.lemonadecode.workspace.strings.devices_button
import dev.lemonadecode.workspace.strings.devices_button_hide
import dev.lemonadecode.workspace.strings.devices_button_separate
import dev.lemonadecode.workspace.strings.sessions_collapse
import dev.lemonadecode.workspace.strings.sessions_expand
import dev.lemonadecode.workspace.strings.settings_title
import dev.lemonadecode.workspace.uimodel.DevicesButtonModel
import dev.lemonadecode.workspace.uimodel.SettingsModel
import dev.lemonadecode.workspace.uimodel.TopBarModel
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TopBar(
  topBar: TopBarModel,
  settings: SettingsModel,
  sessionsVisible: Boolean,
  narrowLayout: Boolean,
  showAttentionDot: Boolean,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val spaces = LemonadeTheme.spaces
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spaces.spacing300),
    modifier = modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .padding(start = spaces.spacing300, end = spaces.spacing400, top = spaces.spacing200, bottom = spaces.spacing200),
  ) {
    val toggleLabel = stringResource(if (sessionsVisible) Res.string.sessions_collapse else Res.string.sessions_expand)
    HoverTooltip(text = toggleLabel, placement = TooltipPlacement.BelowStart) {
      Box {
        LemonadeUi.IconButton(
          icon = LemonadeIcons.Board,
          contentDescription = toggleLabel,
          onClick = { onEvent(WorkspaceEvent.ToggleSessionsPanel(narrowLayout)) },
          variant = LemonadeButtonVariant.Neutral,
          type = LemonadeButtonType.Ghost,
          size = LemonadeButtonSize.Small,
        )
        if (showAttentionDot) {
          StatusDot(
            color = LemonadeTheme.colors.background.bgInfo,
            ring = LemonadeTheme.colors.background.bgSubtle,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = 2.dp),
          )
        }
      }
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spaces.spacing200),
    ) {
      LemonadeCodeMark()
      LemonadeUi.Text(
        text = stringResource(Res.string.app_name),
        textStyle = LemonadeTheme.typography.headingXXSmall,
        maxLines = 1,
      )
    }
    Hairline(width = 1.dp, height = 20.dp)
    LemonadeUi.Text(
      text = topBar.path.orEmpty(),
      textStyle = LemonadeTheme.typography.bodySmallRegular,
      color = LemonadeTheme.colors.content.contentSecondary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spaces.spacing200),
    ) {
      topBar.keepAwakeLabel?.let { label ->
        LemonadeUi.Tag(label = label.resolve(), icon = LemonadeIcons.Lightning, voice = TagVoice.Neutral)
      }
      topBar.devices?.let { devices -> DevicesButton(devices = devices, onEvent = onEvent) }
      Box {
        LemonadeUi.IconButton(
          icon = LemonadeIcons.Gear,
          contentDescription = stringResource(Res.string.settings_title),
          onClick = { onEvent(WorkspaceEvent.ToggleSettings) },
          variant = LemonadeButtonVariant.Neutral,
          type = if (settings.open) LemonadeButtonType.Solid else LemonadeButtonType.Ghost,
          size = LemonadeButtonSize.Small,
        )
        if (topBar.updateAttention) {
          StatusDot(
            color = LemonadeTheme.colors.background.bgInfo,
            ring = LemonadeTheme.colors.background.bgSubtle,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = 2.dp),
          )
        }
        SettingsPopover(settings = settings, onEvent = onEvent)
      }
    }
  }
}

/**
 * The phone button: how many devices are connected, and a ↗ mark while the Devices panel lives in
 * its own window, which the button then brings forward.
 */
@Composable
private fun DevicesButton(
  devices: DevicesButtonModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val colors = LemonadeTheme.colors
  val label = stringResource(
    when {
      devices.separate -> Res.string.devices_button_separate
      devices.docked -> Res.string.devices_button_hide
      else -> Res.string.devices_button
    },
  )
  HoverTooltip(text = label, placement = TooltipPlacement.BelowEnd) {
    Box {
      LemonadeUi.IconButton(
        icon = LemonadeIcons.Smartphone,
        contentDescription = label,
        onClick = { onEvent(WorkspaceEvent.ToggleDevices) },
        variant = LemonadeButtonVariant.Neutral,
        type = if (devices.docked) LemonadeButtonType.Solid else LemonadeButtonType.Ghost,
        size = LemonadeButtonSize.Small,
      )
      LemonadeUi.Text(
        text = devices.count.toString(),
        textStyle = LemonadeTheme.typography.bodyXSmallSemiBold,
        fontSize = 10.sp,
        textAlign = TextAlign.Center,
        color = colors.content.contentOnBrandHigh,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .offset(x = 2.dp, y = (-2).dp)
          .border(2.dp, colors.background.bgSubtle, LemonadeTheme.shapes.radiusFull)
          .padding(2.dp)
          .background(colors.background.bgBrand, LemonadeTheme.shapes.radiusFull)
          .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
          .padding(horizontal = 4.dp),
      )
      if (devices.separate) {
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset(x = 2.dp, y = 2.dp)
            .border(2.dp, colors.background.bgSubtle, LemonadeTheme.shapes.radiusFull)
            .padding(2.dp)
            .size(16.dp)
            .background(colors.background.bgElevatedHigh, LemonadeTheme.shapes.radiusFull),
        ) {
          LemonadeUi.Icon(icon = LemonadeIcons.ArrowUpRight, contentDescription = null, size = LemonadeAssetSize.XSmall, tint = colors.content.contentPrimary)
        }
      }
    }
  }
}
