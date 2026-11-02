package dev.lemonadecode.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.teya.lemonade.Button
import com.teya.lemonade.CountryFlag
import com.teya.lemonade.Icon
import com.teya.lemonade.IconButton
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.Link
import com.teya.lemonade.SegmentedControl
import com.teya.lemonade.Spinner
import com.teya.lemonade.Switch
import com.teya.lemonade.SymbolContainer
import com.teya.lemonade.Text
import com.teya.lemonade.core.LemonadeAssetSize
import com.teya.lemonade.core.LemonadeButtonSize
import com.teya.lemonade.core.LemonadeButtonType
import com.teya.lemonade.core.LemonadeButtonVariant
import com.teya.lemonade.core.LemonadeCountryFlags
import com.teya.lemonade.core.LemonadeIcons
import com.teya.lemonade.core.LemonadeLinkSize
import com.teya.lemonade.core.LemonadeSegmentedControlSize
import com.teya.lemonade.core.SymbolContainerShape
import com.teya.lemonade.core.SymbolContainerSize
import com.teya.lemonade.core.SymbolContainerVoice
import com.teya.lemonade.core.TabButtonProperties
import dev.lemonadecode.platform.designsystem.AnchoredPopover
import dev.lemonadecode.platform.designsystem.DotLabel
import dev.lemonadecode.platform.designsystem.StatusDot
import dev.lemonadecode.platform.localization.resolve
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.keep_awake_description
import dev.lemonadecode.workspace.strings.keep_awake_title
import dev.lemonadecode.workspace.strings.language_english
import dev.lemonadecode.workspace.strings.language_french
import dev.lemonadecode.workspace.strings.language_german
import dev.lemonadecode.workspace.strings.language_match_system
import dev.lemonadecode.workspace.strings.language_portuguese
import dev.lemonadecode.workspace.strings.language_slovak
import dev.lemonadecode.workspace.strings.language_spanish
import dev.lemonadecode.workspace.strings.plugins_mobile
import dev.lemonadecode.workspace.strings.plugins_mobile_description
import dev.lemonadecode.workspace.strings.plugins_open_devices
import dev.lemonadecode.workspace.strings.plugins_value_mobile
import dev.lemonadecode.workspace.strings.plugins_value_none
import dev.lemonadecode.workspace.strings.settings_appearance
import dev.lemonadecode.workspace.strings.settings_back
import dev.lemonadecode.workspace.strings.settings_language
import dev.lemonadecode.workspace.strings.settings_plugins
import dev.lemonadecode.workspace.strings.settings_title
import dev.lemonadecode.workspace.strings.settings_updates
import dev.lemonadecode.workspace.strings.theme_dark
import dev.lemonadecode.workspace.strings.theme_light
import dev.lemonadecode.workspace.strings.theme_system
import dev.lemonadecode.workspace.strings.update_release_notes
import dev.lemonadecode.workspace.strings.update_restart_note
import dev.lemonadecode.workspace.strings.update_retry
import dev.lemonadecode.workspace.strings.updates_check
import dev.lemonadecode.workspace.strings.updates_check_busy
import dev.lemonadecode.workspace.strings.updates_install
import dev.lemonadecode.workspace.strings.updates_install_busy
import dev.lemonadecode.workspace.strings.updates_restart
import dev.lemonadecode.workspace.strings.updates_whats_new
import dev.lemonadecode.workspace.strings.value_off
import dev.lemonadecode.workspace.strings.value_on
import dev.lemonadecode.workspace.uimodel.LanguageOption
import dev.lemonadecode.workspace.uimodel.SettingsModel
import dev.lemonadecode.workspace.uimodel.SettingsPage
import dev.lemonadecode.workspace.uimodel.ThemeOption
import dev.lemonadecode.workspace.uimodel.UpdatePhase
import dev.lemonadecode.workspace.uimodel.UpdatesModel
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val SettingsWidth = 360.dp
private val RowMinHeight = 48.dp

private val ThemeTabs = listOf(
  ThemeOption.Dark to Res.string.theme_dark,
  ThemeOption.Light to Res.string.theme_light,
  ThemeOption.System to Res.string.theme_system,
)

/**
 * Settings, one step at a time: a short list where each item shows its value, and a page of its
 * own for changing it. Escape goes back to the list, then closes.
 */
