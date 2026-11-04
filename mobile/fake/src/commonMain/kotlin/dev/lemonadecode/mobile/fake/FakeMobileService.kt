package dev.lemonadecode.mobile.fake

import dev.lemonadecode.domain.mobile.CablePairing
import dev.lemonadecode.domain.mobile.DeviceApp
import dev.lemonadecode.domain.mobile.DeviceButton
import dev.lemonadecode.domain.mobile.DeviceConnection
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
import dev.lemonadecode.mobile.api.MobileService
import dev.lemonadecode.platform.core.LemonadeCodeResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** A [MobileService] whose devices, logs and screens are set by the test; it records what it was asked to do. */
public class FakeMobileService(
  devices: List<MobileDevice> = emptyList(),
  tools: MobileTools? = MobileTools(adb = "/usr/bin/adb", libimobiledevice = "/usr/bin"),
) : MobileService {
  private val mutableTools = MutableStateFlow(tools)
  override val tools: StateFlow<MobileTools?> = mutableTools

  private val mutableDevices = MutableStateFlow(devices)
  override val devices: StateFlow<List<MobileDevice>> = mutableDevices

  /** Logs by device; set them before or while the panel shows them. */
  public val logs: MutableStateFlow<Map<DeviceId, DeviceLogs>> = MutableStateFlow(emptyMap())

  /** Whose lines each device's log was last followed for. */
  public val logPackages: MutableStateFlow<Map<DeviceId, LogPackages>> = MutableStateFlow(emptyMap())

  /** The apps each device lists for narrowing its log; a device left out has none. */
  public val apps: MutableStateFlow<Map<DeviceId, List<DeviceApp>>> = MutableStateFlow(emptyMap())
  public val screens: MutableStateFlow<Map<DeviceId, DeviceScreen>> = MutableStateFlow(emptyMap())
  public val nearby: MutableStateFlow<Map<MobilePlatform, List<NearbyDevice>>> = MutableStateFlow(emptyMap())
  public val qrPairing: MutableStateFlow<QrPairing?> = MutableStateFlow(null)
  public val cablePairing: MutableStateFlow<CablePairing?> = MutableStateFlow(null)

  /** What the next pairing or connection returns; null pairs with a made-up device. */
  public var nextPairResult: LemonadeCodeResult<MobileDevice, MobileError>? = null

  /** Every action, e.g. `tap pixel 10,20`, in order. */
  public val actions: MutableList<String> = mutableListOf()

  public var screenshotPath: String = "/tmp/lemonade-code/screenshot.png"

  public fun setDevices(devices: List<MobileDevice>) {
    mutableDevices.value = devices
  }

  public fun setTools(tools: MobileTools?) {
    mutableTools.value = tools
  }

  override fun logs(id: DeviceId, packages: LogPackages): Flow<DeviceLogs> = logs
    .map { it[id] ?: DeviceLogs(source = "", lines = emptyList()) }
    .onStart { logPackages.value += id to packages }

  override fun apps(id: DeviceId): Flow<List<DeviceApp>> = apps.map { it[id].orEmpty() }

  override fun clearLogs(id: DeviceId) {
    actions += "clear ${id.value}"
    logs.value = logs.value + (id to (logs.value[id]?.copy(lines = emptyList()) ?: DeviceLogs("", emptyList())))
  }

  override fun screen(id: DeviceId): Flow<DeviceScreen> = screens.map { it[id] ?: DeviceScreen.Loading }

  override suspend fun press(id: DeviceId, button: DeviceButton): LemonadeCodeResult<Unit, MobileError> = record("press ${id.value} $button")

  override suspend fun tap(id: DeviceId, x: Int, y: Int): LemonadeCodeResult<Unit, MobileError> = record("tap ${id.value} $x,$y")

  override suspend fun swipe(
    id: DeviceId,
    fromX: Int,
    fromY: Int,
    toX: Int,
    toY: Int,
    durationMillis: Long,
  ): LemonadeCodeResult<Unit, MobileError> = record("swipe ${id.value} $fromX,$fromY $toX,$toY")

  override suspend fun type(id: DeviceId, text: String): LemonadeCodeResult<Unit, MobileError> = record("type ${id.value} $text")

  override suspend fun key(id: DeviceId, key: DeviceKey): LemonadeCodeResult<Unit, MobileError> = record("key ${id.value} $key")

  override suspend fun rotate(id: DeviceId): LemonadeCodeResult<Unit, MobileError> = record("rotate ${id.value}")

  override suspend fun screenshot(id: DeviceId): LemonadeCodeResult<String, MobileError> {
    actions += "screenshot ${id.value}"
    return LemonadeCodeResult.Success(screenshotPath)
  }

  override fun nearby(platform: MobilePlatform): Flow<List<NearbyDevice>> = nearby.map { it[platform].orEmpty() }

  override suspend fun connect(device: NearbyDevice): LemonadeCodeResult<MobileDevice, MobileError> {
    actions += "connect ${device.address}"
    return paired(device.platform, device.name, device.address)
  }

  override suspend fun pairWithCode(address: String, code: String): LemonadeCodeResult<MobileDevice, MobileError> {
    actions += "pair $address $code"
    return paired(MobilePlatform.Android, "Android device", address)
  }

  override fun pairWithQr(): Flow<QrPairing> = qrPairing.map { it ?: QrPairing.Pairing }

  override fun pairOverCable(): Flow<CablePairing> = cablePairing.map { it ?: CablePairing.WaitingForDevice }

  override suspend fun disconnect(id: DeviceId): LemonadeCodeResult<Unit, MobileError> {
    actions += "disconnect ${id.value}"
    mutableDevices.value = mutableDevices.value.filterNot { it.id == id }
    return LemonadeCodeResult.Success(Unit)
  }

  private fun paired(platform: MobilePlatform, name: String, address: String): LemonadeCodeResult<MobileDevice, MobileError> {
    val result = nextPairResult ?: LemonadeCodeResult.Success(
      MobileDevice(
        id = DeviceId(address),
        platform = platform,
        name = name,
        osVersion = null,
        connection = DeviceConnection.Wifi,
        address = address,
      ),
    )
    if (result is LemonadeCodeResult.Success && mutableDevices.value.none { it.id == result.value.id }) {
      mutableDevices.value = mutableDevices.value + result.value
    }
    return result
  }

  private fun record(action: String): LemonadeCodeResult<Unit, MobileError> {
    actions += action
    return LemonadeCodeResult.Success(Unit)
  }
}
