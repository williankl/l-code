package dev.lemonadecode.workspace.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.teya.lemonade.Button
import com.teya.lemonade.Checkbox
import com.teya.lemonade.Chip
import com.teya.lemonade.ExperimentalLemonadeComponent
import com.teya.lemonade.Icon
import com.teya.lemonade.IconButton
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.SearchField
import com.teya.lemonade.SegmentedControl
import com.teya.lemonade.Spinner
import com.teya.lemonade.SymbolContainer
import com.teya.lemonade.Text
import com.teya.lemonade.TextField
import com.teya.lemonade.core.CheckboxStatus
import com.teya.lemonade.core.LemonadeAssetSize
import com.teya.lemonade.core.LemonadeButtonSize
import com.teya.lemonade.core.LemonadeButtonType
import com.teya.lemonade.core.LemonadeButtonVariant
import com.teya.lemonade.core.LemonadeIcons
import com.teya.lemonade.core.LemonadeSegmentedControlSize
import com.teya.lemonade.core.SymbolContainerSize
import com.teya.lemonade.core.SymbolContainerVoice
import com.teya.lemonade.core.TabButtonProperties
import dev.lemonadecode.platform.designsystem.AnchoredPopover
import dev.lemonadecode.platform.designsystem.HoverTooltip
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.platform.designsystem.StatusDot
import dev.lemonadecode.platform.designsystem.TooltipPlacement
import dev.lemonadecode.platform.designsystem.bottomHairline
import dev.lemonadecode.platform.designsystem.dashedBorder
import dev.lemonadecode.platform.designsystem.rememberCharWidth
import dev.lemonadecode.platform.designsystem.topHairline
import dev.lemonadecode.platform.localization.resolve
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.device_tab_logs
import dev.lemonadecode.workspace.strings.device_tab_screen
import dev.lemonadecode.workspace.strings.device_window_close
import dev.lemonadecode.workspace.strings.device_window_open
import dev.lemonadecode.workspace.strings.devices_android
import dev.lemonadecode.workspace.strings.devices_close
import dev.lemonadecode.workspace.strings.devices_dock
import dev.lemonadecode.workspace.strings.devices_ios
import dev.lemonadecode.workspace.strings.devices_layout_split
import dev.lemonadecode.workspace.strings.devices_layout_tabs
import dev.lemonadecode.workspace.strings.devices_none
import dev.lemonadecode.workspace.strings.devices_pair
import dev.lemonadecode.workspace.strings.devices_separate
import dev.lemonadecode.workspace.strings.logs_all
import dev.lemonadecode.workspace.strings.logs_apps_all
import dev.lemonadecode.workspace.strings.logs_apps_all_note
import dev.lemonadecode.workspace.strings.logs_apps_choose
import dev.lemonadecode.workspace.strings.logs_apps_find
import dev.lemonadecode.workspace.strings.logs_apps_front
import dev.lemonadecode.workspace.strings.logs_apps_front_note
import dev.lemonadecode.workspace.strings.logs_apps_no_match
import dev.lemonadecode.workspace.strings.logs_apps_title
import dev.lemonadecode.workspace.strings.logs_clear
import dev.lemonadecode.workspace.strings.logs_copied
import dev.lemonadecode.workspace.strings.logs_copy
import dev.lemonadecode.workspace.strings.logs_errors
import dev.lemonadecode.workspace.strings.logs_filter
import dev.lemonadecode.workspace.strings.logs_filter_placeholder
import dev.lemonadecode.workspace.strings.logs_live
import dev.lemonadecode.workspace.strings.logs_pause
import dev.lemonadecode.workspace.strings.logs_paused
import dev.lemonadecode.workspace.strings.logs_resume
import dev.lemonadecode.workspace.strings.logs_warnings
import dev.lemonadecode.workspace.strings.mirror_back
import dev.lemonadecode.workspace.strings.mirror_home
import dev.lemonadecode.workspace.strings.mirror_keyboard
import dev.lemonadecode.workspace.strings.mirror_loading
import dev.lemonadecode.workspace.strings.mirror_rotate
import dev.lemonadecode.workspace.strings.mirror_screenshot
import dev.lemonadecode.workspace.strings.pair_action
import dev.lemonadecode.workspace.strings.pair_android
import dev.lemonadecode.workspace.strings.pair_android_note
import dev.lemonadecode.workspace.strings.pair_busy
import dev.lemonadecode.workspace.strings.pair_cable
import dev.lemonadecode.workspace.strings.pair_cable_note
import dev.lemonadecode.workspace.strings.pair_cable_text
import dev.lemonadecode.workspace.strings.pair_cable_title
import dev.lemonadecode.workspace.strings.pair_cable_trust
import dev.lemonadecode.workspace.strings.pair_cable_waiting
import dev.lemonadecode.workspace.strings.pair_code
import dev.lemonadecode.workspace.strings.pair_code_address
import dev.lemonadecode.workspace.strings.pair_code_address_hint
import dev.lemonadecode.workspace.strings.pair_code_code
import dev.lemonadecode.workspace.strings.pair_code_code_hint
import dev.lemonadecode.workspace.strings.pair_code_note
import dev.lemonadecode.workspace.strings.pair_code_text
import dev.lemonadecode.workspace.strings.pair_code_title
import dev.lemonadecode.workspace.strings.pair_how
import dev.lemonadecode.workspace.strings.pair_ios
import dev.lemonadecode.workspace.strings.pair_ios_note
import dev.lemonadecode.workspace.strings.pair_nearby
import dev.lemonadecode.workspace.strings.pair_nearby_empty_android
import dev.lemonadecode.workspace.strings.pair_nearby_empty_ios
import dev.lemonadecode.workspace.strings.pair_nearby_found_android
import dev.lemonadecode.workspace.strings.pair_nearby_found_ios
import dev.lemonadecode.workspace.strings.pair_nearby_none
import dev.lemonadecode.workspace.strings.pair_nearby_title
import dev.lemonadecode.workspace.strings.pair_qr
import dev.lemonadecode.workspace.strings.pair_qr_label
import dev.lemonadecode.workspace.strings.pair_qr_note
import dev.lemonadecode.workspace.strings.pair_qr_text
import dev.lemonadecode.workspace.strings.pair_qr_title
import dev.lemonadecode.workspace.strings.pair_qr_waiting
import dev.lemonadecode.workspace.strings.pair_what
import dev.lemonadecode.workspace.strings.paired_another
import dev.lemonadecode.workspace.strings.paired_open
import dev.lemonadecode.workspace.strings.paired_title
import dev.lemonadecode.workspace.strings.settings_back
import dev.lemonadecode.workspace.uimodel.DeviceConnectionModel
import dev.lemonadecode.workspace.uimodel.DeviceGroupModel
import dev.lemonadecode.workspace.uimodel.DeviceKeyModel
import dev.lemonadecode.workspace.uimodel.DeviceLogsModel
import dev.lemonadecode.workspace.uimodel.DeviceRowModel
import dev.lemonadecode.workspace.uimodel.DeviceScreenModel
import dev.lemonadecode.workspace.uimodel.DeviceTab
import dev.lemonadecode.workspace.uimodel.DevicesContentModel
import dev.lemonadecode.workspace.uimodel.DevicesLayoutOption
import dev.lemonadecode.workspace.uimodel.DevicesPanelModel
import dev.lemonadecode.workspace.uimodel.DevicesWindow
import dev.lemonadecode.workspace.uimodel.LogAppModel
import dev.lemonadecode.workspace.uimodel.LogAppsModel
import dev.lemonadecode.workspace.uimodel.LogFilter
import dev.lemonadecode.workspace.uimodel.LogLevelModel
import dev.lemonadecode.workspace.uimodel.LogLineModel
import dev.lemonadecode.workspace.uimodel.MirrorControlsModel
import dev.lemonadecode.workspace.uimodel.MobilePlatformModel
import dev.lemonadecode.workspace.uimodel.PairingMethod
import dev.lemonadecode.workspace.uimodel.QrCodeModel
import dev.lemonadecode.workspace.uimodel.ScreenPictureModel
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Image as SkiaImage

private val ScreenPaneWidth = 320.dp