@Composable
internal fun SettingsPopover(
  settings: SettingsModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  AnchoredPopover(
    onDismissRequest = { onEvent(WorkspaceEvent.DismissSettings) },
    width = SettingsWidth,
    padding = LemonadeTheme.spaces.spacing300,
    spacing = LemonadeTheme.spaces.spacing300,
    onEscape = { onEvent(WorkspaceEvent.SettingsBack) },
  ) {
    if (settings.page == SettingsPage.List) {
      SettingsList(settings = settings, onEvent = onEvent)
    } else {
      SettingsPageHeader(page = settings.page, onBack = { onEvent(WorkspaceEvent.SettingsBack) })
      Column(
        verticalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing300),
        modifier = Modifier.padding(start = LemonadeTheme.spaces.spacing200, end = LemonadeTheme.spaces.spacing200, bottom = LemonadeTheme.spaces.spacing200),
      ) {
        when (settings.page) {
          SettingsPage.List -> Unit
          SettingsPage.Appearance -> AppearancePage(settings = settings, onEvent = onEvent)
          SettingsPage.Language -> LanguagePage(settings = settings, onEvent = onEvent)
          SettingsPage.KeepAwake -> KeepAwakePage(settings = settings, onEvent = onEvent)
          SettingsPage.Plugins -> PluginsPage(settings = settings, onEvent = onEvent)
          SettingsPage.Updates -> UpdatesPage(updates = settings.updates, onEvent = onEvent)
        }
      }
    }
  }
}

@Composable
private fun ColumnScope.SettingsList(
  settings: SettingsModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  LemonadeUi.Text(
    text = stringResource(Res.string.settings_title),
    textStyle = LemonadeTheme.typography.headingXXSmall,
    modifier = Modifier.padding(start = LemonadeTheme.spaces.spacing200, end = LemonadeTheme.spaces.spacing200, top = LemonadeTheme.spaces.spacing100),
  )
  Column {
    SettingsRow(
      icon = if (settings.theme == ThemeOption.Light) LemonadeIcons.Sun else LemonadeIcons.Moon,
      label = stringResource(Res.string.settings_appearance),
      value = stringResource(ThemeTabs.first { it.first == settings.theme }.second),
      onClick = { onEvent(WorkspaceEvent.OpenSettingsPage(SettingsPage.Appearance)) },
    )
    SettingsRow(
      icon = LemonadeIcons.Globe,
      label = stringResource(Res.string.settings_language),
      value = if (settings.language == LanguageOption.System) stringResource(Res.string.language_match_system) else settings.language.nativeName,
      flag = settings.language.flag,
      onClick = { onEvent(WorkspaceEvent.OpenSettingsPage(SettingsPage.Language)) },
    )
    SettingsRow(
      icon = LemonadeIcons.Lightning,
      label = stringResource(Res.string.keep_awake_title),
      value = stringResource(if (settings.keepAwake) Res.string.value_on else Res.string.value_off),
      onClick = { onEvent(WorkspaceEvent.OpenSettingsPage(SettingsPage.KeepAwake)) },
    )
    SettingsRow(
      icon = LemonadeIcons.Puzzle,
      label = stringResource(Res.string.settings_plugins),
      value = stringResource(if (settings.mobile.enabled) Res.string.plugins_value_mobile else Res.string.plugins_value_none),
      onClick = { onEvent(WorkspaceEvent.OpenSettingsPage(SettingsPage.Plugins)) },
    )
    SettingsRow(
      icon = LemonadeIcons.Download,
      label = stringResource(Res.string.settings_updates),
      value = settings.updates.summary.resolve(),
      attention = settings.updates.needsAttention,
      onClick = { onEvent(WorkspaceEvent.OpenSettingsPage(SettingsPage.Updates)) },
    )
  }
}

/** One item of the settings list: icon, name, current value and a chevron to its page. */
@Composable
private fun SettingsRow(
  icon: LemonadeIcons,
  label: String,
  value: String,
  onClick: () -> Unit,
  flag: LemonadeCountryFlags? = null,
  attention: Boolean = false,
) {
  val colors = LemonadeTheme.colors
  val spaces = LemonadeTheme.spaces
  HoverRow(onClick = onClick, role = Role.Button, minHeight = RowMinHeight) {
    LemonadeUi.Icon(icon = icon, contentDescription = null, size = LemonadeAssetSize.Medium, tint = colors.content.contentSecondary)
    // Some languages need two lines for "Keep awake while sessions work".
    LemonadeUi.Text(
      text = label,
      textStyle = LemonadeTheme.typography.bodySmallMedium,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spaces.spacing100)) {
      if (attention) StatusDot(color = colors.background.bgInfo)
      flag?.let { LemonadeUi.CountryFlag(flag = it, contentDescription = "", size = LemonadeAssetSize.Small) }
      LemonadeUi.Text(
        text = value,
        textStyle = LemonadeTheme.typography.bodySmallRegular,
        color = if (attention) colors.content.contentInfo else colors.content.contentSecondary,
        maxLines = 1,
      )
    }
    LemonadeUi.Icon(icon = LemonadeIcons.ChevronRight, contentDescription = null, size = LemonadeAssetSize.Small, tint = colors.content.contentTertiary)
  }
}

