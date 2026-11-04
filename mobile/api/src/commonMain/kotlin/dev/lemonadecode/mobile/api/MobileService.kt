package dev.lemonadecode.mobile.api

import dev.lemonadecode.domain.mobile.CablePairing
import dev.lemonadecode.domain.mobile.DeviceApp
import dev.lemonadecode.domain.mobile.DeviceButton
import dev.lemonadecode.domain.mobile.DeviceCapability
import dev.lemonadecode.domain.mobile.DeviceId
import dev.lemonadecode.domain.mobile.DeviceKey
import dev.lemonadecode.domain.mobile.DeviceLogs
import dev.lemonadecode.domain.mobile.DeviceScreen
import dev.lemonadecode.domain.mobile.LogPackages
import dev.lemonadecode.domain.mobile.MobileDevice
import dev.lemonadecode.domain.mobile.MobileError
import dev.lemonadecode.domain.mobile.MobilePlatform
import dev.lemonadecode.domain.mobile.MobileTools
import dev.lemonadecode.domain.mobile.NearbyDevice
import dev.lemonadecode.domain.mobile.QrPairing
import dev.lemonadecode.platform.core.LemonadeCodeResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Mobile plugin: Android devices through `adb`, iPhones and iPads through libimobiledevice.
 * Nothing runs while the plugin is off in settings.
 */
public interface MobileService {
  /** The tools found on this computer; null until they have been looked for. */
  public val tools: StateFlow<MobileTools?>

  /** Devices connected now, Android first. Followed while the plugin is on; empty while it's off. */
  public val devices: StateFlow<List<MobileDevice>>

  /**
   * What [id] logs, read while collected and for a while after, so leaving the device and coming
   * back keeps the lines. Emits the whole list each time a line arrives.
   *
   * On a device with [DeviceCapability.LogsByApp] only [packages]' lines are kept; others keep
   * every line. A device has one log: following it for other packages than before starts it over
   * with theirs, for everyone following it.
   */
  public fun logs(id: DeviceId, packages: LogPackages = LogPackages.AppInFront): Flow<DeviceLogs>

  /**
   * The apps on [id] whose lines its log can be narrowed to: the app in front first, then the ones
   * running, then the rest, looked at again every few seconds while collected. Android lists the
   * apps installed by its user, and the one in front whichever it is; a device without
   * [DeviceCapability.LogsByApp] has none.
   */
  public fun apps(id: DeviceId): Flow<List<DeviceApp>>

  /** Forgets the lines kept for [id]; new ones keep coming. */
  public fun clearLogs(id: DeviceId)

  /** The screen of [id], captured again and again while collected. */
  public fun screen(id: DeviceId): Flow<DeviceScreen>

  public suspend fun press(id: DeviceId, button: DeviceButton): LemonadeCodeResult<Unit, MobileError>

  /** Taps at [x], [y], in pixels of the latest frame. */
  public suspend fun tap(id: DeviceId, x: Int, y: Int): LemonadeCodeResult<Unit, MobileError>

  /** Drags from one point to another, in pixels of the latest frame. */
  public suspend fun swipe(
    id: DeviceId,
    fromX: Int,
    fromY: Int,
    toX: Int,
    toY: Int,
    durationMillis: Long,
  ): LemonadeCodeResult<Unit, MobileError>

  public suspend fun type(id: DeviceId, text: String): LemonadeCodeResult<Unit, MobileError>

  public suspend fun key(id: DeviceId, key: DeviceKey): LemonadeCodeResult<Unit, MobileError>

  /** Turns the device between portrait and landscape. */
  public suspend fun rotate(id: DeviceId): LemonadeCodeResult<Unit, MobileError>

  /** Saves a picture of the screen as a PNG and returns its absolute path. */
  public suspend fun screenshot(id: DeviceId): LemonadeCodeResult<String, MobileError>

  /**
   * Devices of [platform] on this network, looked for while collected. Android devices already
   * connected are left out; iOS lists the trusted devices on Wi‑Fi, which [devices] has too.
   */
  public fun nearby(platform: MobilePlatform): Flow<List<NearbyDevice>>

  public suspend fun connect(device: NearbyDevice): LemonadeCodeResult<MobileDevice, MobileError>

  /** Pairs with an Android device showing a code, at [address] (`ip:port`), then connects to it. */
  public suspend fun pairWithCode(address: String, code: String): LemonadeCodeResult<MobileDevice, MobileError>

  /** Shows a code for an Android device to scan, then pairs and connects. Stops when no longer collected. */
  public fun pairWithQr(): Flow<QrPairing>

  /** Waits for an iPhone or iPad to be plugged in and to trust this computer. Stops when no longer collected. */
  public fun pairOverCable(): Flow<CablePairing>

  public suspend fun disconnect(id: DeviceId): LemonadeCodeResult<Unit, MobileError>
}