/** Below this a portrait screen goes above its logs rather than beside them. */
private val SideBySideMinWidth = 600.dp

/** The most of the page a screen above its logs takes, so the logs keep room. */
private const val StackedScreenShare = 0.55f

/** Room around the picture in its pane, for the controls under it and the line under them. */
private val ScreenPaneChrome = 112.dp

/**
 * The Devices panel: connected devices, one device's screen and logs, or a step of pairing. The
 * same panel sits beside the session or fills a window of its own.
 */
@Composable
public fun DevicesPanel(
  panel: DevicesPanelModel,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.background(LemonadeTheme.colors.background.bgDefault)) {
    DevicesHeader(panel = panel, onEvent = onEvent)
    val fill = Modifier.weight(1f).fillMaxWidth()
    when (val content = panel.content) {
      is DevicesContentModel.Devices -> DeviceList(content = content, onEvent = onEvent, modifier = fill)
      is DevicesContentModel.Device -> DevicePage(device = content, onEvent = onEvent, modifier = fill)
      DevicesContentModel.ChoosePlatform -> ChoosePlatform(onEvent = onEvent, modifier = fill)
      is DevicesContentModel.ChooseMethod -> ChooseMethod(content = content, onEvent = onEvent, modifier = fill)
      is DevicesContentModel.ScanQr -> ScanQr(content = content, modifier = fill)
      is DevicesContentModel.EnterCode -> EnterCode(content = content, onEvent = onEvent, modifier = fill)
      is DevicesContentModel.PickNearby -> PickNearby(content = content, onEvent = onEvent, modifier = fill)
      is DevicesContentModel.PlugIn -> PlugIn(content = content, modifier = fill)
      is DevicesContentModel.Paired -> Paired(content = content, onEvent = onEvent, modifier = fill)
    }
  }
}