@Composable
private fun SettingsPageHeader(
  page: SettingsPage,
  onBack: () -> Unit,
) {
  val title = when (page) {
    SettingsPage.List, SettingsPage.Appearance -> Res.string.settings_appearance
    SettingsPage.Language -> Res.string.settings_language
    SettingsPage.KeepAwake -> Res.string.keep_awake_title
    SettingsPage.Plugins -> Res.string.settings_plugins
    SettingsPage.Updates -> Res.string.settings_updates
  }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing200)) {
    LemonadeUi.IconButton(
      icon = LemonadeIcons.ArrowLeft,
      contentDescription = stringResource(Res.string.settings_back),
      onClick = onBack,
      variant = LemonadeButtonVariant.Neutral,
      type = LemonadeButtonType.Ghost,
      size = LemonadeButtonSize.XSmall,
    )
    LemonadeUi.Text(text = stringResource(title), textStyle = LemonadeTheme.typography.headingXXSmall)
  }
}

@Composable
private fun AppearancePage(
  settings: SettingsModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  LemonadeUi.SegmentedControl(
    properties = ThemeTabs.map { (_, label) -> TabButtonProperties.label(stringResource(label)) },
    selectedTab = ThemeTabs.indexOfFirst { (theme, _) -> theme == settings.theme },
    onTabSelected = { index -> onEvent(WorkspaceEvent.SetTheme(ThemeTabs[index].first)) },
    size = LemonadeSegmentedControlSize.Medium,
    modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun LanguagePage(
  settings: SettingsModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val colors = LemonadeTheme.colors
  Column {
    LanguageOption.entries.forEach { option ->
      val selected = option == settings.language
      HoverRow(
        onClick = { onEvent(WorkspaceEvent.SetLanguage(option)) },
        role = Role.RadioButton,
        minHeight = RowMinHeight,
        modifier = Modifier.semantics { this.selected = selected },
      ) {
        val flag = option.flag
        if (flag != null) {
          LemonadeUi.CountryFlag(flag = flag, contentDescription = "", size = LemonadeAssetSize.Large)
        } else {
          LemonadeUi.Icon(icon = LemonadeIcons.Globe, contentDescription = null, size = LemonadeAssetSize.Large, tint = colors.content.contentSecondary)
        }
        Column(modifier = Modifier.weight(1f)) {
          LemonadeUi.Text(
            text = if (option == LanguageOption.System) stringResource(Res.string.language_match_system) else option.nativeName,
            textStyle = LemonadeTheme.typography.bodySmallMedium,
          )
          val sub = when (option) {
            LanguageOption.System -> settings.systemLanguage.nativeName
            LanguageOption.English -> null
            else -> stringResource(option.nameInThisLanguage)
          }
          sub?.let {
            LemonadeUi.Text(text = it, textStyle = LemonadeTheme.typography.bodyXSmallRegular, color = colors.content.contentSecondary)
          }
        }
        if (selected) {
          LemonadeUi.Icon(icon = LemonadeIcons.CircleCheck, contentDescription = null, size = LemonadeAssetSize.Small, tint = colors.content.contentBrand)
        }
      }
    }
  }
}

@Composable
private fun KeepAwakePage(
  settings: SettingsModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val colors = LemonadeTheme.colors
  Row(
    horizontalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing300),
    modifier = Modifier.toggleable(value = settings.keepAwake, role = Role.Switch, onValueChange = { onEvent(WorkspaceEvent.SetKeepAwake(it)) }),
  ) {
    LemonadeUi.Text(
      text = stringResource(Res.string.keep_awake_description),
      textStyle = LemonadeTheme.typography.bodySmallRegular,
      color = colors.content.contentSecondary,
      modifier = Modifier.weight(1f),
    )
    LemonadeUi.Switch(
      checked = settings.keepAwake,
      onCheckedChange = { onEvent(WorkspaceEvent.SetKeepAwake(it)) },
      modifier = Modifier.padding(top = 2.dp),
    )
  }
  val active = settings.keepAwakeStatus.active
  DotLabel(
    text = settings.keepAwakeStatus.text.resolve(),
    dotColor = if (active) colors.background.bgBrand else colors.background.bgNeutral,
    textColor = if (active) colors.content.contentBrand else colors.content.contentSecondary,
  )
}

@Composable
private fun PluginsPage(
  settings: SettingsModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val colors = LemonadeTheme.colors
  val mobile = settings.mobile
  Row(
    horizontalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing300),
    modifier = Modifier.toggleable(value = mobile.enabled, role = Role.Switch, onValueChange = { onEvent(WorkspaceEvent.SetMobilePlugin(it)) }),
  ) {
    LemonadeUi.SymbolContainer(
      icon = LemonadeIcons.Smartphone,
      contentDescription = "",
      voice = SymbolContainerVoice.Neutral,
      size = SymbolContainerSize.Small,
      shape = SymbolContainerShape.Rounded,
    )
    Column(verticalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing50), modifier = Modifier.weight(1f)) {
      LemonadeUi.Text(text = stringResource(Res.string.plugins_mobile), textStyle = LemonadeTheme.typography.bodySmallSemiBold)
      LemonadeUi.Text(
        text = stringResource(Res.string.plugins_mobile_description),
        textStyle = LemonadeTheme.typography.bodyXSmallRegular,
        color = colors.content.contentSecondary,
      )
    }
    LemonadeUi.Switch(
      checked = mobile.enabled,
      onCheckedChange = { onEvent(WorkspaceEvent.SetMobilePlugin(it)) },
      modifier = Modifier.padding(top = 2.dp),
    )
  }
  if (mobile.enabled) {
    mobile.status?.let { status ->
      DotLabel(text = status.resolve(), dotColor = colors.background.bgPositive, textColor = colors.content.contentPositive)
    }
    LemonadeUi.Button(
      label = stringResource(Res.string.plugins_open_devices),
      onClick = { onEvent(WorkspaceEvent.OpenDevices) },
      leadingIcon = LemonadeIcons.Smartphone,
      variant = LemonadeButtonVariant.Neutral,
      type = LemonadeButtonType.Subtle,
      size = LemonadeButtonSize.Small,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun UpdatesPage(
  updates: UpdatesModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val colors = LemonadeTheme.colors
  val spaces = LemonadeTheme.spaces
  val uriHandler = LocalUriHandler.current
  val phase = updates.phase
  Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing50)) {
    LemonadeUi.Text(text = updates.title.resolve(), textStyle = LemonadeTheme.typography.bodySmallSemiBold)
    val busy = phase == UpdatePhase.Checking || phase == UpdatePhase.Downloading || phase == UpdatePhase.Restarting
    val color = when (phase) {
      UpdatePhase.Available, UpdatePhase.Downloaded -> colors.content.contentInfo
      UpdatePhase.Failed -> colors.content.contentCritical
      else -> colors.content.contentSecondary
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spaces.spacing100)) {
      when {
        busy -> LemonadeUi.Spinner(size = LemonadeAssetSize.XSmall, tint = colors.content.contentSecondary)
        phase == UpdatePhase.UpToDate -> StatusDot(color = colors.background.bgPositive)
        phase == UpdatePhase.Available || phase == UpdatePhase.Downloaded -> StatusDot(color = colors.background.bgInfo)
        else -> Unit
      }
      LemonadeUi.Text(text = updates.status.resolve(), textStyle = LemonadeTheme.typography.bodyXSmallMedium, color = color)
    }
  }
  updates.release?.let { release ->
    Column(
      verticalArrangement = Arrangement.spacedBy(spaces.spacing100),
      modifier = Modifier
        .fillMaxWidth()
        .clip(LemonadeTheme.shapes.radius300)
        .background(colors.background.bgSubtle)
        .padding(spaces.spacing300),
    ) {
      LemonadeUi.Text(text = stringResource(Res.string.updates_whats_new, release.version), textStyle = LemonadeTheme.typography.bodyXSmallSemiBold)
      release.notes?.let { notes ->
        LemonadeUi.Text(text = notes, textStyle = LemonadeTheme.typography.bodyXSmallRegular, color = colors.content.contentSecondary)
      }
      LemonadeUi.Text(text = stringResource(Res.string.update_restart_note), textStyle = LemonadeTheme.typography.bodyXSmallRegular, color = colors.content.contentSecondary)
      LemonadeUi.Link(text = stringResource(Res.string.update_release_notes), onClick = { uriHandler.openUri(release.url) }, size = LemonadeLinkSize.Small)
    }
  }
  if (phase == UpdatePhase.Downloading) {
    ProgressBar(fraction = updates.progress)
  }
  if (phase != UpdatePhase.Elsewhere) {
    val (label, event) = when (phase) {
      UpdatePhase.UpToDate -> Res.string.updates_check to WorkspaceEvent.CheckForUpdates
      UpdatePhase.Checking -> Res.string.updates_check_busy to null
      UpdatePhase.Available -> Res.string.updates_install to WorkspaceEvent.InstallUpdate
      UpdatePhase.Downloading -> Res.string.updates_install_busy to null
      UpdatePhase.Downloaded -> Res.string.updates_restart to WorkspaceEvent.RestartToUpdate
      UpdatePhase.Restarting -> Res.string.updates_restart to null
      UpdatePhase.Failed -> Res.string.update_retry to WorkspaceEvent.InstallUpdate
      UpdatePhase.Elsewhere -> Res.string.updates_check to null
    }
    val primary = phase == UpdatePhase.Available || phase == UpdatePhase.Downloaded || phase == UpdatePhase.Failed
    LemonadeUi.Button(
      label = stringResource(label),
      onClick = { event?.let(onEvent) },
      leadingIcon = if (phase == UpdatePhase.Available || phase == UpdatePhase.Downloading || phase == UpdatePhase.Failed) LemonadeIcons.Download else LemonadeIcons.ArrowRotateCw,
      variant = if (primary) LemonadeButtonVariant.Primary else LemonadeButtonVariant.Neutral,
      type = if (primary) LemonadeButtonType.Solid else LemonadeButtonType.Subtle,
      size = LemonadeButtonSize.Small,
      enabled = event != null,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

/** A thin bar filled to [fraction]; empty while the size isn't known. */
@Composable
private fun ProgressBar(fraction: Float?) {
  val colors = LemonadeTheme.colors
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(4.dp)
      .clip(LemonadeTheme.shapes.radiusFull)
      .background(colors.background.bgNeutralSubtle),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth(fraction ?: 0f)
        .height(4.dp)
        .clip(LemonadeTheme.shapes.radiusFull)
        .background(colors.background.bgBrand),
    )
  }
}

