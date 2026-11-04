package dev.lemonadecode.workspace.impl

import dev.lemonadecode.domain.mobile.CablePairing
import dev.lemonadecode.domain.mobile.DeviceAvailability
import dev.lemonadecode.domain.mobile.DeviceCapability
import dev.lemonadecode.domain.mobile.DeviceConnection
import dev.lemonadecode.domain.mobile.DeviceLogLine
import dev.lemonadecode.domain.mobile.DeviceLogs
import dev.lemonadecode.domain.mobile.DeviceScreen
import dev.lemonadecode.domain.mobile.LogLevel
import dev.lemonadecode.domain.mobile.MobileDevice
import dev.lemonadecode.domain.mobile.MobileError
import dev.lemonadecode.domain.mobile.MobilePlatform
import dev.lemonadecode.domain.mobile.MobileTool
import dev.lemonadecode.domain.mobile.MobileTools
import dev.lemonadecode.domain.mobile.NearbyDevice
import dev.lemonadecode.domain.mobile.NearbyKind
import dev.lemonadecode.domain.mobile.QrMatrix
import dev.lemonadecode.domain.mobile.QrPairing
import dev.lemonadecode.domain.mobile.ScreenPicture
import dev.lemonadecode.domain.mobile.ScreenUnavailable
import dev.lemonadecode.domain.settings.DevicesLayout
import dev.lemonadecode.platform.localization.LemonadeCodeString
import dev.lemonadecode.platform.localization.asLemonadeCodeString
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.detail_three
import dev.lemonadecode.workspace.strings.detail_two
import dev.lemonadecode.workspace.strings.device_allow_debugging
import dev.lemonadecode.workspace.strings.device_android_version
import dev.lemonadecode.workspace.strings.device_ios_version
import dev.lemonadecode.workspace.strings.device_offline
import dev.lemonadecode.workspace.strings.device_tap_trust
import dev.lemonadecode.workspace.strings.device_usb
import dev.lemonadecode.workspace.strings.device_wifi
import dev.lemonadecode.workspace.strings.devices_android
import dev.lemonadecode.workspace.strings.devices_connected
import dev.lemonadecode.workspace.strings.devices_disconnect
import dev.lemonadecode.workspace.strings.devices_ios
import dev.lemonadecode.workspace.strings.devices_needs_adb
import dev.lemonadecode.workspace.strings.devices_needs_libimobiledevice
import dev.lemonadecode.workspace.strings.devices_title
import dev.lemonadecode.workspace.strings.logs_cleared
import dev.lemonadecode.workspace.strings.logs_no_match
import dev.lemonadecode.workspace.strings.logs_send_errors
import dev.lemonadecode.workspace.strings.logs_waiting
import dev.lemonadecode.workspace.strings.mirror_clicks
import dev.lemonadecode.workspace.strings.mirror_fps
import dev.lemonadecode.workspace.strings.mirror_keys
import dev.lemonadecode.workspace.strings.mirror_size
import dev.lemonadecode.workspace.strings.mirror_view_only
import dev.lemonadecode.workspace.strings.nearby_awaiting_code
import dev.lemonadecode.workspace.strings.nearby_connectable
import dev.lemonadecode.workspace.strings.nearby_trusted
import dev.lemonadecode.workspace.strings.pair_action
import dev.lemonadecode.workspace.strings.pair_connect
import dev.lemonadecode.workspace.strings.pair_error_adb_missing
import dev.lemonadecode.workspace.strings.pair_error_discovery
import dev.lemonadecode.workspace.strings.pair_error_gone
import dev.lemonadecode.workspace.strings.pair_error_libimobiledevice_missing
import dev.lemonadecode.workspace.strings.pair_error_not_paired
import dev.lemonadecode.workspace.strings.pair_error_timed_out
import dev.lemonadecode.workspace.strings.pair_error_wrong_code
import dev.lemonadecode.workspace.strings.pair_step
import dev.lemonadecode.workspace.strings.pair_step_platform
import dev.lemonadecode.workspace.strings.pair_title
import dev.lemonadecode.workspace.strings.screen_cable_only_detail
import dev.lemonadecode.workspace.strings.screen_cable_only_title
import dev.lemonadecode.workspace.strings.screen_failed_detail
import dev.lemonadecode.workspace.strings.screen_failed_title
import dev.lemonadecode.workspace.strings.screen_not_supported_detail
import dev.lemonadecode.workspace.strings.screen_not_supported_title
import dev.lemonadecode.workspace.uimodel.DeviceConnectionModel
import dev.lemonadecode.workspace.uimodel.DeviceGroupModel
import dev.lemonadecode.workspace.uimodel.DeviceLogsModel
import dev.lemonadecode.workspace.uimodel.DeviceRowModel
import dev.lemonadecode.workspace.uimodel.DeviceScreenModel
import dev.lemonadecode.workspace.uimodel.DevicesButtonModel
import dev.lemonadecode.workspace.uimodel.DevicesContentModel
import dev.lemonadecode.workspace.uimodel.DevicesLayoutOption
import dev.lemonadecode.workspace.uimodel.DevicesPanelModel
import dev.lemonadecode.workspace.uimodel.DevicesWindow
import dev.lemonadecode.workspace.uimodel.LogFilter
import dev.lemonadecode.workspace.uimodel.LogLevelModel
import dev.lemonadecode.workspace.uimodel.LogLineModel
import dev.lemonadecode.workspace.uimodel.MirrorControlsModel
import dev.lemonadecode.workspace.uimodel.MobilePlatformModel
import dev.lemonadecode.workspace.uimodel.NearbyDeviceModel
import dev.lemonadecode.workspace.uimodel.QrCodeModel
import dev.lemonadecode.workspace.uimodel.ScreenPictureModel
import kotlin.math.roundToInt