@Composable
private fun DevicesHeader(
  panel: DevicesPanelModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val spaces = LemonadeTheme.spaces
  val colors = LemonadeTheme.colors
  val device = panel.content as? DevicesContentModel.Device
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spaces.spacing100),
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 60.dp)
      .bottomHairline(colors.border.borderNeutralLow)
      .padding(horizontal = spaces.spacing300, vertical = spaces.spacing200),
  ) {
    if (panel.canGoBack) {
      HeaderButton(LemonadeIcons.ArrowLeft, stringResource(Res.string.settings_back)) { onEvent(WorkspaceEvent.DevicesBack) }
    }
    Column(modifier = Modifier.weight(1f).padding(start = if (panel.canGoBack) 0.dp else spaces.spacing200)) {
      LemonadeUi.Text(text = panel.title.resolve(), textStyle = LemonadeTheme.typography.headingXXSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
      panel.subtitle?.let { subtitle ->
        LemonadeUi.Text(
          text = subtitle.resolve(),
          textStyle = LemonadeTheme.typography.bodyXSmallRegular,
          color = colors.content.contentSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (device != null) {
      if (device.canDisconnect) {
        HeaderButton(LemonadeIcons.Unlink, device.disconnectLabel.resolve()) { onEvent(WorkspaceEvent.DisconnectDevice(device.id)) }
      }
      // Always offered: where there's no room beside the logs, the screen goes above them instead.
      val split = device.layout == DevicesLayoutOption.SideBySide
      HeaderButton(
        icon = if (split) LemonadeIcons.StackThree else LemonadeIcons.Board,
        label = stringResource(if (split) Res.string.devices_layout_tabs else Res.string.devices_layout_split),
      ) { onEvent(WorkspaceEvent.SetDevicesLayout(if (split) DevicesLayoutOption.Tabs else DevicesLayoutOption.SideBySide)) }
    }
    if (panel.windowControls) {
      val separate = panel.window == DevicesWindow.Separate
      HeaderButton(
        icon = if (separate) LemonadeIcons.ArrowDownLeft else LemonadeIcons.ArrowUpRight,
        label = stringResource(if (separate) Res.string.devices_dock else Res.string.devices_separate),
      ) { onEvent(WorkspaceEvent.MoveDevices(if (separate) DevicesWindow.Docked else DevicesWindow.Separate)) }
      HeaderButton(LemonadeIcons.Times, stringResource(Res.string.devices_close)) { onEvent(WorkspaceEvent.CloseDevices) }
    }
  }
}

@Composable
private fun HeaderButton(
  icon: LemonadeIcons,
  label: String,
  onClick: () -> Unit,
) {
  HoverTooltip(text = label, placement = TooltipPlacement.BelowEnd) {
    LemonadeUi.IconButton(
      icon = icon,
      contentDescription = label,
      onClick = onClick,
      variant = LemonadeButtonVariant.Neutral,
      type = LemonadeButtonType.Ghost,
      size = LemonadeButtonSize.Small,
    )
  }
}

/** A fixed bar at the bottom of a step, over a hairline: Pair a device, Pair, Open… */
@Composable
private fun Footer(content: @Composable ColumnScope.() -> Unit) {
  val spaces = LemonadeTheme.spaces
  Column(
    verticalArrangement = Arrangement.spacedBy(spaces.spacing200),
    modifier = Modifier
      .fillMaxWidth()
      .topHairline(LemonadeTheme.colors.border.borderNeutralLow)
      .padding(start = spaces.spacing400, end = spaces.spacing400, top = spaces.spacing300, bottom = spaces.spacing400),
    content = content,
  )
}

@Composable
private fun DeviceList(
  content: DevicesContentModel.Devices,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val spaces = LemonadeTheme.spaces
  Column(modifier = modifier) {
    Column(
      verticalArrangement = Arrangement.spacedBy(spaces.spacing400),
      modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(spaces.spacing300),
    ) {
      content.groups.forEach { group -> DeviceGroup(group = group, onEvent = onEvent) }
    }
    Footer {
      LemonadeUi.Button(
        label = stringResource(Res.string.devices_pair),
        onClick = { onEvent(WorkspaceEvent.StartPairing) },
        leadingIcon = LemonadeIcons.Plus,
        variant = LemonadeButtonVariant.Neutral,
        type = LemonadeButtonType.Subtle,
        size = LemonadeButtonSize.Small,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun DeviceGroup(
  group: DeviceGroupModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val spaces = LemonadeTheme.spaces
  val colors = LemonadeTheme.colors
  Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing50)) {
    LemonadeUi.Text(
      text = stringResource(if (group.platform == MobilePlatformModel.Android) Res.string.devices_android else Res.string.devices_ios).uppercase(),
      textStyle = LemonadeTheme.typography.bodyXSmallOverline,
      color = colors.content.contentSecondary,
      modifier = Modifier.padding(horizontal = spaces.spacing300, vertical = spaces.spacing100),
    )
    if (group.devices.isEmpty()) {
      LemonadeUi.Text(
        text = group.missingTool?.resolve() ?: stringResource(Res.string.devices_none),
        textStyle = LemonadeTheme.typography.bodySmallRegular,
        color = colors.content.contentSecondary,
        modifier = Modifier.padding(horizontal = spaces.spacing300, vertical = spaces.spacing100),
      )
    }
    group.devices.forEach { device ->
      DeviceRow(
        device = device,
        onClick = { onEvent(WorkspaceEvent.SelectDevice(device.id)) },
        onToggleWindow = { onEvent(WorkspaceEvent.ToggleDeviceWindow(device.id)) },
      )
    }
  }
}

/** A device in the list: opens its page here, or brings its own window forward; ↗ opens or closes that window. */
@Composable
private fun DeviceRow(
  device: DeviceRowModel,
  onClick: () -> Unit,
  onToggleWindow: () -> Unit,
) {
  val colors = LemonadeTheme.colors
  val spaces = LemonadeTheme.spaces
  HoverRow(onClick = onClick, minHeight = 56.dp, modifier = Modifier.padding(0.dp)) {
    LemonadeUi.Icon(icon = device.platform.icon, contentDescription = null, size = LemonadeAssetSize.Medium, tint = colors.content.contentSecondary)
    Column(modifier = Modifier.weight(1f)) {
      LemonadeUi.Text(text = device.name, textStyle = LemonadeTheme.typography.bodySmallSemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spaces.spacing100)) {
        LemonadeUi.Icon(
          icon = if (device.connection == DeviceConnectionModel.Usb) LemonadeIcons.Link else LemonadeIcons.Wifi,
          contentDescription = null,
          size = LemonadeAssetSize.XSmall,
          tint = colors.content.contentSecondary,
        )
        LemonadeUi.Text(
          text = device.detail.resolve(),
          textStyle = LemonadeTheme.typography.bodyXSmallRegular,
          color = if (device.ready) colors.content.contentSecondary else colors.content.contentCaution,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (device.canOpenWindow) {
      val label = stringResource(if (device.windowOpen) Res.string.device_window_close else Res.string.device_window_open, device.name)
      HoverTooltip(text = label, placement = TooltipPlacement.BelowEnd) {
        LemonadeUi.IconButton(
          icon = LemonadeIcons.ArrowUpRight,
          contentDescription = label,
          onClick = onToggleWindow,
          variant = LemonadeButtonVariant.Neutral,
          type = if (device.windowOpen) LemonadeButtonType.Solid else LemonadeButtonType.Ghost,
          size = LemonadeButtonSize.Small,
        )
      }
    }
    LemonadeUi.Icon(icon = LemonadeIcons.ChevronRight, contentDescription = null, size = LemonadeAssetSize.Small, tint = colors.content.contentTertiary)
  }
}

/**
 * A device's screen and logs, as tabs or together. Together, a portrait screen sits beside its logs
 * when there's room; a landscape one, or one without room beside them, goes above them.
 */
@Composable
private fun DevicePage(
  device: DevicesContentModel.Device,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val spaces = LemonadeTheme.spaces
  val hairline = LemonadeTheme.colors.border.borderNeutralLow
  if (device.layout == DevicesLayoutOption.Tabs) {
    Column(modifier = modifier) {
      val tabs = listOf(DeviceTab.Screen to Res.string.device_tab_screen, DeviceTab.Logs to Res.string.device_tab_logs)
      LemonadeUi.SegmentedControl(
        properties = tabs.map { (tab, label) ->
          TabButtonProperties.labelAndIcon(stringResource(label), if (tab == DeviceTab.Screen) LemonadeIcons.Smartphone else LemonadeIcons.List)
        },
        selectedTab = tabs.indexOfFirst { it.first == device.tab },
        onTabSelected = { index -> onEvent(WorkspaceEvent.SetDeviceTab(tabs[index].first)) },
        size = LemonadeSegmentedControlSize.Medium,
        modifier = Modifier.fillMaxWidth().padding(start = spaces.spacing400, end = spaces.spacing400, top = spaces.spacing300),
      )
      val fill = Modifier.weight(1f).fillMaxWidth()
      when (device.tab) {
        DeviceTab.Screen -> ScreenPane(device = device, onEvent = onEvent, modifier = fill)
        DeviceTab.Logs -> LogsPane(logs = device.logs, onEvent = onEvent, modifier = fill)
      }
    }
    return
  }
  BoxWithConstraints(modifier = modifier) {
    val ratio = screenRatio(device)
    if (ratio < 1f && maxWidth >= SideBySideMinWidth) {
      Row(modifier = Modifier.fillMaxSize()) {
        ScreenPane(device = device, onEvent = onEvent, modifier = Modifier.width(ScreenPaneWidth).fillMaxHeight().drawRightHairline(hairline))
        LogsPane(logs = device.logs, onEvent = onEvent, modifier = Modifier.weight(1f).fillMaxHeight())
      }
    } else {
      // As tall as the picture needs at this width, up to a share of the page.
      val pictureHeight = (maxWidth - spaces.spacing400 * 2) / ratio
      val screenHeight = min(pictureHeight + ScreenPaneChrome, maxHeight * StackedScreenShare)
      Column(modifier = Modifier.fillMaxSize()) {
        ScreenPane(device = device, onEvent = onEvent, modifier = Modifier.fillMaxWidth().height(screenHeight).bottomHairline(hairline))
        LogsPane(logs = device.logs, onEvent = onEvent, modifier = Modifier.weight(1f).fillMaxWidth())
      }
    }
  }
}

/** Width over height of the device's screen: of the latest frame, else a phone's, turned the way the device is. */
private fun screenRatio(device: DevicesContentModel.Device): Float {
  val frame = device.screen as? DeviceScreenModel.Frame
  return when {
    frame != null && frame.width > 0 && frame.height > 0 -> frame.width.toFloat() / frame.height
    device.controls.landscape -> 19.5f / 9f
    else -> 9f / 19.5f
  }
}

/** 1px rule along the end edge, between the screen and the logs. */
private fun Modifier.drawRightHairline(color: Color): Modifier = drawBehind {
  drawRect(color = color, topLeft = Offset(size.width - 1.dp.toPx(), 0f), size = Size(1.dp.toPx(), size.height))
}

@Composable
private fun ScreenPane(
  device: DevicesContentModel.Device,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val spaces = LemonadeTheme.spaces
  val colors = LemonadeTheme.colors
  when (val screen = device.screen) {
    is DeviceScreenModel.Unavailable -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spaces.spacing300),
        modifier = Modifier
          .padding(horizontal = spaces.spacing400, vertical = spaces.spacing300)
          .fillMaxWidth()
          .clip(LemonadeTheme.shapes.radius400)
          .background(colors.background.bgSubtle)
          .padding(spaces.spacing300),
      ) {
        LemonadeUi.SymbolContainer(icon = LemonadeIcons.Link, contentDescription = "", voice = SymbolContainerVoice.Neutral, size = SymbolContainerSize.Medium)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
          LemonadeUi.Text(text = screen.title.resolve(), textStyle = LemonadeTheme.typography.bodySmallSemiBold)
          LemonadeUi.Text(text = screen.detail.resolve(), textStyle = LemonadeTheme.typography.bodyXSmallRegular, color = colors.content.contentSecondary)
        }
      }
    }
    else -> Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(spaces.spacing200),
      modifier = modifier.padding(horizontal = spaces.spacing400, vertical = spaces.spacing300),
    ) {
      BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        val frame = screen as? DeviceScreenModel.Frame
        val ratio = screenRatio(device)
        val fits = maxWidth / maxHeight.coerceAtLeast(1.dp) > ratio
        MirroredScreen(
          frame = frame,
          controls = device.controls,
          onEvent = onEvent,
          modifier = (if (fits) Modifier.fillMaxHeight() else Modifier.fillMaxWidth()).aspectRatio(ratio, matchHeightConstraintsFirst = fits),
        )
      }
      MirrorControls(controls = device.controls, onEvent = onEvent)
      device.screenDetail?.let { detail ->
        LemonadeUi.Text(
          text = detail.resolve(),
          textStyle = LemonadeTheme.typography.bodyXSmallRegular,
          textAlign = TextAlign.Center,
          color = colors.content.contentSecondary,
        )
      }
    }
  }
}

/** The mirrored screen, edge to edge; clicks, drags and, when on, typing go to the device. */
@Composable
private fun MirroredScreen(
  frame: DeviceScreenModel.Frame?,
  controls: MirrorControlsModel,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = LemonadeTheme.colors
  val shape = LemonadeTheme.shapes.radius300
  val image = remember(frame?.picture) { frame?.picture?.let(::decodePicture) }
  val currentFrame by rememberUpdatedState(frame)
  val slop = LocalViewConfiguration.current.touchSlop
  val keys = remember { FocusRequester() }
  Box(
    modifier = modifier
      .clip(shape)
      .background(colors.background.bgSubtle)
      .border(1.dp, colors.border.borderNeutralLow, shape),
    contentAlignment = Alignment.Center,
  ) {
    if (image == null) {
      Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing200)) {
        LemonadeUi.Spinner(size = LemonadeAssetSize.Small, tint = colors.content.contentSecondary)
        LemonadeUi.Text(text = stringResource(Res.string.mirror_loading), textStyle = LemonadeTheme.typography.bodyXSmallRegular, color = colors.content.contentSecondary)
      }
    } else {
      Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier
          .fillMaxSize()
          .then(
            if (controls.touch) {
              Modifier.pointerInput(Unit) {
                awaitEachGesture {
                  val down = awaitFirstDown()
                  if (controls.keyboardOn) keys.requestFocus()
                  val startTime = down.uptimeMillis
                  var last = down.position
                  var moved = false
                  while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    last = change.position
                    if ((last - down.position).getDistance() > slop) moved = true
                    if (!change.pressed) {
                      val shown = currentFrame ?: break
                      fun deviceX(x: Float) = (x / size.width * shown.width).toInt().coerceIn(0, shown.width - 1)
                      fun deviceY(y: Float) = (y / size.height * shown.height).toInt().coerceIn(0, shown.height - 1)
                      onEvent(
                        if (moved) {
                          WorkspaceEvent.SwipeDevice(deviceX(down.position.x), deviceY(down.position.y), deviceX(last.x), deviceY(last.y), (change.uptimeMillis - startTime).coerceAtLeast(50))
                        } else {
                          WorkspaceEvent.TapDevice(deviceX(down.position.x), deviceY(down.position.y))
                        },
                      )
                      break
                    }
                  }
                }
              }
            } else {
              Modifier
            },
          )
          .then(if (controls.keyboardOn) Modifier.deviceKeyboard(keys, onEvent) else Modifier),
      )
    }
  }
  LaunchedEffect(controls.keyboardOn, image != null) { if (controls.keyboardOn && image != null) keys.requestFocus() }
}

