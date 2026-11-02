package dev.lemonadecode.workspace.uimodel

import androidx.compose.runtime.Immutable
import dev.lemonadecode.platform.localization.LemonadeCodeString

public enum class MobilePlatformModel { Android, Ios }

public enum class DeviceConnectionModel { Usb, Wifi }

/** Where the Devices panel lives: beside the session, or in a window of its own. */
public enum class DevicesWindow { Docked, Separate }

/** How a device's screen and logs share the panel. */
public enum class DevicesLayoutOption { SideBySide, Tabs }

public enum class DeviceTab { Screen, Logs }

/** The phone button in the top bar, shown while the Mobile plugin is on. */
@Immutable
public data class DevicesButtonModel(
  /** Devices connected now. */
  val count: Int,
  /** The panel is open beside the session in this window. */
  val docked: Boolean,
  /** The panel is open in its own window; the button gets a ↗ mark and brings that window forward. */
  val separate: Boolean,
)

/** The Devices panel: one step at a time, from the list to a device or through pairing. */
@Immutable
public data class DevicesPanelModel(
  val window: DevicesWindow,
  val title: LemonadeCodeString,
  val subtitle: LemonadeCodeString?,
  val canGoBack: Boolean,
  val content: DevicesContentModel,
  /** Goes up by one each time the separate window should come to the front. */
  val focusRequest: Int = 0,
)

@Immutable
public sealed interface DevicesContentModel {
  /** Connected devices, Android and iOS apart. */
  @Immutable
  public data class Devices(val groups: List<DeviceGroupModel>) : DevicesContentModel

  @Immutable
  public data class Device(
    val id: String,
    val platform: MobilePlatformModel,
    val layout: DevicesLayoutOption,
    /** The tab shown when [layout] is [DevicesLayoutOption.Tabs]. */
    val tab: DeviceTab,
    val canDisconnect: Boolean,
    val disconnectLabel: LemonadeCodeString,
    val screen: DeviceScreenModel,
    val controls: MirrorControlsModel,
    /** e.g. "1080 × 2400 · 4 fps · clicks go to the device". */
    val screenDetail: LemonadeCodeString?,
    val logs: DeviceLogsModel,
  ) : DevicesContentModel

  /** Pairing, step 1: Android or iOS. */
  @Immutable
  public data object ChoosePlatform : DevicesContentModel

  /** Pairing, step 2: how. */
  @Immutable
  public data class ChooseMethod(
    val platform: MobilePlatformModel,
    /** Devices found on this network so far. */
    val nearbyCount: Int,
  ) : DevicesContentModel

  /** Pairing, step 3: the phone scans a code shown here. */
  @Immutable
  public data class ScanQr(
    /** Null until the code is ready. */
    val qr: QrCodeModel?,
    /** The phone scanned it and pairing is under way. */
    val pairing: Boolean,
    val error: LemonadeCodeString?,
  ) : DevicesContentModel

  /** Pairing, step 3: type what the phone shows. */
  @Immutable
  public data class EnterCode(
    val address: String,
    val code: String,
    val canPair: Boolean,
    val pairing: Boolean,
    val error: LemonadeCodeString?,
  ) : DevicesContentModel

  /** Pairing, step 3: pick a device found on this network. */
  @Immutable
  public data class PickNearby(
    val platform: MobilePlatformModel,
    val devices: List<NearbyDeviceModel>,
    /** The address being connected to, while it is. */
    val connecting: String?,
    val error: LemonadeCodeString?,
  ) : DevicesContentModel

  /** Pairing, step 3 for iOS: plug the device in and trust this computer. */
  @Immutable
  public data class PlugIn(
    /** Set once a device is plugged in and asking to trust this computer. */
    val trustingDevice: String?,
    val error: LemonadeCodeString?,
  ) : DevicesContentModel

  @Immutable
  public data class Paired(
    val deviceId: String,
    val name: String,
    /** e.g. "Wi‑Fi · Android 14". */
    val detail: LemonadeCodeString,
  ) : DevicesContentModel
}

@Immutable
public data class DeviceGroupModel(
  val platform: MobilePlatformModel,
  val devices: List<DeviceRowModel>,
  /** The tool this platform needs isn't installed; says what to install instead of "None connected". */
  val missingTool: LemonadeCodeString? = null,
)

@Immutable
public data class DeviceRowModel(
  val id: String,
  val platform: MobilePlatformModel,
  val name: String,
  val connection: DeviceConnectionModel,
  /** e.g. "USB · Android 15" or "Wi‑Fi · 192.168.1.42 · Android 14". */
  val detail: LemonadeCodeString,
  /** False while the device still has to allow this computer; [detail] says so. */
  val ready: Boolean = true,
)

@Immutable
public data class NearbyDeviceModel(
  val address: String,
  val platform: MobilePlatformModel,
  val name: String,
  /** e.g. "192.168.1.57 · wireless debugging on". */
  val detail: LemonadeCodeString,
  /** "Pair" or "Connect". */
  val action: LemonadeCodeString,
)

/** A QR code's modules, [size] by [size], row by row; true is dark. */
@Immutable
public class QrCodeModel(
  public val size: Int,
  public val modules: List<Boolean>,
) {
  public fun isDark(x: Int, y: Int): Boolean = modules[y * size + x]
}

@Immutable
public sealed interface DeviceScreenModel {
  @Immutable
  public data object Loading : DeviceScreenModel

  /** The latest capture, a PNG of [width] by [height] pixels. */
  @Immutable
  public class Frame(
    public val png: ByteArray,
    public val width: Int,
    public val height: Int,
  ) : DeviceScreenModel

  /** No picture: [title] and [detail] say why, e.g. "Plug in to see the screen". */
  @Immutable
  public data class Unavailable(
    val title: LemonadeCodeString,
    val detail: LemonadeCodeString,
  ) : DeviceScreenModel
}

/** The buttons under the mirrored screen; only what the device supports is there. */
@Immutable
public data class MirrorControlsModel(
  val back: Boolean = false,
  val home: Boolean = false,
  val rotate: Boolean = false,
  /** The screen is on its side. */
  val landscape: Boolean = false,
  val screenshot: Boolean = false,
  val keyboard: Boolean = false,
  /** Typing goes to the device. */
  val keyboardOn: Boolean = false,
  /** Clicks and drags on the screen reach the device. */
  val touch: Boolean = false,
)

public enum class LogFilter { All, Warnings, Errors }

public enum class LogLevelModel(public val letter: String) {
  Verbose("V"),
  Debug("D"),
  Info("I"),
  Warning("W"),
  Error("E"),
  Fatal("F"),
}

@Immutable
public data class DeviceLogsModel(
  val filter: LogFilter,
  /** Warnings and errors among the lines the search keeps. */
  val warningCount: Int,
  val errorCount: Int,
  val searchOpen: Boolean,
  val query: String,
  /** The lines shown, oldest first. */
  val lines: List<LogLineModel>,
  /** Shown instead of lines when there are none, e.g. "No lines match.". */
  val emptyText: LemonadeCodeString?,
  val paused: Boolean,
  /** e.g. `logcat · com.teya.storefront`. */
  val source: String,
  /** e.g. "Send 2 errors to claude"; null when no errors are shown. */
  val sendErrorsLabel: LemonadeCodeString?,
)

@Immutable
public data class LogLineModel(
  val key: Long,
  val time: String,
  val level: LogLevelModel,
  val tag: String,
  val message: String,
)