/** What the Mobile plugin knows right now, for the Devices panel. */
internal data class MobileInput(
  val enabled: Boolean = false,
  val tools: MobileTools? = null,
  val devices: List<MobileDevice> = emptyList(),
  /** The open device's logs and screen. */
  val logs: DeviceLogs? = null,
  val screen: DeviceScreen? = null,
  /** Devices found on the network while pairing. */
  val nearby: List<NearbyDevice> = emptyList(),
  val qr: QrPairing? = null,
  val cable: CablePairing? = null,
)

/** The docked Devices panel's widths in dp: for the list, pairing and tabs, and for a device's screen and logs together. */
internal data class DevicesPanelWidths(
  val narrow: Int = DevicesPanelModel.DefaultWidth,
  val wide: Int = DevicesPanelModel.DefaultWideWidth,
)

internal object DevicesMapper {
  /** Whether the panel shows a device's screen and logs together, which takes the wide width. */
  fun isWide(view: WorkspaceViewState, devices: List<MobileDevice>, layout: DevicesLayout): Boolean {
    val state = view.devices ?: return false
    return state.step == DevicesStep.Device && layout == DevicesLayout.SideBySide && devices.any { it.id == state.deviceId }
  }

  fun clampPanelWidth(width: Int): Int = width.coerceIn(DevicesPanelModel.MinWidth, DevicesPanelModel.MaxWidth)

  fun button(input: MobileInput, view: WorkspaceViewState): DevicesButtonModel? {
    if (!input.enabled) return null
    val open = view.devices != null
    return DevicesButtonModel(
      count = input.devices.size,
      docked = open && view.devicesWindow == DevicesWindow.Docked,
      separate = open && view.devicesWindow == DevicesWindow.Separate,
    )
  }

