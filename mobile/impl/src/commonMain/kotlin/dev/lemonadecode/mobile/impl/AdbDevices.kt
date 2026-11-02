package dev.lemonadecode.mobile.impl

import dev.lemonadecode.domain.mobile.DeviceAvailability
import dev.lemonadecode.domain.mobile.DeviceButton
import dev.lemonadecode.domain.mobile.DeviceCapability
import dev.lemonadecode.domain.mobile.DeviceConnection
import dev.lemonadecode.domain.mobile.DeviceId
import dev.lemonadecode.domain.mobile.DeviceKey
import dev.lemonadecode.domain.mobile.MobileDevice
import dev.lemonadecode.domain.mobile.MobileError
import dev.lemonadecode.domain.mobile.MobilePlatform
import dev.lemonadecode.domain.mobile.NearbyDevice
import dev.lemonadecode.domain.mobile.NearbyKind
import dev.lemonadecode.domain.mobile.QrPairing
import dev.lemonadecode.domain.mobile.ScreenUnavailable
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.core.flatMap
import dev.lemonadecode.platform.core.fold
import dev.lemonadecode.platform.core.getOrElse
import dev.lemonadecode.platform.core.getOrNull
import dev.lemonadecode.platform.core.onFailure
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Android devices through adb: follows `adb devices -l`, captures screens, sends input, reads
 * logcat, and pairs and connects over Wi‑Fi. Every `adb` parameter is the tool's path.
 */
