package dev.lemonadecode.workspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.teya.lemonade.Button
import com.teya.lemonade.HorizontalDivider
import com.teya.lemonade.IconButton
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.Link
import com.teya.lemonade.SegmentedControl
import com.teya.lemonade.Spinner
import com.teya.lemonade.Switch
import com.teya.lemonade.Tag
import com.teya.lemonade.Text
import com.teya.lemonade.core.LemonadeAssetSize
import com.teya.lemonade.core.LemonadeButtonSize
import com.teya.lemonade.core.LemonadeButtonType
import com.teya.lemonade.core.LemonadeButtonVariant
import com.teya.lemonade.core.LemonadeIcons
import com.teya.lemonade.core.LemonadeLinkSize
import com.teya.lemonade.core.LemonadeSegmentedControlSize
import com.teya.lemonade.core.TabButtonProperties
import com.teya.lemonade.core.TagVoice
import dev.lemonadecode.platform.designsystem.AnchoredPopover
import dev.lemonadecode.platform.designsystem.DotLabel
import dev.lemonadecode.platform.designsystem.Hairline
import dev.lemonadecode.platform.designsystem.HoverTooltip
import dev.lemonadecode.platform.designsystem.LemonadeCodeMark
import dev.lemonadecode.platform.designsystem.StatusDot
import dev.lemonadecode.platform.designsystem.TooltipPlacement
import dev.lemonadecode.platform.localization.resolve
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.app_name
import dev.lemonadecode.workspace.strings.keep_awake_description
import dev.lemonadecode.workspace.strings.keep_awake_title
import dev.lemonadecode.workspace.strings.sessions_collapse
import dev.lemonadecode.workspace.strings.sessions_expand
import dev.lemonadecode.workspace.strings.settings_appearance
import dev.lemonadecode.workspace.strings.settings_title
import dev.lemonadecode.workspace.strings.theme_dark
import dev.lemonadecode.workspace.strings.theme_light
import dev.lemonadecode.workspace.strings.theme_system
import dev.lemonadecode.workspace.strings.update_action
import dev.lemonadecode.workspace.strings.update_action_busy
import dev.lemonadecode.workspace.strings.update_detail
import dev.lemonadecode.workspace.strings.update_downloading
import dev.lemonadecode.workspace.strings.update_downloading_unknown
import dev.lemonadecode.workspace.strings.update_install
import dev.lemonadecode.workspace.strings.update_release_notes
import dev.lemonadecode.workspace.strings.update_restart_note
import dev.lemonadecode.workspace.strings.update_restarting
import dev.lemonadecode.workspace.strings.update_retry
import dev.lemonadecode.workspace.strings.update_title
import dev.lemonadecode.workspace.uimodel.SettingsModel
import dev.lemonadecode.workspace.uimodel.ThemeOption
import dev.lemonadecode.workspace.uimodel.TopBarModel
import dev.lemonadecode.workspace.uimodel.UpdateModel
import dev.lemonadecode.workspace.uimodel.UpdateStatusModel
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
      topBar.update?.let { update -> UpdateButton(update = update, onEvent = onEvent) }
      Box {
        LemonadeUi.IconButton(
          icon = LemonadeIcons.Gear,
          contentDescription = stringResource(Res.string.settings_title),
          onClick = { onEvent(WorkspaceEvent.ToggleSettings) },
          variant = LemonadeButtonVariant.Neutral,
          type = if (settings.open) LemonadeButtonType.Solid else LemonadeButtonType.Ghost,
          size = LemonadeButtonSize.Small,
        )
        if (settings.open) {
          SettingsPopover(settings = settings, onEvent = onEvent)
        }
      }
    }
  }
}

@Composable
private fun UpdateButton(
  update: UpdateModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val busy = update.status is UpdateStatusModel.Downloading || update.status == UpdateStatusModel.Restarting
  Box {
    LemonadeUi.Button(
      label = stringResource(if (busy) Res.string.update_action_busy else Res.string.update_action),
      onClick = { onEvent(WorkspaceEvent.ToggleUpdate) },
      leadingIcon = LemonadeIcons.Download,
      variant = LemonadeButtonVariant.Primary,
      type = if (update.open) LemonadeButtonType.Solid else LemonadeButtonType.Subtle,
      size = LemonadeButtonSize.Small,
    )
    if (update.open) {
      UpdatePopover(update = update, onEvent = onEvent)
    }
  }
}