  /**
   * The panel for [view]'s step; null while it's closed. [agent] is who errors are sent to; null
   * without a session. [widths] are the docked widths last dragged to.
   */
  fun panel(
    input: MobileInput,
    view: WorkspaceViewState,
    layout: DevicesLayout,
    agent: String?,
    widths: DevicesPanelWidths = DevicesPanelWidths(),
  ): DevicesPanelModel? {
    if (!input.enabled) return null
    val state = view.devices ?: return null
    val device = state.deviceId?.let { id -> input.devices.firstOrNull { it.id == id } }
    val pairStep = Res.string.pair_title.asLemonadeCodeString()
    val width = clampPanelWidth(if (isWide(view, input.devices, layout)) widths.wide else widths.narrow)
    fun panel(title: LemonadeCodeString, subtitle: LemonadeCodeString?, back: Boolean, content: DevicesContentModel) = DevicesPanelModel(
      window = view.devicesWindow,
      title = title,
      subtitle = subtitle,
      canGoBack = back,
      content = content,
      focusRequest = view.devicesFocusRequest,
      width = width,
    )
    val platformName = (if (state.pairPlatform == MobilePlatform.Android) Res.string.devices_android else Res.string.devices_ios).asLemonadeCodeString()
    return when (state.step) {
      DevicesStep.List -> list(input, ::panel)
      DevicesStep.Device -> if (device == null) {
        list(input, ::panel)
      } else {
        panel(
          device.name.asLemonadeCodeString(),
          joined(listOfNotNull(version(device), connection(device.connection), device.address.asLemonadeCodeString())),
          true,
          device(device, state, input, layout, agent),
        )
      }
      DevicesStep.PairPlatform -> panel(pairStep, Res.string.pair_step.asLemonadeCodeString(1), true, DevicesContentModel.ChoosePlatform)
      DevicesStep.PairMethod -> panel(
        pairStep,
        Res.string.pair_step_platform.asLemonadeCodeString(2, platformName),
        true,
        DevicesContentModel.ChooseMethod(state.pairPlatform.toModel(), input.nearby.size),
      )
      DevicesStep.PairQr -> {
        val qr = input.qr
        panel(
          pairStep,
          Res.string.pair_step_platform.asLemonadeCodeString(3, platformName),
          true,
          DevicesContentModel.ScanQr(
            qr = (qr as? QrPairing.Showing)?.qr?.toModel(),
            pairing = qr is QrPairing.Pairing,
            error = (qr as? QrPairing.Failed)?.error?.let(::errorText),
          ),
        )
      }
      DevicesStep.PairCode -> panel(
        pairStep,
        Res.string.pair_step_platform.asLemonadeCodeString(3, platformName),
        true,
        DevicesContentModel.EnterCode(
          address = state.pairAddress,
          code = state.pairCode,
          canPair = !state.pairing && canPairWithCode(state.pairAddress, state.pairCode),
          pairing = state.pairing,
          error = state.pairError?.let(::errorText),
        ),
      )
      DevicesStep.PairNearby -> panel(
        pairStep,
        Res.string.pair_step_platform.asLemonadeCodeString(3, platformName),
        true,
        DevicesContentModel.PickNearby(
          platform = state.pairPlatform.toModel(),
          devices = input.nearby.map(::nearby),
          connecting = state.connecting,
          error = state.pairError?.let(::errorText),
        ),
      )
      DevicesStep.PairCable -> {
        val cable = input.cable
        panel(
          pairStep,
          Res.string.pair_step_platform.asLemonadeCodeString(3, platformName),
          true,
          DevicesContentModel.PlugIn(
            trustingDevice = (cable as? CablePairing.WaitingForTrust)?.name,
            error = (cable as? CablePairing.Failed)?.error?.let(::errorText),
          ),
        )
      }
      DevicesStep.Paired -> {
        val added = state.paired?.let { id -> input.devices.firstOrNull { it.id == id } }
        if (added == null) {
          list(input, ::panel)
        } else {
          panel(
            pairStep,
            null,
            false,
            DevicesContentModel.Paired(added.id.value, added.name, joined(listOfNotNull(connection(added.connection), version(added)))),
          )
        }
      }
    }
  }