/** While typing goes to the device: letters as text, and the keys a phone has. */
private fun Modifier.deviceKeyboard(focus: FocusRequester, onEvent: (WorkspaceEvent) -> Unit): Modifier = this
  .focusRequester(focus)
  .focusable()
  .onKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isMetaPressed) return@onKeyEvent false
    val key = when (event.key) {
      Key.Enter, Key.NumPadEnter -> DeviceKeyModel.Enter
      Key.Backspace -> DeviceKeyModel.Backspace
      Key.Delete -> DeviceKeyModel.ForwardDelete
      Key.Tab -> DeviceKeyModel.Tab
      Key.Escape -> DeviceKeyModel.Escape
      Key.DirectionUp -> DeviceKeyModel.Up
      Key.DirectionDown -> DeviceKeyModel.Down
      Key.DirectionLeft -> DeviceKeyModel.Left
      Key.DirectionRight -> DeviceKeyModel.Right
      else -> null
    }
    if (key != null) {
      onEvent(WorkspaceEvent.PressDeviceKey(key))
      return@onKeyEvent true
    }
    val code = event.utf16CodePoint
    if (code < ' '.code || code == 0xFFFF) return@onKeyEvent false
    onEvent(WorkspaceEvent.TypeOnDevice(code.toChar().toString()))
    true
  }

private fun decodePicture(picture: ScreenPictureModel): ImageBitmap? = runCatching {
  when (picture) {
    is ScreenPictureModel.Encoded -> SkiaImage.makeFromEncoded(picture.bytes).toComposeImageBitmap()
    is ScreenPictureModel.Bgra -> Bitmap().apply {
      installPixels(ImageInfo(picture.width, picture.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), picture.pixels, picture.rowBytes)
      setImmutable()
    }.asComposeImageBitmap()
  }
}.getOrNull()

@Composable
private fun MirrorControls(
  controls: MirrorControlsModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val buttons = buildList {
    if (controls.back) add(Triple(LemonadeIcons.ArrowLeft, Res.string.mirror_back, false) to WorkspaceEvent.PressDeviceBack)
    if (controls.home) add(Triple(LemonadeIcons.Home, Res.string.mirror_home, false) to WorkspaceEvent.PressDeviceHome)
    if (controls.rotate) add(Triple(LemonadeIcons.ArrowRotateCw, Res.string.mirror_rotate, controls.landscape) to WorkspaceEvent.RotateDevice)
    if (controls.screenshot) add(Triple(LemonadeIcons.Camera, Res.string.mirror_screenshot, false) to WorkspaceEvent.ScreenshotToSession)
    if (controls.keyboard) add(Triple(LemonadeIcons.Keyboard, Res.string.mirror_keyboard, controls.keyboardOn) to WorkspaceEvent.ToggleDeviceKeyboard)
  }
  if (buttons.isEmpty()) return
  Row(
    horizontalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing100),
    modifier = Modifier
      .clip(LemonadeTheme.shapes.radiusFull)
      .background(LemonadeTheme.colors.background.bgSubtle)
      .padding(LemonadeTheme.spaces.spacing100),
  ) {
    buttons.forEach { (button, event) ->
      val (icon, label, on) = button
      val text = stringResource(label)
      HoverTooltip(text = text, placement = TooltipPlacement.BelowStart) {
        LemonadeUi.IconButton(
          icon = icon,
          contentDescription = text,
          onClick = { onEvent(event) },
          variant = LemonadeButtonVariant.Neutral,
          type = if (on) LemonadeButtonType.Solid else LemonadeButtonType.Ghost,
          size = LemonadeButtonSize.Small,
        )
      }
    }
  }
}

@OptIn(ExperimentalLemonadeComponent::class)
@Composable
private fun LogsPane(
  logs: DeviceLogsModel,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val spaces = LemonadeTheme.spaces
  val colors = LemonadeTheme.colors
  Column(modifier = modifier.padding(top = spaces.spacing200)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spaces.spacing200),
      modifier = Modifier.fillMaxWidth().padding(start = spaces.spacing400, end = spaces.spacing400, top = spaces.spacing100, bottom = spaces.spacing200),
    ) {
      // The chips take what the buttons leave, and scroll sideways when a narrow panel can't fit them.
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spaces.spacing200),
        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
      ) {
        LemonadeUi.Chip(
          label = stringResource(Res.string.logs_all),
          selected = logs.filter == LogFilter.All,
          leadingIcon = null,
          onChipClicked = { onEvent(WorkspaceEvent.SetLogFilter(LogFilter.All)) },
        )
        LemonadeUi.Chip(
          label = stringResource(Res.string.logs_warnings),
          selected = logs.filter == LogFilter.Warnings,
          leadingIcon = null,
          counter = logs.warningCount.takeIf { it > 0 },
          onChipClicked = { onEvent(WorkspaceEvent.SetLogFilter(LogFilter.Warnings)) },
        )
        LemonadeUi.Chip(
          label = stringResource(Res.string.logs_errors),
          selected = logs.filter == LogFilter.Errors,
          leadingIcon = null,
          counter = logs.errorCount.takeIf { it > 0 },
          onChipClicked = { onEvent(WorkspaceEvent.SetLogFilter(LogFilter.Errors)) },
        )
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spaces.spacing100)) {
        LemonadeUi.IconButton(
          icon = LemonadeIcons.Search,
          contentDescription = stringResource(Res.string.logs_filter),
          onClick = { onEvent(WorkspaceEvent.ToggleLogSearch) },
          variant = LemonadeButtonVariant.Neutral,
          type = if (logs.searchOpen) LemonadeButtonType.Solid else LemonadeButtonType.Ghost,
          size = LemonadeButtonSize.XSmall,
        )
        CopyLinesButton(lines = logs.lines)
        LemonadeUi.IconButton(
          icon = LemonadeIcons.Trash,
          contentDescription = stringResource(Res.string.logs_clear),
          onClick = { onEvent(WorkspaceEvent.ClearLogs) },
          variant = LemonadeButtonVariant.Neutral,
          type = LemonadeButtonType.Ghost,
          size = LemonadeButtonSize.XSmall,
        )
      }
    }
    if (logs.searchOpen) {
      LemonadeUi.SearchField(
        input = logs.query,
        onInputChanged = { onEvent(WorkspaceEvent.SetLogQuery(it)) },
        placeholder = stringResource(Res.string.logs_filter_placeholder),
        modifier = Modifier.fillMaxWidth().padding(start = spaces.spacing400, end = spaces.spacing400, bottom = spaces.spacing200),
      )
    }
    LogLines(logs = logs, modifier = Modifier.weight(1f).fillMaxWidth())
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spaces.spacing300),
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .topHairline(colors.border.borderNeutralLow)
        .padding(horizontal = spaces.spacing400, vertical = spaces.spacing200),
    ) {
      LiveToggle(paused = logs.paused, onClick = { onEvent(WorkspaceEvent.ToggleLogsPaused) })
      val apps = logs.apps
      if (apps == null) {
        LemonadeUi.Text(
          text = logs.source,
          textStyle = LemonadeCodeTheme.typography.codeSmall.copy(color = colors.content.contentSecondary),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
      } else {
        Box(modifier = Modifier.weight(1f)) {
          LogAppsPicker(source = logs.source, apps = apps, onEvent = onEvent)
        }
      }
      logs.sendErrorsLabel?.let { label ->
        LemonadeUi.Button(
          label = label.resolve(),
          onClick = { onEvent(WorkspaceEvent.SendLogErrors) },
          leadingIcon = LemonadeIcons.Send,
          variant = LemonadeButtonVariant.Primary,
          type = LemonadeButtonType.Solid,
          size = LemonadeButtonSize.XSmall,
        )
      }
    }
  }
}