internal class AdbDevices(
  private val commands: DeviceCommands,
  private val files: DeviceFiles,
  private val qr: QrEncoder,
  private val random: Random,
  /** Every device; this writes the Android ones, ahead of the rest. */
  private val devices: MutableStateFlow<List<MobileDevice>>,
) {
  private val wake = Channel<Unit>(Channel.CONFLATED)

  /** Read once per connection, by serial. Only [follow] uses it. */
  private val infos = mutableMapOf<String, AndroidInfo>()

  /** `ip:port` of devices adb reached through mdns, by serial. Only [follow] uses it. */
  private val mdnsAddresses = mutableMapOf<String, String>()

  /** Device names by `ro.serialno`, to name what wireless debugging finds. */
  private val namesBySerialNumber = MutableStateFlow<Map<String, String>>(emptyMap())

  /** Follows the connected devices until cancelled, then forgets them. */
  suspend fun follow(adb: String) {
    try {
      while (true) {
        poll(adb)
        val _ = withTimeoutOrNull(PollInterval) { wake.receive() }
      }
    } finally {
      infos.clear()
      mdnsAddresses.clear()
      publish(emptyList())
    }
  }

  /** Looks at the devices now rather than at the next poll. */
  fun refresh() {
    val _ = wake.trySend(Unit)
  }

  private suspend fun poll(adb: String) {
    val output = commands.run(listOf(adb, "devices", "-l"), DevicesTimeout).getOrNull()?.takeIf { it.isSuccess } ?: return
    val entries = parseAdbDevices(output.stdout)
    val ready = entries.filter { it.availability == DeviceAvailability.Ready }.map { it.serial }.toSet()
    val _ = infos.keys.retainAll(ready)
    val _ = mdnsAddresses.keys.retainAll(entries.map { it.serial }.toSet())
    if (entries.any { mdnsInstanceOf(it.serial) != null && it.serial !in mdnsAddresses }) resolveMdnsSerials(adb, entries)
    for (serial in ready) {
      if (serial !in infos) readInfo(adb, serial)?.let { infos[serial] = it }
    }
    val named = infos.values.mapNotNull { info -> info.serialNumber?.let { serial -> info.name?.let { serial to it } } }
    if (named.isNotEmpty()) namesBySerialNumber.update { it + named }
    publish(entries.map { it.toDevice() })
  }

  private fun publish(android: List<MobileDevice>) = devices.update { all -> android + all.filter { it.platform != MobilePlatform.Android } }

  private suspend fun readInfo(adb: String, serial: String): AndroidInfo? = commands.run(listOf(adb, "-s", serial, "shell", AndroidInfoScript))
    .getOrNull()
    ?.takeIf { it.isSuccess }
    ?.let { parseAndroidInfo(it.stdout) }

  private suspend fun resolveMdnsSerials(adb: String, entries: List<AdbEntry>) {
    val services = mdnsServices(adb)
    for (entry in entries) {
      val instance = mdnsInstanceOf(entry.serial) ?: continue
      services.firstOrNull { it.kind == MdnsKind.Connect && it.instance == instance }?.let { mdnsAddresses[entry.serial] = it.address }
    }
  }

  private fun AdbEntry.toDevice(): MobileDevice {
    val info = infos[serial]
    return MobileDevice(
      id = DeviceId(serial),
      platform = MobilePlatform.Android,
      name = info?.name ?: model ?: serial,
      osVersion = info?.osVersion,
      connection = connection,
      address = mdnsAddresses[serial] ?: serial,
      availability = availability,
      screenWidth = info?.screen?.first,
      screenHeight = info?.screen?.second,
      capabilities = when {
        availability != DeviceAvailability.Ready -> emptySet()
        connection == DeviceConnection.Wifi -> Capabilities + DeviceCapability.Disconnect
        else -> Capabilities
      },
    )
  }

  suspend fun capture(adb: String, device: MobileDevice): Capture {
    val output = commands.runForBytes(listOf(adb, "-s", device.id.value, "exec-out", "screencap", "-p"), CaptureTimeout)
      .getOrElse { return Capture.Unavailable(ScreenUnavailable.Failed, it.message.orEmpty()) }
    val png = readPng(output.stdout)
    return if (output.isSuccess && png != null) {
      Capture.Picture(png)
    } else {
      Capture.Unavailable(ScreenUnavailable.Failed, output.stderr.trim().ifEmpty { "screencap gave no picture" })
    }
  }

  suspend fun screenshot(adb: String, device: MobileDevice): LemonadeCodeResult<String, MobileError> = when (val shot = capture(adb, device)) {
    is Capture.Picture -> files.save(device.name, shot.png.bytes)
    is Capture.Unavailable -> LemonadeCodeResult.failure(MobileError.CommandFailed(shot.message))
  }

  suspend fun press(adb: String, device: MobileDevice, button: DeviceButton): LemonadeCodeResult<Unit, MobileError> = shell(adb, device, "input", "keyevent", "${button.keyCode}")

  suspend fun key(adb: String, device: MobileDevice, key: DeviceKey): LemonadeCodeResult<Unit, MobileError> = shell(adb, device, "input", "keyevent", "${key.keyCode}")

  suspend fun tap(adb: String, device: MobileDevice, x: Int, y: Int): LemonadeCodeResult<Unit, MobileError> = shell(adb, device, "input", "tap", "$x", "$y")

  suspend fun swipe(
    adb: String,
    device: MobileDevice,
    from: Pair<Int, Int>,
    to: Pair<Int, Int>,
    durationMillis: Long,
  ): LemonadeCodeResult<Unit, MobileError> {
    val duration = durationMillis.coerceIn(0, MaxSwipeMillis)
    val points = listOf(from.first, from.second, to.first, to.second).map { "$it" }
    return shell(adb, device, "input", "swipe", *points.toTypedArray(), "$duration", timeout = CommandTimeout + duration.milliseconds)
  }

  /** Types [text]; each line break becomes Enter, since `input text` can't carry one. */
  suspend fun type(adb: String, device: MobileDevice, text: String): LemonadeCodeResult<Unit, MobileError> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    lines.forEachIndexed { index, line ->
      if (line.isNotEmpty()) shell(adb, device, "input", "text", inputTextArgument(line)).onFailure { return LemonadeCodeResult.failure(it) }
      if (index < lines.lastIndex) shell(adb, device, "input", "keyevent", "${DeviceKey.Enter.keyCode}").onFailure { return LemonadeCodeResult.failure(it) }
    }
    return LemonadeCodeResult.success(Unit)
  }

  /**
   * Turns off auto-rotate and turns the screen the other way: to portrait when [latestFrame] is
   * wide, else to landscape. Without a frame it goes by the rotation set now.
   */
  suspend fun rotate(adb: String, device: MobileDevice, latestFrame: Pair<Int, Int>?): LemonadeCodeResult<Unit, MobileError> {
    val landscape = latestFrame?.let { (width, height) -> width > height } ?: run {
      val rotation = commands.run(listOf(adb, "-s", device.id.value, "shell", "settings", "get", "system", "user_rotation")).getOrNull()
      (rotation?.stdout?.trim()?.toIntOrNull() ?: 0) % 2 == 1
    }
    val target = if (landscape) 0 else 1
    return shell(adb, device, "settings put system accelerometer_rotation 0 && settings put system user_rotation $target")
  }

  /**
   * Reads logcat (the last [LogBacklog] lines, then new ones) into [sink], keeping the lines of the
   * app in front when they arrive. Which app that is is looked at again every few seconds.
   */
  suspend fun readLogs(adb: String, device: MobileDevice, sink: LogSink): MobileError? = coroutineScope {
    val serial = device.id.value
    sink.setSource(AppInFront.Unknown.source)
    val front = MutableStateFlow(appInFront(adb, serial) ?: AppInFront.Unknown)
    sink.setSource(front.value.source)
    val refresher = launch {
      while (true) {
        delay(ForegroundRefresh)
        appInFront(adb, serial)?.let { front.value = it }
        sink.setSource(front.value.source)
      }
    }
    try {
      val parser = LogcatParser()
      commands.lines(listOf(adb, "-s", serial, "logcat", "-v", "threadtime", "-T", "$LogBacklog")).batched().collect { batch ->
        val app = front.value
        sink.append(batch.mapNotNull(parser::parse).filter { app.pids == null || it.pid in app.pids }.map { it.entry })
      }
      null
    } finally {
      refresher.cancel()
    }
  }

  /** The app in front and its processes; null when the device couldn't be asked. */
  private suspend fun appInFront(adb: String, serial: String): AppInFront? {
    val activities = shellOutput(adb, serial, "dumpsys activity activities | grep ResumedActivity") ?: return null
    val packageName = parseForegroundPackage(activities)
      ?: parseForegroundPackage(shellOutput(adb, serial, "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'").orEmpty())
      ?: return AppInFront.Unknown
    // The name comes from the device's own output and is checked to be a package name, so it's safe on its shell.
    val pids = parsePids(shellOutput(adb, serial, "pidof", packageName).orEmpty())
    return if (pids.isEmpty()) AppInFront.Unknown else AppInFront(packageName, pids)
  }

  /** [packageName]'s processes; with no package, every line is kept. */
  private data class AppInFront(val packageName: String?, val pids: Set<Int>?) {
    val source: String get() = packageName?.let { "logcat · $it" } ?: "logcat"

    companion object {
      val Unknown = AppInFront(null, null)
    }
  }

  /** Android devices advertising wireless debugging, looked for while collected. */
  fun nearby(adb: String): Flow<List<NearbyDevice>> = flow {
    while (!mdnsAvailable(adb)) {
      emit(emptyList())
      delay(MdnsRecheck)
    }
    while (true) {
      emit(nearbyFrom(mdnsServices(adb)))
      delay(PollInterval)
    }
  }.distinctUntilChanged()

  /** [services] that aren't connected already, nor this app's own QR pairing. */
  private fun nearbyFrom(services: List<MdnsService>): List<NearbyDevice> {
    val connected = devices.value.filter { it.platform == MobilePlatform.Android && it.connection == DeviceConnection.Wifi }
    val hosts = connected.map { hostOf(it.address) }.toSet()
    val instances = connected.mapNotNull { mdnsInstanceOf(it.id.value) }.toSet()
    val names = namesBySerialNumber.value
    return services
      .filter { it.kind != MdnsKind.Other && hostOf(it.address) !in hosts && it.instance !in instances }
      .filterNot { service -> QrServicePrefixes.any { service.instance.startsWith(it) } }
      .distinctBy { it.instance to it.kind }
      .map { service ->
        NearbyDevice(
          platform = MobilePlatform.Android,
          name = serialInInstance(service.instance)?.let(names::get) ?: service.instance,
          address = service.address,
          kind = if (service.kind == MdnsKind.Connect) NearbyKind.Connectable else NearbyKind.AwaitingCode,
        )
      }
  }

  suspend fun connect(adb: String, nearby: NearbyDevice): LemonadeCodeResult<MobileDevice, MobileError> {
    if (nearby.kind == NearbyKind.AwaitingCode) return LemonadeCodeResult.failure(MobileError.NotPaired)
    val output = commands.run(listOf(adb, "connect", nearby.address), ConnectTimeout).getOrElse { return LemonadeCodeResult.failure(it.toCommandFailed()) }
    val text = output.text
    val lower = text.lowercase()
    return when {
      connectSucceeded(text) -> awaitReady(hostOf(nearby.address), instance = null)
      "failed to authenticate" in lower || "failed to connect" in lower -> LemonadeCodeResult.failure(MobileError.NotPaired)
      else -> LemonadeCodeResult.failure(MobileError.CommandFailed(text))
    }
  }

  private suspend fun awaitReady(host: String, instance: String?): LemonadeCodeResult<MobileDevice, MobileError> {
    refresh()
    val device = withTimeoutOrNull(ReadyTimeout) {
      devices.first { list -> list.readyAt(host, instance) != null }.readyAt(host, instance)
    }
    return device?.let { LemonadeCodeResult.success(it) } ?: LemonadeCodeResult.failure(MobileError.TimedOut)
  }

  /** The Android device over Wi‑Fi at [host], or adb's mdns name [instance], once it's ready. */
  private fun List<MobileDevice>.readyAt(host: String, instance: String?): MobileDevice? = firstOrNull { device ->
    device.platform == MobilePlatform.Android &&
      device.connection == DeviceConnection.Wifi &&
      device.availability == DeviceAvailability.Ready &&
      (hostOf(device.address) == host || (instance != null && mdnsInstanceOf(device.id.value) == instance))
  }

  suspend fun pairWithCode(adb: String, address: String, code: String): LemonadeCodeResult<MobileDevice, MobileError> {
    val target = address.trim()
    val password = code.filterNot(Char::isWhitespace)
    if (!isHostAndPort(target)) return LemonadeCodeResult.failure(MobileError.CommandFailed("$target isn't an address and port"))
    if (password.isEmpty() || !password.all(Char::isLetterOrDigit)) return LemonadeCodeResult.failure(MobileError.WrongCode)
    return pair(adb, target, password).flatMap { guid -> connectAfterPairing(adb, hostOf(target), guid) }
  }

  /** Runs `adb pair`; returns the device's guid, which names its connect service. */
  private suspend fun pair(adb: String, address: String, password: String): LemonadeCodeResult<String?, MobileError> {
    val output = commands.run(listOf(adb, "pair", address, password), PairTimeout).getOrElse { return LemonadeCodeResult.failure(it.toCommandFailed()) }
    val text = output.text
    val lower = text.lowercase()
    return when {
      "successfully paired" in lower -> LemonadeCodeResult.success(pairedGuid(text))
      "wrong password" in lower || "failed to pair" in lower -> LemonadeCodeResult.failure(MobileError.WrongCode)
      else -> LemonadeCodeResult.failure(MobileError.CommandFailed(text))
    }
  }

  /**
   * After pairing, finds the device's connect service at [host] (or named [guid]) and connects to
   * it. adb often connects by itself, so the device list is watched too.
   */
  private suspend fun connectAfterPairing(adb: String, host: String, guid: String?): LemonadeCodeResult<MobileDevice, MobileError> {
    val device = withTimeoutOrNull(ConnectAfterPairingTimeout) { connectWhenAdvertised(adb, host, guid) }
    return device?.let { LemonadeCodeResult.success(it) } ?: LemonadeCodeResult.failure(MobileError.TimedOut)
  }

  private suspend fun connectWhenAdvertised(adb: String, host: String, guid: String?): MobileDevice {
    var round = 0
    var attempted = -ConnectAttemptRounds
    while (true) {
      devices.value.readyAt(host, guid)?.let { return it }
      if (round - attempted >= ConnectAttemptRounds) {
        val service = mdnsServices(adb).firstOrNull { it.kind == MdnsKind.Connect && (hostOf(it.address) == host || it.instance == guid) }
        if (service != null) {
          attempted = round
          val _ = commands.run(listOf(adb, "connect", service.address), ConnectTimeout)
        }
      }
      refresh()
      delay(PairingPollInterval)
      round++
    }
  }

  /**
   * Shows a QR code for Android's "Pair device with QR code", waits for the phone to advertise
   * the pairing service it names, then pairs and connects.
   */
  fun pairWithQr(adb: String): Flow<QrPairing> = flow {
    if (!mdnsAvailable(adb)) return@flow emit(QrPairing.Failed(MobileError.DiscoveryUnavailable))
    val name = QrServicePrefixes.first() + randomText(6)
    val password = randomText(12)
    val payload = "WIFI:T:ADB;S:$name;P:$password;;"
    val code = runCatching { qr.encode(payload) }.getOrElse { return@flow emit(QrPairing.Failed(it.toCommandFailed())) }
    emit(QrPairing.Showing(payload, code))
    val service = withTimeoutOrNull(QrTimeout) { awaitPairingService(adb, name) } ?: return@flow emit(QrPairing.Failed(MobileError.TimedOut))
    emit(QrPairing.Pairing)
    val result = pair(adb, service.address, password).flatMap { guid -> connectAfterPairing(adb, hostOf(service.address), guid) }
    emit(result.fold({ QrPairing.Paired(it) }, { QrPairing.Failed(it) }))
  }

  private suspend fun awaitPairingService(adb: String, name: String): MdnsService {
    while (true) {
      mdnsServices(adb).firstOrNull { it.kind == MdnsKind.Pairing && it.instance.equals(name, ignoreCase = true) }?.let { return it }
      delay(PairingPollInterval)
    }
  }

  private fun randomText(length: Int): String = (1..length).map { QrAlphabet[random.nextInt(QrAlphabet.length)] }.joinToString("")

  suspend fun disconnect(adb: String, device: MobileDevice): LemonadeCodeResult<Unit, MobileError> {
    val output = commands.run(listOf(adb, "disconnect", device.id.value)).getOrElse { return LemonadeCodeResult.failure(it.toCommandFailed()) }
    refresh()
    val text = output.text
    return if (output.isSuccess && !text.startsWith("error")) LemonadeCodeResult.success(Unit) else LemonadeCodeResult.failure(MobileError.CommandFailed(text))
  }

  /** Whether this adb can look for devices on the network. */
  private suspend fun mdnsAvailable(adb: String): Boolean {
    val output = commands.run(listOf(adb, "mdns", "check"), MdnsTimeout).getOrNull() ?: return false
    val text = output.text.lowercase()
    return output.isSuccess && "unavailable" !in text && "error" !in text
  }

  private suspend fun mdnsServices(adb: String): List<MdnsService> = commands.run(listOf(adb, "mdns", "services"), MdnsTimeout)
    .getOrNull()
    ?.takeIf { it.isSuccess }
    ?.let { parseMdnsServices(it.stdout) }
    .orEmpty()

  private suspend fun shell(adb: String, device: MobileDevice, vararg arguments: String, timeout: Duration = CommandTimeout): LemonadeCodeResult<Unit, MobileError> = commands.run(listOf(adb, "-s", device.id.value, "shell", *arguments), timeout).toUnitResult()

  private suspend fun shellOutput(adb: String, serial: String, vararg arguments: String): String? = commands.run(listOf(adb, "-s", serial, "shell", *arguments))
    .getOrNull()
    ?.stdout

  private companion object {
    val Capabilities = setOf(
      DeviceCapability.Mirror,
      DeviceCapability.Touch,
      DeviceCapability.Keys,
      DeviceCapability.Rotate,
      DeviceCapability.Screenshot,
      DeviceCapability.Logs,
    )

    val PollInterval = 2.seconds
    val DevicesTimeout = 10.seconds
    val CaptureTimeout = 10.seconds
    val MdnsTimeout = 5.seconds
    val MdnsRecheck = 10.seconds
    val ConnectTimeout = 15.seconds
    val PairTimeout = 20.seconds
    val ReadyTimeout = 10.seconds
    val ConnectAfterPairingTimeout = 15.seconds
    val PairingPollInterval = 1.seconds
    val QrTimeout = 3.minutes
    val ForegroundRefresh = 3.seconds

    /** Rounds of [PairingPollInterval] between two `adb connect` tries after pairing. */
    const val ConnectAttemptRounds = 3
    const val MaxSwipeMillis = 60_000L
    const val LogBacklog = 300

    /** Service names this app's QR codes use, and Android Studio's. */
    val QrServicePrefixes = listOf("lemonade-", "studio-")
    const val QrAlphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
  }
}
