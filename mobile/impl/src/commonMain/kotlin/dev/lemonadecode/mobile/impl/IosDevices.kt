package dev.lemonadecode.mobile.impl

import dev.lemonadecode.domain.mobile.CablePairing
import dev.lemonadecode.domain.mobile.DeviceAvailability
import dev.lemonadecode.domain.mobile.DeviceCapability
import dev.lemonadecode.domain.mobile.DeviceConnection
import dev.lemonadecode.domain.mobile.DeviceId
import dev.lemonadecode.domain.mobile.MobileDevice
import dev.lemonadecode.domain.mobile.MobileError
import dev.lemonadecode.domain.mobile.MobilePlatform
import dev.lemonadecode.domain.mobile.NearbyDevice
import dev.lemonadecode.domain.mobile.NearbyKind
import dev.lemonadecode.domain.mobile.ScreenUnavailable
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.core.fold
import dev.lemonadecode.platform.core.getOrElse
import dev.lemonadecode.platform.core.getOrNull
import dev.lemonadecode.platform.core.safeSuspendRunCatching
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * iPhones and iPads through libimobiledevice: follows `idevice_id`, captures screens over a cable,
 * reads the system log and pairs over a cable. Every `folder` parameter is where the tools are.
 */
internal class IosDevices(
  private val commands: DeviceCommands,
  private val finder: MobileToolFinder,
  private val files: DeviceFiles,
  /** Every device; this writes the iOS ones, after the rest. */
  private val devices: MutableStateFlow<List<MobileDevice>>,
) {
  private val wake = Channel<Unit>(Channel.CONFLATED)

  /** Read once per connection from devices that trust this computer, by UDID. Only [follow] uses it. */
  private val infos = mutableMapOf<String, IosInfo>()

  /** Names by UDID, including those read before a device trusts this computer. */
  private val names = MutableStateFlow<Map<String, String>>(emptyMap())

  /** UDIDs whose name couldn't be read without trust, so it isn't asked again. Only [follow] uses it. */
  private val unnamed = mutableSetOf<String>()

  /** Follows the connected devices until cancelled, then forgets them. */
  suspend fun follow(folder: String) {
    try {
      while (true) {
        poll(folder)
        val _ = withTimeoutOrNull(PollInterval) { wake.receive() }
      }
    } finally {
      infos.clear()
      unnamed.clear()
      publish(emptyList())
    }
  }

  /** Looks at the devices now rather than at the next poll. */
  fun refresh() {
    val _ = wake.trySend(Unit)
  }

  private suspend fun poll(folder: String) {
    val usb = udids(folder, "-l") ?: return
    val network = udids(folder, "-n").orEmpty().filterNot { it in usb }
    val connections = usb.associateWith { DeviceConnection.Usb } + network.associateWith { DeviceConnection.Wifi }
    val _ = infos.keys.retainAll(connections.keys)
    val _ = unnamed.retainAll(connections.keys)
    val list = connections.map { (udid, connection) ->
      val info = infos[udid] ?: readInfo(folder, udid, connection).also { if (it.availability == DeviceAvailability.Ready) infos[udid] = it }
      toDevice(udid, connection, info)
    }
    publish(list)
  }

  private fun publish(ios: List<MobileDevice>) = devices.update { all -> all.filter { it.platform != MobilePlatform.Ios } + ios }

  private data class IosInfo(
    val availability: DeviceAvailability,
    val name: String? = null,
    val osVersion: String? = null,
    /** e.g. `iPhone15,2`, the name of last resort. */
    val productType: String? = null,
  )

  private suspend fun readInfo(folder: String, udid: String, connection: DeviceConnection): IosInfo {
    val output = commands.run(listOf(tool(folder, "ideviceinfo"), "-u", udid) + network(connection)).getOrNull()
      ?: return IosInfo(DeviceAvailability.Offline, names.value[udid])
    if (output.isSuccess) {
      val values = parseIdeviceInfo(output.stdout)
      val name = values["DeviceName"]?.takeIf { it.isNotEmpty() }
      if (name != null) names.update { it + (udid to name) }
      return IosInfo(DeviceAvailability.Ready, name, values["ProductVersion"], values["ProductType"])
    }
    if (!isTrustError(output.text)) return IosInfo(DeviceAvailability.Offline, names.value[udid])
    return IosInfo(DeviceAvailability.Unauthorized, names.value[udid] ?: untrustedName(folder, udid, connection))
  }

  /** The name a device gives before it trusts this computer (`ideviceinfo -s`), asked once. */
  private suspend fun untrustedName(folder: String, udid: String, connection: DeviceConnection): String? {
    if (udid in unnamed) return null
    val name = simpleName(folder, udid, connection)
    if (name == null) unnamed += udid
    return name
  }

  private suspend fun simpleName(folder: String, udid: String, connection: DeviceConnection): String? {
    val name = commands.run(listOf(tool(folder, "ideviceinfo"), "-u", udid) + network(connection) + listOf("-s", "-k", "DeviceName"))
      .getOrNull()
      ?.takeIf { it.isSuccess }
      ?.stdout
      ?.trim()
      ?.takeIf { it.isNotEmpty() && '\n' !in it }
    if (name != null) names.update { it + (udid to name) }
    return name
  }

  private fun toDevice(udid: String, connection: DeviceConnection, info: IosInfo): MobileDevice = MobileDevice(
    id = DeviceId(udid),
    platform = MobilePlatform.Ios,
    name = info.name ?: info.productType ?: DefaultName,
    osVersion = info.osVersion,
    connection = connection,
    address = udid,
    availability = info.availability,
    capabilities = when {
      info.availability != DeviceAvailability.Ready -> emptySet()
      connection == DeviceConnection.Usb -> CableCapabilities
      else -> setOf(DeviceCapability.Logs)
    },
  )

  suspend fun capture(folder: String, device: MobileDevice): Capture {
    if (device.connection == DeviceConnection.Wifi) return Capture.Unavailable(ScreenUnavailable.CableOnly, "An iPhone or iPad shows its screen over a cable only")
    return shoot(folder, device)
  }

  suspend fun screenshot(folder: String, device: MobileDevice): LemonadeCodeResult<String, MobileError> = when (val shot = shoot(folder, device)) {
    is Capture.Picture -> files.save(device.name, shot.png.bytes)
    is Capture.Unavailable -> LemonadeCodeResult.failure(MobileError.CommandFailed(shot.message))
  }

  /**
   * One `idevicescreenshot`. A picture that isn't a PNG (older devices send TIFF), or a device
   * without its developer disk image, can't be shown.
   */
  private suspend fun shoot(folder: String, device: MobileDevice): Capture {
    val path = safeSuspendRunCatching { files.capturePath() }.getOrElse { return Capture.Unavailable(ScreenUnavailable.Failed, it.message.orEmpty()) }
    val output = commands.run(listOf(tool(folder, "idevicescreenshot"), "-u", device.id.value) + network(device.connection) + path, ScreenshotTimeout)
    val bytes = safeSuspendRunCatching { files.take(path) }.getOrNull()
    val png = bytes?.let(::readPng)
    val printed = output.fold({ it.text }, { it.message.orEmpty() })
    return when {
      png != null -> Capture.Picture(png)
      bytes != null && bytes.isNotEmpty() -> Capture.Unavailable(ScreenUnavailable.NotSupported, "idevicescreenshot didn't save a PNG")
      isScreenshotUnsupported(printed) -> Capture.Unavailable(ScreenUnavailable.NotSupported, printed)
      else -> Capture.Unavailable(ScreenUnavailable.Failed, printed.ifEmpty { "idevicescreenshot gave no picture" })
    }
  }

  /** Reads the device's system log into [sink], every process's lines. */
  suspend fun readLogs(folder: String, device: MobileDevice, sink: LogSink): MobileError? {
    sink.setSource(SyslogSource)
    val parser = SyslogParser()
    commands.lines(listOf(tool(folder, "idevicesyslog"), "-u", device.id.value) + network(device.connection)).batched().collect { batch ->
      sink.append(batch.map(parser::parse))
    }
    return null
  }

  /** Devices reachable over Wi‑Fi, which trusted this computer before. They are in the device list too. */
  fun nearby(folder: String): Flow<List<NearbyDevice>> = flow {
    while (true) {
      val udids = udids(folder, "-n").orEmpty()
      emit(udids.map { NearbyDevice(MobilePlatform.Ios, nameOf(it), it, NearbyKind.Trusted) })
      delay(PollInterval)
    }
  }.distinctUntilChanged()

  /** A trusted device on the network is connected already; this finds it. */
  fun connect(nearby: NearbyDevice): LemonadeCodeResult<MobileDevice, MobileError> = devices.value
    .firstOrNull { it.platform == MobilePlatform.Ios && it.id.value == nearby.address }
    ?.let { LemonadeCodeResult.success(it) }
    ?: LemonadeCodeResult.failure(MobileError.DeviceGone)

  /**
   * Waits for a device to be plugged in (or one that doesn't trust this computer yet), then asks
   * to pair until its user taps Trust, and ends once it's ready.
   */
  fun pairOverCable(folder: String): Flow<CablePairing> = flow {
    emit(CablePairing.WaitingForDevice)
    val device = withTimeoutOrNull(CablePairingTimeout) { pairNextDevice(folder) }
    emit(device?.let { CablePairing.Paired(it) } ?: CablePairing.Failed(MobileError.TimedOut))
  }.distinctUntilChanged()

  private suspend fun FlowCollector<CablePairing>.pairNextDevice(folder: String): MobileDevice {
    val present = udids(folder, "-l").orEmpty().toSet()
    val validated = mutableSetOf<String>()
    while (true) {
      val udid = awaitDeviceToPair(folder, present, validated)
      if (pairWhenTrusted(folder, udid)) {
        refresh()
        return devices.first { list -> list.any { it.isReady(udid) } }.first { it.isReady(udid) }
      }
      emit(CablePairing.WaitingForDevice)
    }
  }

  private fun MobileDevice.isReady(udid: String) = id.value == udid && availability == DeviceAvailability.Ready

  /** A USB device plugged in after [present] was taken, or one that isn't paired with this computer. */
  private suspend fun awaitDeviceToPair(folder: String, present: Set<String>, validated: MutableSet<String>): String {
    while (true) {
      for (udid in udids(folder, "-l").orEmpty()) {
        if (udid !in present) return udid
        if (udid in validated) continue
        if (isValidated(folder, udid)) validated += udid else return udid
      }
      delay(CablePollInterval)
    }
  }

  /** Asks to pair until the user taps Trust; false when the device is unplugged meanwhile. */
  private suspend fun FlowCollector<CablePairing>.pairWhenTrusted(folder: String, udid: String): Boolean {
    var name: String? = null
    while (true) {
      val output = commands.run(listOf(tool(folder, "idevicepair"), "-u", udid, "pair")).getOrNull()
      when (output?.let { parsePairAttempt(it.text) } ?: PairAttempt.Failed) {
        PairAttempt.Paired -> return true
        PairAttempt.NoDevice -> return false
        PairAttempt.WaitingForTrust, PairAttempt.Failed -> {
          val shown = name ?: (names.value[udid] ?: simpleName(folder, udid, DeviceConnection.Usb) ?: DefaultName).also { name = it }
          emit(CablePairing.WaitingForTrust(shown))
        }
      }
      delay(CablePollInterval)
    }
  }

  private suspend fun isValidated(folder: String, udid: String): Boolean {
    val output = commands.run(listOf(tool(folder, "idevicepair"), "-u", udid, "validate")).getOrNull() ?: return false
    return output.isSuccess && "success" in output.text.lowercase()
  }

  private fun nameOf(udid: String): String = devices.value.firstOrNull { it.id.value == udid }?.name ?: names.value[udid] ?: udid

  /** The UDIDs `idevice_id` lists with [flag]; null when it couldn't run. It fails when usbmuxd isn't running, which means no devices. */
  private suspend fun udids(folder: String, flag: String): List<String>? {
    val output = commands.run(listOf(tool(folder, "idevice_id"), flag), ListTimeout).getOrNull() ?: return null
    return if (output.isSuccess) parseUdids(output.stdout) else emptyList()
  }

  private fun tool(folder: String, name: String): String = finder.ideviceTool(folder, name)

  /** libimobiledevice reaches a device over Wi‑Fi only when told to. */
  private fun network(connection: DeviceConnection): List<String> = if (connection == DeviceConnection.Wifi) listOf("-n") else emptyList()

  private companion object {
    val CableCapabilities = setOf(DeviceCapability.Mirror, DeviceCapability.Screenshot, DeviceCapability.Logs)
    const val DefaultName = "iOS device"
    const val SyslogSource = "idevicesyslog · all processes"
    val PollInterval = 3.seconds
    val ListTimeout = 5.seconds
    val ScreenshotTimeout = 15.seconds
    val CablePollInterval = 2.seconds
    val CablePairingTimeout = 3.minutes
  }
}