/**
 * The lines, newest last, selectable with the mouse: a drag, even across lines, a double or triple
 * click, or a shift-click selects, and ⌘C, Ctrl+C or the right-click menu copies the selection as
 * text, a line each. While live the list follows the newest line, except while lines are being
 * selected or after it was scrolled up; scrolling back to the bottom or resuming follows again.
 */
@Composable
private fun LogLines(
  logs: DeviceLogsModel,
  modifier: Modifier = Modifier,
) {
  val spaces = LemonadeTheme.spaces
  val colors = LemonadeTheme.colors
  val lines = logs.lines
  val listState = rememberLazyListState()
  val selection = remember { LogSelection() }
  val pins = remember { LinePins() }
  var follow by remember { mutableStateOf(true) }
  LaunchedEffect(logs.paused) { if (!logs.paused) follow = true }
  LaunchedEffect(listState) {
    snapshotFlow { listState.canScrollForward }.collect { more -> if (!more) follow = true }
  }
  val holding = selection.holding
  LaunchedEffect(lines.size, lines.lastOrNull()?.key, logs.paused, follow, holding) {
    if (!logs.paused && follow && !holding && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
  }
  Box(modifier = modifier.background(colors.background.bgSubtle)) {
    val emptyText = logs.emptyText
    if (emptyText != null) {
      LemonadeUi.Text(
        text = emptyText.resolve(),
        textStyle = LemonadeTheme.typography.bodySmallRegular,
        textAlign = TextAlign.Center,
        color = colors.content.contentSecondary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = spaces.spacing300, vertical = spaces.spacing600),
      )
    }
    SelectionContainer(
      modifier = Modifier
        .fillMaxSize()
        .onFocusChanged { selection.focused = it.hasFocus }
        .pointerInput(selection, listState) {
          trackLogSelection(selection, onScrolledBack = { if (listState.canScrollBackward) follow = false })
        },
    ) {
      LazyColumn(state = listState, contentPadding = PaddingValues(spaces.spacing200), modifier = Modifier.fillMaxSize()) {
        items(lines, key = { it.key }) { line ->
          if (holding) KeepComposed(line.key, pins)
          LogRow(line)
        }
      }
    }
  }
}

/**
 * Whether lines are being selected, as far as the mouse tells: the selection itself is the
 * [SelectionContainer]'s own. It holds from a press until a plain click clears it again, or the
 * list loses the focus, which clears it too.
 */
@Stable
private class LogSelection {
  /** The button is down in the list. */
  var pressed by mutableStateOf(false)

  /** The last press dragged, clicked twice or thrice, or held Shift: it selected something. */
  var selected by mutableStateOf(false)

  /** The list has the focus, as it does once something in it is selected. */
  var focused by mutableStateOf(false)

  /** The list should hold still: a selection is being made or is there. */
  val holding: Boolean get() = pressed || (selected && focused)
}

/**
 * Watches the mouse over the log lines without taking anything from the selection underneath:
 * presses, drags and clicks update [selection]; scrolling towards older lines calls [onScrolledBack].
 */
private suspend fun PointerInputScope.trackLogSelection(
  selection: LogSelection,
  onScrolledBack: () -> Unit,
) = awaitPointerEventScope {
  var downAt = Offset.Unspecified
  var lastDownAt = Offset.Unspecified
  var lastDownTime = 0L
  var clicks = 0
  while (true) {
    val event = awaitPointerEvent(PointerEventPass.Initial)
    val change = event.changes.firstOrNull() ?: continue
    when (event.type) {
      PointerEventType.Scroll -> if (change.scrollDelta.y < 0f) onScrolledBack()
      PointerEventType.Press -> if (event.buttons.isPrimaryPressed && !downAt.isSpecified) {
        val again = lastDownAt.isSpecified &&
          change.uptimeMillis - lastDownTime < viewConfiguration.doubleTapTimeoutMillis &&
          (change.position - lastDownAt).getDistance() < viewConfiguration.touchSlop
        clicks = if (again) clicks + 1 else 1
        downAt = change.position
        lastDownAt = change.position
        lastDownTime = change.uptimeMillis
        selection.pressed = true
        selection.selected = clicks > 1 || event.keyboardModifiers.isShiftPressed
      }
      PointerEventType.Move -> if (downAt.isSpecified && (change.position - downAt).getDistance() > viewConfiguration.touchSlop) {
        selection.selected = true
      }
      PointerEventType.Release -> if (downAt.isSpecified && !event.buttons.isPrimaryPressed) {
        downAt = Offset.Unspecified
        selection.pressed = false
      }
      else -> Unit
    }
  }
}

/** Keeps the line [key] composed while it scrolls out of sight, so a selection reaching it still copies it. */
@Composable
private fun KeepComposed(key: Long, pins: LinePins) {
  val container = LocalPinnableContainer.current ?: return
  DisposableEffect(container, key) {
    pins.pin(key, container)
    onDispose { pins.release(key) }
  }
}

/**
 * The lines kept composed, by key. A lazy list lays pinned items out of sight in the order they
 * were pinned, and a selection goes by where its lines are, so each new one pins them all again in
 * the lines' own order, which their keys follow.
 */
private class LinePins {
  private val containers = mutableMapOf<Long, PinnableContainer>()
  private val handles = mutableMapOf<Long, PinnableContainer.PinnedHandle>()

  fun pin(key: Long, container: PinnableContainer) {
    containers[key] = container
    // Released and pinned again in one go, before the list measures, so none of them leaves.
    handles.values.forEach { it.release() }
    handles.clear()
    containers.keys.sorted().forEach { handles[it] = containers.getValue(it).pin() }
  }

  fun release(key: Long) {
    val _ = containers.remove(key)
    handles.remove(key)?.release()
  }
}

/** Copies the lines shown, as text a line each, and says so for a moment. */
@Composable
private fun CopyLinesButton(lines: List<LogLineModel>) {
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  var copied by remember { mutableStateOf(false) }
  LaunchedEffect(copied) {
    if (copied) {
      delay(CopiedMillis)
      copied = false
    }
  }
  LemonadeUi.IconButton(
    icon = if (copied) LemonadeIcons.Check else LemonadeIcons.Copy,
    contentDescription = stringResource(if (copied) Res.string.logs_copied else Res.string.logs_copy),
    onClick = {
      val text = lines.joinToString("\n") { it.plainText() }
      scope.launch {
        val _ = runCatching { clipboard.setClipEntry(plainTextClipEntry(text)) }
        copied = true
      }
    },
    enabled = lines.isNotEmpty(),
    variant = LemonadeButtonVariant.Neutral,
    type = LemonadeButtonType.Ghost,
    size = LemonadeButtonSize.XSmall,
  )
}

/** How long the copy button shows it copied. */
private const val CopiedMillis = 1_500L

/** The time column is at least this many characters wide. */
private const val LogTimeColumns = 9

/** [LogLineModel] as one line of text, as it's shown and copied: time, level letter, tag and message. */
private fun LogLineModel.plainText(): String = "${time.padEnd(LogTimeColumns)} ${level.letter}  $tag $message"