@Composable
private fun UpdatePopover(
  update: UpdateModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val spaces = LemonadeTheme.spaces
  val colors = LemonadeTheme.colors
  val uriHandler = LocalUriHandler.current
  val status = update.status
  AnchoredPopover(onDismissRequest = { onEvent(WorkspaceEvent.DismissUpdate) }) {
    LemonadeUi.Text(text = stringResource(Res.string.update_title), textStyle = LemonadeTheme.typography.headingXXSmall)
    Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing100)) {
      LemonadeUi.Text(
        text = stringResource(Res.string.update_detail, update.version, update.currentVersion),
        textStyle = LemonadeTheme.typography.bodySmallRegular,
      )
      LemonadeUi.Text(
        text = stringResource(Res.string.update_restart_note),
        textStyle = LemonadeTheme.typography.bodyXSmallRegular,
        color = colors.content.contentSecondary,
      )
    }
    when (status) {
      is UpdateStatusModel.Downloading, UpdateStatusModel.Restarting -> Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spaces.spacing200),
      ) {
        LemonadeUi.Spinner(size = LemonadeAssetSize.Small, tint = colors.content.contentBrand)
        LemonadeUi.Text(
          text = when {
            status is UpdateStatusModel.Downloading && status.percent != null -> stringResource(Res.string.update_downloading, "${status.percent}%")
            status is UpdateStatusModel.Downloading -> stringResource(Res.string.update_downloading_unknown)
            else -> stringResource(Res.string.update_restarting)
          },
          textStyle = LemonadeTheme.typography.bodySmallRegular,
          color = colors.content.contentSecondary,
        )
      }
      is UpdateStatusModel.Failed -> LemonadeUi.Text(
        text = status.message.resolve(),
        textStyle = LemonadeTheme.typography.bodySmallRegular,
        color = colors.content.contentCritical,
      )
      UpdateStatusModel.Ready -> Unit
    }
    LemonadeUi.HorizontalDivider()
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spaces.spacing300),
    ) {
      LemonadeUi.Link(
        text = stringResource(Res.string.update_release_notes),
        onClick = { uriHandler.openUri(update.releaseUrl) },
        size = LemonadeLinkSize.Small,
      )
      Spacer(modifier = Modifier.weight(1f))
      LemonadeUi.Button(
        label = stringResource(if (status is UpdateStatusModel.Failed) Res.string.update_retry else Res.string.update_install),
        onClick = { onEvent(WorkspaceEvent.InstallUpdate) },
        variant = LemonadeButtonVariant.Primary,
        type = LemonadeButtonType.Solid,
        size = LemonadeButtonSize.Small,
        enabled = status == UpdateStatusModel.Ready || status is UpdateStatusModel.Failed,
      )
    }
  }
}

private val ThemeTabs = listOf(
  ThemeOption.Dark to Res.string.theme_dark,
  ThemeOption.Light to Res.string.theme_light,
  ThemeOption.System to Res.string.theme_system,
)

@Composable
private fun SettingsPopover(
  settings: SettingsModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val spaces = LemonadeTheme.spaces
  val colors = LemonadeTheme.colors
  AnchoredPopover(onDismissRequest = { onEvent(WorkspaceEvent.DismissSettings) }) {
    LemonadeUi.Text(text = stringResource(Res.string.settings_title), textStyle = LemonadeTheme.typography.headingXXSmall)
    Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing200)) {
      LemonadeUi.Text(text = stringResource(Res.string.settings_appearance), textStyle = LemonadeTheme.typography.bodySmallSemiBold)
      LemonadeUi.SegmentedControl(
        properties = ThemeTabs.map { (_, label) -> TabButtonProperties.label(stringResource(label)) },
        selectedTab = ThemeTabs.indexOfFirst { (theme, _) -> theme == settings.theme },
        onTabSelected = { index -> onEvent(WorkspaceEvent.SetTheme(ThemeTabs[index].first)) },
        size = LemonadeSegmentedControlSize.Medium,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    LemonadeUi.HorizontalDivider()
    Row(
      horizontalArrangement = Arrangement.spacedBy(spaces.spacing300),
      modifier = Modifier.toggleable(
        value = settings.keepAwake,
        role = Role.Switch,
        onValueChange = { onEvent(WorkspaceEvent.SetKeepAwake(it)) },
      ),
    ) {
      Column(
        verticalArrangement = Arrangement.spacedBy(spaces.spacing50),
        modifier = Modifier.weight(1f),
      ) {
        LemonadeUi.Text(
          text = stringResource(Res.string.keep_awake_title),
          textStyle = LemonadeTheme.typography.bodySmallSemiBold,
        )
        LemonadeUi.Text(
          text = stringResource(Res.string.keep_awake_description),
          textStyle = LemonadeTheme.typography.bodyXSmallRegular,
          color = colors.content.contentSecondary,
        )
        val active = settings.keepAwakeStatus.active
        DotLabel(
          text = settings.keepAwakeStatus.text.resolve(),
          dotColor = if (active) colors.background.bgBrand else colors.background.bgNeutral,
          textColor = if (active) colors.content.contentBrand else colors.content.contentSecondary,
          modifier = Modifier.padding(top = spaces.spacing100),
        )
      }
      LemonadeUi.Switch(
        checked = settings.keepAwake,
        onCheckedChange = { onEvent(WorkspaceEvent.SetKeepAwake(it)) },
        modifier = Modifier.padding(top = 2.dp),
      )
    }
  }
}