  private fun list(
    input: MobileInput,
    panel: (LemonadeCodeString, LemonadeCodeString?, Boolean, DevicesContentModel) -> DevicesPanelModel,
  ): DevicesPanelModel {
    val groups = MobilePlatform.entries.map { platform ->
      val missing = when (platform) {
        MobilePlatform.Android -> Res.string.devices_needs_adb.takeIf { input.tools != null && input.tools.adb == null }
        MobilePlatform.Ios -> Res.string.devices_needs_libimobiledevice.takeIf { input.tools != null && input.tools.libimobiledevice == null }
      }
      DeviceGroupModel(
        platform = platform.toModel(),
        devices = input.devices.filter { it.platform == platform }.map(::row),
        missingTool = missing?.asLemonadeCodeString(),
      )
    }
    return panel(
      Res.string.devices_title.asLemonadeCodeString(),
      Res.plurals.devices_connected.asLemonadeCodeString(input.devices.size),
      false,
      DevicesContentModel.Devices(groups),
    )
  }

  fun row(device: MobileDevice): DeviceRowModel {
    val detail = when (device.availability) {
      DeviceAvailability.Ready -> joined(
        listOfNotNull(
          connection(device.connection),
          device.address.substringBefore(':').takeIf { device.connection == DeviceConnection.Wifi && device.platform == MobilePlatform.Android }?.asLemonadeCodeString(),
          version(device),
        ),
      )
      DeviceAvailability.Unauthorized -> (if (device.platform == MobilePlatform.Android) Res.string.device_allow_debugging else Res.string.device_tap_trust).asLemonadeCodeString()
      DeviceAvailability.Offline -> Res.string.device_offline.asLemonadeCodeString()
    }
    return DeviceRowModel(
      id = device.id.value,
      platform = device.platform.toModel(),
      name = device.name,
      connection = device.connection.toModel(),
      detail = detail,
      ready = device.availability == DeviceAvailability.Ready,
    )
  }

  private fun device(
    device: MobileDevice,
    state: DevicesViewState,
    input: MobileInput,
    layout: DevicesLayout,
    agent: String?,
  ): DevicesContentModel.Device {
    val caps = device.capabilities
    val frame = input.screen as? DeviceScreen.Frame
    val screen = when {
      DeviceCapability.Mirror !in caps -> unavailable(if (device.platform == MobilePlatform.Ios && device.connection == DeviceConnection.Wifi) ScreenUnavailable.CableOnly else ScreenUnavailable.NotSupported)
      frame != null -> DeviceScreenModel.Frame(picture(frame.picture), frame.width, frame.height)
      input.screen is DeviceScreen.Unavailable -> unavailable((input.screen as DeviceScreen.Unavailable).reason)
      else -> DeviceScreenModel.Loading
    }
    val width = frame?.width ?: device.screenWidth
    val height = frame?.height ?: device.screenHeight
    val keys = DeviceCapability.Keys in caps
    val screenDetail = if (screen is DeviceScreenModel.Unavailable) {
      null
    } else {
      joined(
        listOfNotNull(
          if (width != null && height != null) Res.string.mirror_size.asLemonadeCodeString(width, height) else null,
          frame?.framesPerSecond?.let { Res.string.mirror_fps.asLemonadeCodeString(it.roundToInt().coerceAtLeast(1)) },
          when {
            DeviceCapability.Touch !in caps -> Res.string.mirror_view_only.asLemonadeCodeString()
            state.keyboard && keys -> Res.string.mirror_keys.asLemonadeCodeString()
            else -> Res.string.mirror_clicks.asLemonadeCodeString()
          },
        ),
      )
    }
    return DevicesContentModel.Device(
      id = device.id.value,
      platform = device.platform.toModel(),
      layout = if (layout == DevicesLayout.Tabs) DevicesLayoutOption.Tabs else DevicesLayoutOption.SideBySide,
      tab = state.tab,
      canDisconnect = DeviceCapability.Disconnect in caps,
      disconnectLabel = Res.string.devices_disconnect.asLemonadeCodeString(device.name),
      screen = screen,
      controls = MirrorControlsModel(
        back = keys,
        home = keys,
        rotate = DeviceCapability.Rotate in caps,
        landscape = frame != null && frame.width > frame.height,
        screenshot = DeviceCapability.Screenshot in caps,
        keyboard = keys,
        keyboardOn = keys && state.keyboard,
        touch = DeviceCapability.Touch in caps && frame != null,
      ),
      screenDetail = screenDetail,
      logs = logs(input.logs, state, agent),
    )
  }