/**
 * One log line: time, level letter, tag and message, in columns; errors on a red wash. It's one
 * text, so a selection across lines copies each whole, and a long message wraps under its own column.
 */
@Composable
private fun LogRow(line: LogLineModel) {
  val colors = LemonadeTheme.colors
  val style = LemonadeCodeTheme.typography.codeSmall
  val ch = rememberCharWidth(style)
  val density = LocalDensity.current
  val error = line.level == LogLevelModel.Error || line.level == LogLevelModel.Fatal
  val levelColor = when (line.level) {
    LogLevelModel.Error, LogLevelModel.Fatal -> colors.content.contentCritical
    LogLevelModel.Warning -> colors.content.contentCaution
    LogLevelModel.Info -> colors.content.contentInfo
    LogLevelModel.Debug, LogLevelModel.Verbose -> colors.content.contentTertiary
  }
  val time = line.time.padEnd(LogTimeColumns)
  val text = buildAnnotatedString {
    withStyle(SpanStyle(color = colors.content.contentTertiary)) { append(time) }
    append(' ')
    withStyle(SpanStyle(color = levelColor, fontWeight = FontWeight.SemiBold)) { append(line.level.letter) }
    append("  ")
    withStyle(SpanStyle(color = LemonadeTheme.themed.blue.content)) { append(line.tag) }
    withStyle(SpanStyle(color = colors.content.contentPrimary)) { append(' ').append(line.message) }
  }
  // Wrapped lines start under the tag, past the time and the level.
  val messageColumn = with(density) { (ch * (time.length + 4)).toSp() }
  LemonadeUi.Text(
    text = text,
    textStyle = style.copy(textIndent = TextIndent(restLine = messageColumn)),
    modifier = Modifier
      .fillMaxWidth()
      .clip(LemonadeTheme.shapes.radius150)
      .background(if (error) colors.background.bgCriticalSubtle else Color.Transparent)
      .padding(horizontal = LemonadeTheme.spaces.spacing200, vertical = 1.dp),
  )
}

/** "● Live" or "● Paused"; pressing it pauses or resumes the list. */
@Composable
private fun LiveToggle(
  paused: Boolean,
  onClick: () -> Unit,
) {
  val colors = LemonadeTheme.colors
  val interactionSource = remember { MutableInteractionSource() }
  val hovered by interactionSource.collectIsHoveredAsState()
  val label = stringResource(if (paused) Res.string.logs_resume else Res.string.logs_pause)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing100),
    modifier = Modifier
      .heightIn(min = 32.dp)
      .clip(LemonadeTheme.shapes.radius200)
      .background(if (hovered) colors.background.bgElevated else Color.Transparent)
      .hoverable(interactionSource)
      .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
      .semantics { contentDescription = label }
      .padding(horizontal = LemonadeTheme.spaces.spacing200),
  ) {
    StatusDot(color = if (paused) colors.background.bgNeutral else colors.background.bgPositive)
    LemonadeUi.Text(
      text = stringResource(if (paused) Res.string.logs_paused else Res.string.logs_live),
      textStyle = LemonadeTheme.typography.bodyXSmallMedium,
      color = if (paused) colors.content.contentSecondary else colors.content.contentPositive,
    )
  }
}

/**
 * Whose lines the log shows, as its source says (`logcat · com.teya.storefront`, `logcat · 2 apps`),
 * and, pressed, the choice of them above it: the app in front, every app, or the apps ticked.
 */
@Composable
private fun LogAppsPicker(
  source: String,
  apps: LogAppsModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val colors = LemonadeTheme.colors
  val spaces = LemonadeTheme.spaces
  val interactionSource = remember { MutableInteractionSource() }
  val hovered by interactionSource.collectIsHoveredAsState()
  val label = stringResource(Res.string.logs_apps_choose)
  Box {
    // Beside the send button the source is often cut short; resting on it shows it whole.
    HoverTooltip(text = source, placement = TooltipPlacement.AboveStart, enabled = !apps.open) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spaces.spacing100),
        modifier = Modifier
          .heightIn(min = 32.dp)
          .clip(LemonadeTheme.shapes.radius200)
          .background(if (hovered || apps.open) colors.background.bgElevated else Color.Transparent)
          .hoverable(interactionSource)
          .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = { onEvent(WorkspaceEvent.ToggleLogApps) })
          .semantics { contentDescription = label }
          .padding(horizontal = spaces.spacing200),
      ) {
        LemonadeUi.Text(
          text = source,
          textStyle = LemonadeCodeTheme.typography.codeSmall.copy(color = colors.content.contentSecondary),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        LemonadeUi.Icon(icon = LemonadeIcons.ChevronUpDown, contentDescription = null, size = LemonadeAssetSize.XSmall, tint = colors.content.contentSecondary)
      }
    }
    if (apps.open) LogAppsPopover(apps = apps, onEvent = onEvent)
  }
}

/** The choice of whose lines the log shows: the app in front or every app, then the apps to tick, found by name. */
@OptIn(ExperimentalLemonadeComponent::class)
@Composable
private fun LogAppsPopover(
  apps: LogAppsModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val colors = LemonadeTheme.colors
  val spaces = LemonadeTheme.spaces
  var query by remember { mutableStateOf("") }
  val shown = apps.apps.filter { query.isBlank() || it.packageName.contains(query.trim(), ignoreCase = true) }
  AnchoredPopover(
    onDismissRequest = { onEvent(WorkspaceEvent.DismissLogApps) },
    width = LogAppsWidth,
    placement = TooltipPlacement.AboveStart,
    padding = spaces.spacing300,
    spacing = spaces.spacing200,
    // Escape closes it even from the find field, which would take the key otherwise.
    onEscape = { onEvent(WorkspaceEvent.DismissLogApps) },
  ) {
    LemonadeUi.Text(
      text = stringResource(Res.string.logs_apps_title),
      textStyle = LemonadeTheme.typography.bodySmallSemiBold,
      modifier = Modifier.padding(start = spaces.spacing200, end = spaces.spacing200, top = spaces.spacing100),
    )
    Column {
      LogAppsChoice(
        title = stringResource(Res.string.logs_apps_front),
        note = stringResource(Res.string.logs_apps_front_note),
        chosen = apps.followsAppInFront,
        onClick = { onEvent(WorkspaceEvent.FollowAppInFront) },
      )
      LogAppsChoice(
        title = stringResource(Res.string.logs_apps_all),
        note = stringResource(Res.string.logs_apps_all_note),
        chosen = apps.showsAll,
        onClick = { onEvent(WorkspaceEvent.ShowAllApps) },
      )
    }
    Column(
      verticalArrangement = Arrangement.spacedBy(spaces.spacing200),
      modifier = Modifier.fillMaxWidth().topHairline(colors.border.borderNeutralLow).padding(top = spaces.spacing200),
    ) {
      if (apps.apps.size > LogAppsFindFrom) {
        LemonadeUi.SearchField(
          input = query,
          onInputChanged = { query = it },
          placeholder = stringResource(Res.string.logs_apps_find),
          modifier = Modifier.fillMaxWidth(),
        )
      }
      Column(modifier = Modifier.heightIn(max = LogAppsMaxHeight).verticalScroll(rememberScrollState())) {
        shown.forEach { app -> LogAppRow(app = app, onClick = { onEvent(WorkspaceEvent.ToggleLogApp(app.packageName)) }) }
        val empty = apps.emptyText?.resolve() ?: if (shown.isEmpty()) stringResource(Res.string.logs_apps_no_match) else null
        if (empty != null) {
          LemonadeUi.Text(
            text = empty,
            textStyle = LemonadeTheme.typography.bodySmallRegular,
            color = colors.content.contentSecondary,
            modifier = Modifier.padding(horizontal = spaces.spacing200, vertical = spaces.spacing300),
          )
        }
      }
    }
  }
}

