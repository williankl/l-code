package dev.lemonadecode.domain.mobile

public enum class MobilePlatform { Android, Ios }

public enum class DeviceConnection { Usb, Wifi }

/** An adb serial (`38021FDH2000AB`, `192.168.1.42:41235`) or an iOS UDID. */
public data class DeviceId(val value: String)

public enum class DeviceAvailability {
  Ready,

  /** Android: USB debugging isn't allowed on the phone yet. iOS: the device doesn't trust this computer yet. */
  Unauthorized,

  /** Listed, but not answering. */
  Offline,
}

/** What Lemonade Code can do with a device; the panel only offers these. */
public enum class DeviceCapability {
  /** Its screen can be shown. */
  Mirror,

  /** Clicks and drags on the mirrored screen reach the device. */
  Touch,

  /** Back, Home and typed text reach the device. */
  Keys,

  /** It can be turned between portrait and landscape from here. */
  Rotate,

  /** Its screen can be saved as a picture. */
  Screenshot,

  Logs,

  /** Its logs can be narrowed to the apps picked, or to the app in front: Android. */
  LogsByApp,

  /** It can be disconnected from here: Android over Wi‑Fi. */
  Disconnect,
}

/** A phone or tablet connected to this computer, over a cable or Wi‑Fi. */
public data class MobileDevice(
  val id: DeviceId,
  val platform: MobilePlatform,
  /** e.g. `Pixel 8`, `iPhone 15 Pro`. */
  val name: String,
  /** e.g. `15` for Android 15, `18.1` for iOS 18.1; null until it has been read. */
  val osVersion: String?,
  val connection: DeviceConnection,
  /** The serial over USB or `ip:port` over Wi‑Fi on Android; the UDID, or the IP over Wi‑Fi, on iOS. */
  val address: String,
  val availability: DeviceAvailability = DeviceAvailability.Ready,
  /** The screen in pixels, portrait; null until it has been read. */
  val screenWidth: Int? = null,
  val screenHeight: Int? = null,
  val capabilities: Set<DeviceCapability> = emptySet(),
)

public enum class DeviceButton { Back, Home }

/** Keys sent to the device while the mirrored screen takes the keyboard. Text goes as text. */
public enum class DeviceKey { Enter, Backspace, ForwardDelete, Tab, Escape, Up, Down, Left, Right }

/** What the mirrored screen shows. */
public sealed interface DeviceScreen {
  public data object Loading : DeviceScreen

  /**
   * One picture of the screen. [width] and [height] are the screen's, in the device's own pixels,
   * so a device turned to landscape gives a wide frame and taps can be placed; the [picture] can
   * be smaller. [framesPerSecond] is how often frames have been arriving; null for the first.
   */
  public class Frame(
    public val picture: ScreenPicture,
    public val width: Int,
    public val height: Int,
    public val framesPerSecond: Float?,
  ) : DeviceScreen

  public data class Unavailable(val reason: ScreenUnavailable) : DeviceScreen
}

/** What a [DeviceScreen.Frame] shows. */
public sealed interface ScreenPicture {
  /** An encoded image, such as a screenshot's PNG. */
  public class Encoded(public val bytes: ByteArray) : ScreenPicture

  /** Decoded pixels, as a video gives them: 4 bytes each, blue, green, red and alpha, [rowBytes] to a row. */
  public class Bgra(
    public val pixels: ByteArray,
    public val width: Int,
    public val height: Int,
    public val rowBytes: Int,
  ) : ScreenPicture
}

public enum class ScreenUnavailable {
  /** iOS shows its screen over a cable only. */
  CableOnly,

  /** The tool can't capture this device's screen, e.g. a newer iOS without its developer image. */
  NotSupported,

  /** Capturing failed, e.g. the device went away. */
  Failed,
}

public enum class LogLevel { Verbose, Debug, Info, Warning, Error, Fatal }

public data class DeviceLogLine(
  /** Counts up by one per line from a device, so a view can mark where it paused. */
  val sequence: Long,
  /** As the device printed it, e.g. `10:14:03.120`. */
  val time: String,
  val level: LogLevel,
  val tag: String,
  val message: String,
)