/** A list row that lights up under the pointer; the settings list, the language list, the branch picker. */
@Composable
internal fun HoverRow(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  role: Role = Role.Button,
  minHeight: Dp = RowMinHeight,
  selectedBackground: Boolean = false,
  content: @Composable RowScope.() -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val hovered by interactionSource.collectIsHoveredAsState()
  val colors = LemonadeTheme.colors
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing300),
    modifier = modifier
      .fillMaxWidth()
      .heightIn(min = minHeight)
      .clip(LemonadeTheme.shapes.radius300)
      .background(
        when {
          selectedBackground -> colors.background.bgElevatedHigh
          hovered -> colors.background.bgElevated
          else -> Color.Transparent
        },
      )
      .hoverable(interactionSource)
      .clickable(interactionSource = interactionSource, indication = null, role = role, onClick = onClick)
      .padding(horizontal = LemonadeTheme.spaces.spacing200, vertical = LemonadeTheme.spaces.spacing100),
    content = content,
  )
}

/** A language's name in that language, which stays the same whatever language the app is in. */
internal val LanguageOption.nativeName: String
  get() = when (this) {
    LanguageOption.System, LanguageOption.English -> "English"
    LanguageOption.German -> "Deutsch"
    LanguageOption.Spanish -> "Español"
    LanguageOption.French -> "Français"
    LanguageOption.Portuguese -> "Português"
    LanguageOption.Slovak -> "Slovenčina"
  }

private val LanguageOption.nameInThisLanguage: StringResource
  get() = when (this) {
    LanguageOption.System, LanguageOption.English -> Res.string.language_english
    LanguageOption.German -> Res.string.language_german
    LanguageOption.Spanish -> Res.string.language_spanish
    LanguageOption.French -> Res.string.language_french
    LanguageOption.Portuguese -> Res.string.language_portuguese
    LanguageOption.Slovak -> Res.string.language_slovak
  }

private val LanguageOption.flag: LemonadeCountryFlags?
  get() = when (this) {
    LanguageOption.System -> null
    LanguageOption.English -> LemonadeCountryFlags.GBUnitedKingdom
    LanguageOption.German -> LemonadeCountryFlags.DEGermany
    LanguageOption.Spanish -> LemonadeCountryFlags.ESSpain
    LanguageOption.French -> LemonadeCountryFlags.FRFrance
    LanguageOption.Portuguese -> LemonadeCountryFlags.PTPortugal
    LanguageOption.Slovak -> LemonadeCountryFlags.SKSlovakia
  }