/** "App in front" or "All apps", with what it means, and a tick when it's the one chosen. */
@Composable
private fun LogAppsChoice(
  title: String,
  note: String,
  chosen: Boolean,
  onClick: () -> Unit,
) {
  val colors = LemonadeTheme.colors
  HoverRow(onClick = onClick, role = Role.RadioButton, modifier = Modifier.semantics { selected = chosen }) {
    Column(modifier = Modifier.weight(1f)) {
      LemonadeUi.Text(text = title, textStyle = LemonadeTheme.typography.bodySmallMedium)
      LemonadeUi.Text(text = note, textStyle = LemonadeTheme.typography.bodyXSmallRegular, color = colors.content.contentSecondary)
    }
    if (chosen) {
      LemonadeUi.Icon(icon = LemonadeIcons.CircleCheck, contentDescription = null, size = LemonadeAssetSize.Small, tint = colors.content.contentBrand)
    }
  }
}

/** An app to tick: its package, and whether it's in front or running. */
@Composable
private fun LogAppRow(
  app: LogAppModel,
  onClick: () -> Unit,
) {
  val colors = LemonadeTheme.colors
  HoverRow(
    onClick = onClick,
    role = Role.Checkbox,
    modifier = Modifier.semantics { toggleableState = ToggleableState(app.ticked) },
  ) {
    LemonadeUi.Checkbox(if (app.ticked) CheckboxStatus.Checked else CheckboxStatus.Unchecked, onClick)
    Column(modifier = Modifier.weight(1f)) {
      LemonadeUi.Text(
        text = app.packageName,
        textStyle = LemonadeCodeTheme.typography.codeSmall.copy(color = colors.content.contentPrimary),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      app.detail?.let { detail ->
        LemonadeUi.Text(text = detail.resolve(), textStyle = LemonadeTheme.typography.bodyXSmallRegular, color = colors.content.contentSecondary, maxLines = 1)
      }
    }
  }
}

private val LogAppsWidth = 320.dp
private val LogAppsMaxHeight = 280.dp

/** With more apps than this, a field finds them by name. */
private const val LogAppsFindFrom = 6

/** A step's question and its answers, as big rows: what to pair, then how. */
@Composable
private fun Choices(
  title: String,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val spaces = LemonadeTheme.spaces
  Column(
    verticalArrangement = Arrangement.spacedBy(spaces.spacing400),
    modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = spaces.spacing400, vertical = spaces.spacing500),
  ) {
    LemonadeUi.Text(text = title, textStyle = LemonadeTheme.typography.headingXSmall, modifier = Modifier.padding(horizontal = spaces.spacing100))
    Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing200), content = content)
  }
}

@Composable
private fun Choice(
  icon: LemonadeIcons,
  title: String,
  note: String,
  onClick: () -> Unit,
) {
  val colors = LemonadeTheme.colors
  val spaces = LemonadeTheme.spaces
  val interactionSource = remember { MutableInteractionSource() }
  val hovered by interactionSource.collectIsHoveredAsState()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spaces.spacing300),
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 72.dp)
      .clip(LemonadeTheme.shapes.radius400)
      .background(if (hovered) colors.background.bgElevated else colors.background.bgSubtle)
      .hoverable(interactionSource)
      .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
      .padding(spaces.spacing300),
  ) {
    LemonadeUi.SymbolContainer(icon = icon, contentDescription = "", voice = SymbolContainerVoice.Neutral, size = SymbolContainerSize.Medium)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
      LemonadeUi.Text(text = title, textStyle = LemonadeTheme.typography.bodySmallSemiBold)
      LemonadeUi.Text(text = note, textStyle = LemonadeTheme.typography.bodyXSmallRegular, color = colors.content.contentSecondary)
    }
    LemonadeUi.Icon(icon = LemonadeIcons.ChevronRight, contentDescription = null, size = LemonadeAssetSize.Small, tint = colors.content.contentTertiary)
  }
}

@Composable
private fun ChoosePlatform(
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  Choices(title = stringResource(Res.string.pair_what), modifier = modifier) {
    Choice(LemonadeIcons.BrandAndroid, stringResource(Res.string.pair_android), stringResource(Res.string.pair_android_note)) {
      onEvent(WorkspaceEvent.ChoosePairingPlatform(MobilePlatformModel.Android))
    }
    Choice(LemonadeIcons.BrandApple, stringResource(Res.string.pair_ios), stringResource(Res.string.pair_ios_note)) {
      onEvent(WorkspaceEvent.ChoosePairingPlatform(MobilePlatformModel.Ios))
    }
  }
}

@Composable
private fun ChooseMethod(
  content: DevicesContentModel.ChooseMethod,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val android = content.platform == MobilePlatformModel.Android
  val nearbyNote = when {
    content.nearbyCount == 0 -> stringResource(Res.string.pair_nearby_none)
    android -> pluralStringResource(Res.plurals.pair_nearby_found_android, content.nearbyCount, content.nearbyCount)
    else -> pluralStringResource(Res.plurals.pair_nearby_found_ios, content.nearbyCount, content.nearbyCount)
  }
  Choices(title = stringResource(Res.string.pair_how), modifier = modifier) {
    if (android) {
      Choice(LemonadeIcons.QrCode, stringResource(Res.string.pair_qr), stringResource(Res.string.pair_qr_note)) { onEvent(WorkspaceEvent.ChoosePairingMethod(PairingMethod.QrCode)) }
      Choice(LemonadeIcons.Keyboard, stringResource(Res.string.pair_code), stringResource(Res.string.pair_code_note)) { onEvent(WorkspaceEvent.ChoosePairingMethod(PairingMethod.PairingCode)) }
    } else {
      Choice(LemonadeIcons.Link, stringResource(Res.string.pair_cable), stringResource(Res.string.pair_cable_note)) { onEvent(WorkspaceEvent.ChoosePairingMethod(PairingMethod.Cable)) }
    }
    Choice(LemonadeIcons.Wifi, stringResource(Res.string.pair_nearby), nearbyNote) { onEvent(WorkspaceEvent.ChoosePairingMethod(PairingMethod.Nearby)) }
  }
}

/** A step that waits on the device: a picture, what to do on the device, and a spinner. */
@Composable
private fun Waiting(
  title: String,
  text: String,
  status: String,
  error: String?,
  modifier: Modifier = Modifier,
  picture: @Composable () -> Unit,
) {
  val spaces = LemonadeTheme.spaces
  val colors = LemonadeTheme.colors
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(spaces.spacing500, Alignment.CenterVertically),
    modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = spaces.spacing500, vertical = spaces.spacing600),
  ) {
    picture()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(spaces.spacing100)) {
      LemonadeUi.Text(text = title, textStyle = LemonadeTheme.typography.headingXXSmall)
      LemonadeUi.Text(
        text = text,
        textStyle = LemonadeTheme.typography.bodySmallRegular,
        textAlign = TextAlign.Center,
        color = colors.content.contentSecondary,
        modifier = Modifier.widthIn(max = 300.dp),
      )
    }
    if (error != null) {
      LemonadeUi.Text(
        text = error,
        textStyle = LemonadeTheme.typography.bodySmallRegular,
        textAlign = TextAlign.Center,
        color = colors.content.contentCritical,
        modifier = Modifier.widthIn(max = 300.dp),
      )
    } else {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spaces.spacing200)) {
        LemonadeUi.Spinner(size = LemonadeAssetSize.XSmall, tint = colors.content.contentSecondary)
        LemonadeUi.Text(text = status, textStyle = LemonadeTheme.typography.bodyXSmallMedium, color = colors.content.contentSecondary)
      }
    }
  }
}

@Composable
private fun ScanQr(
  content: DevicesContentModel.ScanQr,
  modifier: Modifier = Modifier,
) {
  val label = stringResource(Res.string.pair_qr_label)
  Waiting(
    title = stringResource(Res.string.pair_qr_title),
    text = stringResource(Res.string.pair_qr_text),
    status = stringResource(if (content.pairing) Res.string.pair_busy else Res.string.pair_qr_waiting),
    error = content.error?.resolve(),
    modifier = modifier,
  ) {
    val shape = LemonadeTheme.shapes.radius500
    val qr = content.qr
    if (qr != null) {
      QrCode(qr = qr, modifier = Modifier.size(208.dp).semantics { contentDescription = label })
    } else {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .size(208.dp)
          .clip(shape)
          .background(LemonadeTheme.colors.background.bgSubtle)
          .dashedBorder(width = 1.dp, color = LemonadeTheme.colors.border.borderNeutralHigh, cornerRadius = LemonadeTheme.radius.radius500)
          .semantics { contentDescription = label },
      ) {
        LemonadeUi.Icon(icon = LemonadeIcons.QrCode, contentDescription = null, size = LemonadeAssetSize.XXXXLarge, tint = LemonadeTheme.colors.content.contentSecondary)
      }
    }
  }
}