/** Whose lines a device's log keeps, on a device with [DeviceCapability.LogsByApp]; others keep every line. */
public sealed interface LogPackages {
  /** The app in front, followed as it changes; every line while that can't be told. The default. */
  public data object AppInFront : LogPackages

  /** The lines of these packages' processes; with none, every line, the system's too. */
  public data class Only(val packages: Set<String>) : LogPackages

  public companion object {
    /** Every line, from every app and the system. */
    public val All: LogPackages = Only(emptySet())
  }
}

/** An app on a device, whose lines its log can be narrowed to. */
public data class DeviceApp(
  /** e.g. `com.teya.storefront`. */
  val packageName: String,
  /** One of its processes is running. */
  val running: Boolean = false,
  /** It's the app in front. */
  val inFront: Boolean = false,
)

/** The lines a device has logged since they started being read, oldest first. */
public data class DeviceLogs(
  /** What reads them and whose they are, e.g. `logcat · com.teya.storefront`, `logcat · 2 apps`, `logcat · all`. */
  val source: String,
  val lines: List<DeviceLogLine>,
  /** Set when reading stopped, e.g. the device went away. */
  val error: MobileError? = null,
)

/** A device on this network that can be paired with or connected to. */
public data class NearbyDevice(
  val platform: MobilePlatform,
  val name: String,
  /** `ip:port` for Android; the UDID for iOS. */
  val address: String,
  val kind: NearbyKind,
)

public enum class NearbyKind {
  /** Android with wireless debugging on: connects straight away if it was paired before. */
  Connectable,

  /** Android showing a pairing code: pairing needs that code. */
  AwaitingCode,

  /** iOS, trusted before and reachable over Wi‑Fi. */
  Trusted,
}

/** A QR code's modules, [size] by [size], row by row; true is dark. */
public class QrMatrix(
  public val size: Int,
  private val modules: BooleanArray,
) {
  init {
    require(modules.size == size * size) { "A $size × $size code has ${size * size} modules, not ${modules.size}" }
  }

  public fun isDark(x: Int, y: Int): Boolean = modules[y * size + x]
}

/** How pairing by QR code is going: Android's Wireless debugging › Pair device with QR code. */
public sealed interface QrPairing {
  /** Waiting for the phone to scan [qr], which encodes [payload]. */
  public data class Showing(val payload: String, val qr: QrMatrix) : QrPairing

  /** The phone scanned the code; pairing and connecting. */
  public data object Pairing : QrPairing

  public data class Paired(val device: MobileDevice) : QrPairing

  public data class Failed(val error: MobileError) : QrPairing
}

/** How pairing over a cable is going (iOS): plug in, then tap Trust on the device. */
public sealed interface CablePairing {
  public data object WaitingForDevice : CablePairing

  public data class WaitingForTrust(val name: String) : CablePairing

  public data class Paired(val device: MobileDevice) : CablePairing

  public data class Failed(val error: MobileError) : CablePairing
}

public enum class MobileTool {
  /** Android's `adb`, from the platform tools. */
  Adb,

  /** `idevice_id`, `ideviceinfo`, `idevicesyslog` and friends. */
  Libimobiledevice,
}

/** The command-line tools found on this computer. */
public data class MobileTools(
  /** Absolute path of `adb`; null when it isn't installed. */
  val adb: String?,
  /** Folder holding libimobiledevice's tools; null when they aren't installed. */
  val libimobiledevice: String?,
)

public sealed interface MobileError {
  public data class ToolMissing(val tool: MobileTool) : MobileError

  /** The pairing code was wrong or had expired. */
  public data object WrongCode : MobileError

  /** The device refused to connect because it was never paired with this computer. */
  public data object NotPaired : MobileError

  /** This adb can't discover devices on the network (`adb mdns check` failed). */
  public data object DiscoveryUnavailable : MobileError

  /** Waited too long, e.g. nobody scanned the code or tapped Trust. */
  public data object TimedOut : MobileError

  /** The device isn't connected any more. */
  public data object DeviceGone : MobileError

  /** A tool failed; [message] is what it printed. */
  public data class CommandFailed(val message: String) : MobileError
}