  fun logs(logs: DeviceLogs?, state: DevicesViewState, agent: String?): DeviceLogsModel {
    val all = logs?.lines.orEmpty().let { lines -> state.pausedAt?.let { last -> lines.filter { it.sequence <= last } } ?: lines }
    val query = state.logQuery.trim()
    val matching = if (query.isEmpty()) all else all.filter { "${it.tag} ${it.message}".contains(query, ignoreCase = true) }
    val shown = matching.filter { it.level.passes(state.logFilter) }
    val errors = shown.count { it.level.isError }
    return DeviceLogsModel(
      filter = state.logFilter,
      warningCount = matching.count { it.level.passes(LogFilter.Warnings) },
      errorCount = matching.count { it.level.isError },
      searchOpen = state.logSearch,
      query = state.logQuery,
      lines = shown.map { it.toModel() },
      emptyText = when {
        shown.isNotEmpty() -> null
        all.isNotEmpty() -> Res.string.logs_no_match
        logs?.error != null -> Res.string.pair_error_gone
        state.logsCleared -> Res.string.logs_cleared
        else -> Res.string.logs_waiting
      }?.asLemonadeCodeString(),
      paused = state.pausedAt != null,
      source = logs?.source.orEmpty(),
      sendErrorsLabel = if (errors > 0 && agent != null) Res.plurals.logs_send_errors.asLemonadeCodeString(errors, errors, agent) else null,
    )
  }

  /** The error lines among [logs] as the panel shows them now, for handing to an agent. */
  fun shownErrors(logs: DeviceLogs?, state: DevicesViewState): List<DeviceLogLine> {
    val all = logs?.lines.orEmpty().let { lines -> state.pausedAt?.let { last -> lines.filter { it.sequence <= last } } ?: lines }
    val query = state.logQuery.trim()
    return all.filter { (query.isEmpty() || "${it.tag} ${it.message}".contains(query, ignoreCase = true)) && it.level.passes(state.logFilter) && it.level.isError }
  }

  fun canPairWithCode(address: String, code: String): Boolean = AddressPattern.matches(address.trim()) && CodePattern.matches(code.trim())

  fun errorText(error: MobileError): LemonadeCodeString = when (error) {
    is MobileError.ToolMissing -> when (error.tool) {
      MobileTool.Adb -> Res.string.pair_error_adb_missing
      MobileTool.Libimobiledevice -> Res.string.pair_error_libimobiledevice_missing
    }.asLemonadeCodeString()
    MobileError.WrongCode -> Res.string.pair_error_wrong_code.asLemonadeCodeString()
    MobileError.NotPaired -> Res.string.pair_error_not_paired.asLemonadeCodeString()
    MobileError.DiscoveryUnavailable -> Res.string.pair_error_discovery.asLemonadeCodeString()
    MobileError.TimedOut -> Res.string.pair_error_timed_out.asLemonadeCodeString()
    MobileError.DeviceGone -> Res.string.pair_error_gone.asLemonadeCodeString()
    is MobileError.CommandFailed -> error.message.asLemonadeCodeString()
  }

  private fun nearby(device: NearbyDevice): NearbyDeviceModel {
    val host = device.address.substringBeforeLast(':')
    return NearbyDeviceModel(
      address = device.address,
      platform = device.platform.toModel(),
      name = device.name,
      detail = when (device.kind) {
        NearbyKind.Connectable -> Res.string.nearby_connectable.asLemonadeCodeString(host)
        NearbyKind.AwaitingCode -> Res.string.nearby_awaiting_code.asLemonadeCodeString(host)
        NearbyKind.Trusted -> Res.string.nearby_trusted.asLemonadeCodeString()
      },
      action = (if (device.kind == NearbyKind.Trusted) Res.string.pair_connect else Res.string.pair_action).asLemonadeCodeString(),
    )
  }