/** Dark modules on white with a quiet zone, whatever the theme, so any phone camera reads it. */
@Composable
private fun QrCode(
  qr: QrCodeModel,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier = modifier.clip(LemonadeTheme.shapes.radius500).background(Color.White)) {
    val quiet = 2
    val cells = qr.size + quiet * 2
    val cell = size.minDimension / cells
    for (y in 0 until qr.size) {
      for (x in 0 until qr.size) {
        if (qr.isDark(x, y)) {
          drawRect(color = Color.Black, topLeft = Offset((x + quiet) * cell, (y + quiet) * cell), size = Size(cell + 0.5f, cell + 0.5f))
        }
      }
    }
  }
}

@Composable
private fun EnterCode(
  content: DevicesContentModel.EnterCode,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val spaces = LemonadeTheme.spaces
  val colors = LemonadeTheme.colors
  Column(modifier = modifier) {
    Column(
      verticalArrangement = Arrangement.spacedBy(spaces.spacing400),
      modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = spaces.spacing400, vertical = spaces.spacing500),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing100), modifier = Modifier.padding(horizontal = spaces.spacing100)) {
        LemonadeUi.Text(text = stringResource(Res.string.pair_code_title), textStyle = LemonadeTheme.typography.headingXSmall)
        LemonadeUi.Text(text = stringResource(Res.string.pair_code_text), textStyle = LemonadeTheme.typography.bodySmallRegular, color = colors.content.contentSecondary)
      }
      LemonadeUi.TextField(
        input = content.address,
        onInputChanged = { onEvent(WorkspaceEvent.SetPairingAddress(it)) },
        label = stringResource(Res.string.pair_code_address),
        placeholderText = stringResource(Res.string.pair_code_address_hint),
        modifier = Modifier.fillMaxWidth(),
      )
      LemonadeUi.TextField(
        input = content.code,
        onInputChanged = { onEvent(WorkspaceEvent.SetPairingCode(it)) },
        label = stringResource(Res.string.pair_code_code),
        placeholderText = stringResource(Res.string.pair_code_code_hint),
        modifier = Modifier.fillMaxWidth(),
      )
      content.error?.let { error ->
        LemonadeUi.Text(text = error.resolve(), textStyle = LemonadeTheme.typography.bodySmallRegular, color = colors.content.contentCritical)
      }
    }
    Footer {
      LemonadeUi.Button(
        label = stringResource(if (content.pairing) Res.string.pair_busy else Res.string.pair_action),
        onClick = { onEvent(WorkspaceEvent.PairWithCode) },
        variant = LemonadeButtonVariant.Primary,
        type = LemonadeButtonType.Solid,
        size = LemonadeButtonSize.Small,
        enabled = content.canPair,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun PickNearby(
  content: DevicesContentModel.PickNearby,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val spaces = LemonadeTheme.spaces
  val colors = LemonadeTheme.colors
  Column(
    verticalArrangement = Arrangement.spacedBy(spaces.spacing400),
    modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = spaces.spacing400, vertical = spaces.spacing500),
  ) {
    LemonadeUi.Text(text = stringResource(Res.string.pair_nearby_title), textStyle = LemonadeTheme.typography.headingXSmall, modifier = Modifier.padding(horizontal = spaces.spacing100))
    if (content.devices.isEmpty()) {
      LemonadeUi.Text(
        text = stringResource(if (content.platform == MobilePlatformModel.Android) Res.string.pair_nearby_empty_android else Res.string.pair_nearby_empty_ios),
        textStyle = LemonadeTheme.typography.bodySmallRegular,
        color = colors.content.contentSecondary,
        modifier = Modifier.padding(horizontal = spaces.spacing100),
      )
    }
    content.error?.let { error ->
      LemonadeUi.Text(text = error.resolve(), textStyle = LemonadeTheme.typography.bodySmallRegular, color = colors.content.contentCritical, modifier = Modifier.padding(horizontal = spaces.spacing100))
    }
    Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing200)) {
      content.devices.forEach { device ->
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(spaces.spacing300),
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(LemonadeTheme.shapes.radius400)
            .background(colors.background.bgSubtle)
            .padding(horizontal = spaces.spacing300, vertical = spaces.spacing200),
        ) {
          LemonadeUi.Icon(icon = device.platform.icon, contentDescription = null, size = LemonadeAssetSize.Medium, tint = colors.content.contentSecondary)
          Column(modifier = Modifier.weight(1f)) {
            LemonadeUi.Text(text = device.name, textStyle = LemonadeTheme.typography.bodySmallSemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LemonadeUi.Text(text = device.detail.resolve(), textStyle = LemonadeTheme.typography.bodyXSmallRegular, color = colors.content.contentSecondary)
          }
          val connecting = content.connecting == device.address
          LemonadeUi.Button(
            label = if (connecting) stringResource(Res.string.pair_busy) else device.action.resolve(),
            onClick = { onEvent(WorkspaceEvent.ConnectNearby(device.address)) },
            variant = LemonadeButtonVariant.Primary,
            type = LemonadeButtonType.Solid,
            size = LemonadeButtonSize.Small,
            enabled = content.connecting == null,
          )
        }
      }
    }
  }
}

@Composable
private fun PlugIn(
  content: DevicesContentModel.PlugIn,
  modifier: Modifier = Modifier,
) {
  val trusting = content.trustingDevice
  Waiting(
    title = stringResource(Res.string.pair_cable_title),
    text = stringResource(Res.string.pair_cable_text),
    status = if (trusting != null) stringResource(Res.string.pair_cable_trust, trusting) else stringResource(Res.string.pair_cable_waiting),
    error = content.error?.resolve(),
    modifier = modifier,
  ) {
    LemonadeUi.SymbolContainer(icon = LemonadeIcons.Link, contentDescription = "", voice = SymbolContainerVoice.Neutral, size = SymbolContainerSize.XLarge)
  }
}

@Composable
private fun Paired(
  content: DevicesContentModel.Paired,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val spaces = LemonadeTheme.spaces
  val colors = LemonadeTheme.colors
  Column(modifier = modifier) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(spaces.spacing400, Alignment.CenterVertically),
      modifier = Modifier.weight(1f).fillMaxWidth().padding(spaces.spacing600),
    ) {
      LemonadeUi.SymbolContainer(icon = LemonadeIcons.CircleCheck, contentDescription = "", voice = SymbolContainerVoice.Positive, size = SymbolContainerSize.XLarge)
      Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(spaces.spacing100)) {
        LemonadeUi.Text(text = stringResource(Res.string.paired_title, content.name), textStyle = LemonadeTheme.typography.headingXXSmall)
        LemonadeUi.Text(text = content.detail.resolve(), textStyle = LemonadeTheme.typography.bodySmallRegular, color = colors.content.contentSecondary)
      }
    }
    Footer {
      LemonadeUi.Button(
        label = stringResource(Res.string.paired_open, content.name),
        onClick = { onEvent(WorkspaceEvent.OpenPairedDevice) },
        variant = LemonadeButtonVariant.Primary,
        type = LemonadeButtonType.Solid,
        size = LemonadeButtonSize.Small,
        modifier = Modifier.fillMaxWidth(),
      )
      LemonadeUi.Button(
        label = stringResource(Res.string.paired_another),
        onClick = { onEvent(WorkspaceEvent.StartPairing) },
        variant = LemonadeButtonVariant.Neutral,
        type = LemonadeButtonType.Ghost,
        size = LemonadeButtonSize.Small,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

private val MobilePlatformModel.icon: LemonadeIcons
  get() = when (this) {
    MobilePlatformModel.Android -> LemonadeIcons.BrandAndroid
    MobilePlatformModel.Ios -> LemonadeIcons.BrandApple
  }