  private fun unavailable(reason: ScreenUnavailable): DeviceScreenModel.Unavailable = when (reason) {
    ScreenUnavailable.CableOnly -> DeviceScreenModel.Unavailable(Res.string.screen_cable_only_title.asLemonadeCodeString(), Res.string.screen_cable_only_detail.asLemonadeCodeString())
    ScreenUnavailable.NotSupported -> DeviceScreenModel.Unavailable(Res.string.screen_not_supported_title.asLemonadeCodeString(), Res.string.screen_not_supported_detail.asLemonadeCodeString())
    ScreenUnavailable.Failed -> DeviceScreenModel.Unavailable(Res.string.screen_failed_title.asLemonadeCodeString(), Res.string.screen_failed_detail.asLemonadeCodeString())
  }

  private fun version(device: MobileDevice): LemonadeCodeString? = device.osVersion?.let {
    (if (device.platform == MobilePlatform.Android) Res.string.device_android_version else Res.string.device_ios_version).asLemonadeCodeString(it)
  }

  private fun connection(connection: DeviceConnection): LemonadeCodeString = when (connection) {
    DeviceConnection.Usb -> Res.string.device_usb
    DeviceConnection.Wifi -> Res.string.device_wifi
  }.asLemonadeCodeString()

  /** "A · B" or "A · B · C"; parts beyond three are joined two by two. */
  private fun joined(parts: List<LemonadeCodeString>): LemonadeCodeString = when (parts.size) {
    0 -> "".asLemonadeCodeString()
    1 -> parts[0]
    2 -> Res.string.detail_two.asLemonadeCodeString(parts[0], parts[1])
    3 -> Res.string.detail_three.asLemonadeCodeString(parts[0], parts[1], parts[2])
    else -> Res.string.detail_two.asLemonadeCodeString(joined(parts.take(3)), joined(parts.drop(3)))
  }

  private fun LogLevel.passes(filter: LogFilter): Boolean = when (filter) {
    LogFilter.All -> true
    LogFilter.Warnings -> this == LogLevel.Warning || isError
    LogFilter.Errors -> isError
  }

  private val LogLevel.isError: Boolean get() = this == LogLevel.Error || this == LogLevel.Fatal

  private fun DeviceLogLine.toModel(): LogLineModel = LogLineModel(
    key = sequence,
    time = time,
    level = when (level) {
      LogLevel.Verbose -> LogLevelModel.Verbose
      LogLevel.Debug -> LogLevelModel.Debug
      LogLevel.Info -> LogLevelModel.Info
      LogLevel.Warning -> LogLevelModel.Warning
      LogLevel.Error -> LogLevelModel.Error
      LogLevel.Fatal -> LogLevelModel.Fatal
    },
    tag = tag,
    message = message,
  )

  fun MobilePlatform.toModel(): MobilePlatformModel = when (this) {
    MobilePlatform.Android -> MobilePlatformModel.Android
    MobilePlatform.Ios -> MobilePlatformModel.Ios
  }

  fun MobilePlatformModel.toDomain(): MobilePlatform = when (this) {
    MobilePlatformModel.Android -> MobilePlatform.Android
    MobilePlatformModel.Ios -> MobilePlatform.Ios
  }

  private fun DeviceConnection.toModel(): DeviceConnectionModel = when (this) {
    DeviceConnection.Usb -> DeviceConnectionModel.Usb
    DeviceConnection.Wifi -> DeviceConnectionModel.Wifi
  }

  private fun QrMatrix.toModel(): QrCodeModel = QrCodeModel(size, List(size * size) { isDark(it % size, it / size) })

  /** `192.168.1.57:37099`, as Wireless debugging shows it. */
  private val AddressPattern = Regex("^\\d{1,3}(\\.\\d{1,3}){3}:\\d{2,5}$")
  private val CodePattern = Regex("^\\d{6}$")
}

private fun picture(picture: ScreenPicture): ScreenPictureModel = when (picture) {
  is ScreenPicture.Encoded -> ScreenPictureModel.Encoded(picture.bytes)
  is ScreenPicture.Bgra -> ScreenPictureModel.Bgra(picture.pixels, picture.width, picture.height, picture.rowBytes)
}
