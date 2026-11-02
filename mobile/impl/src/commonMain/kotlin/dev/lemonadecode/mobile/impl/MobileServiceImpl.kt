package dev.lemonadecode.mobile.impl

import dev.lemonadecode.domain.mobile.CablePairing
import dev.lemonadecode.domain.mobile.DeviceAvailability
import dev.lemonadecode.domain.mobile.DeviceButton
import dev.lemonadecode.domain.mobile.DeviceId
import dev.lemonadecode.domain.mobile.DeviceKey
import dev.lemonadecode.domain.mobile.DeviceLogs
import dev.lemonadecode.domain.mobile.DeviceScreen
import dev.lemonadecode.domain.mobile.MobileDevice
import dev.lemonadecode.domain.mobile.MobileError
import dev.lemonadecode.domain.mobile.MobilePlatform
import dev.lemonadecode.domain.mobile.MobileTool
import dev.lemonadecode.domain.mobile.MobileTools
import dev.lemonadecode.domain.mobile.NearbyDevice
import dev.lemonadecode.domain.mobile.QrPairing
import dev.lemonadecode.domain.mobile.ScreenUnavailable
import dev.lemonadecode.mobile.api.MobileService
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.core.getOrElse
import dev.lemonadecode.platform.core.safeSuspendRunCatching
import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.settings.api.SettingsService
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The Mobile plugin. While it's on in settings, it looks for the tools, follows the devices adb and
 * libimobiledevice list, and runs what's asked of them; while it's off, nothing runs and every
 * call fails with [MobileError.ToolMissing] or [MobileError.DeviceGone]. Turning it off ends a
 * pairing in progress the same way.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
internal class MobileServiceImpl(
  private val settings: SettingsService,
  private val finder: MobileToolFinder,
  commands: DeviceCommands,
  files: DeviceFiles,
  qr: QrEncoder,
  scope: CoroutineScope,
  private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
  random: Random = Random.Default,
) : MobileService {
  private val mutableTools = MutableStateFlow<MobileTools?>(null)
  override val tools: StateFlow<MobileTools?> = mutableTools.asStateFlow()

  private val mutableDevices = MutableStateFlow<List<MobileDevice>>(emptyList())
  override val devices: StateFlow<List<MobileDevice>> = mutableDevices.asStateFlow()

  private val android = AdbDevices(commands, files, qr, random, mutableDevices)
  private val ios = IosDevices(commands, finder, files, mutableDevices)
  private val logBook = DeviceLogBook(scope, isConnected = { device(it) != null }, read = ::readLogs)

  /** The latest frame's size per device, to know which way to turn it. */
  private val frameSizes = MutableStateFlow<Map<DeviceId, Pair<Int, Int>>>(emptyMap())

  private val isOn: Boolean get() = settings.settings.value.mobilePlugin

  init {
    scope.launch {
      settings.settings.map { it.mobilePlugin }.distinctUntilChanged().collectLatest { on -> if (on) follow() }
    }
    scope.launch { mutableDevices.collect { logBook.retain() } }
  }

  /** Looks for the tools, again every so often while one is missing, and follows each platform whose tool is there. */
  private suspend fun follow(): Unit = coroutineScope {
    var found = findTools()
    launch {
      while (found.adb == null || found.libimobiledevice == null) {
        delay(ToolRecheck)
        found = findTools()
      }
    }
    launch { mutableTools.map { it?.adb }.distinctUntilChanged().collectLatest { adb -> if (adb != null) android.follow(adb) } }
    launch { mutableTools.map { it?.libimobiledevice }.distinctUntilChanged().collectLatest { folder -> if (folder != null) ios.follow(folder) } }
  }

  private suspend fun findTools(): MobileTools {
    val found = safeSuspendRunCatching { finder.find() }.getOrElse { MobileTools(adb = null, libimobiledevice = null) }
    mutableTools.value = found
    return found
  }

  /** The tools once they have been looked for; null while the plugin is off. */
  private suspend fun currentTools(): MobileTools? {
    if (!isOn) return null
    return withTimeoutOrNull(ToolSearchWait) {
      val (on, tools) = combine(settings.settings, mutableTools) { prefs, tools -> prefs.mobilePlugin to tools }.first { (on, tools) -> !on || tools != null }
      tools.takeIf { on }
    }
  }

  private fun device(id: DeviceId): MobileDevice? = mutableDevices.value.firstOrNull { it.id == id }

  /** Runs [android] or [ios] for the device [id] names, with its platform's tool. */
  private suspend fun <T> onDevice(
    id: DeviceId,
    android: suspend (adb: String, device: MobileDevice) -> LemonadeCodeResult<T, MobileError>,
    ios: suspend (folder: String, device: MobileDevice) -> LemonadeCodeResult<T, MobileError> = { _, _ -> LemonadeCodeResult.failure(NotOnIos) },
  ): LemonadeCodeResult<T, MobileError> {
    val device = device(id) ?: return LemonadeCodeResult.failure(MobileError.DeviceGone)
    val tools = currentTools()
    return when (device.platform) {
      MobilePlatform.Android -> {
        val adb = tools?.adb ?: return LemonadeCodeResult.failure(MobileError.ToolMissing(MobileTool.Adb))
        android(adb, device)
      }
      MobilePlatform.Ios -> {
        val folder = tools?.libimobiledevice ?: return LemonadeCodeResult.failure(MobileError.ToolMissing(MobileTool.Libimobiledevice))
        ios(folder, device)
      }
    }
  }

  override fun logs(id: DeviceId): Flow<DeviceLogs> = logBook.follow(id)

  override fun clearLogs(id: DeviceId) = logBook.clear(id)

  private suspend fun readLogs(id: DeviceId, sink: LogSink): MobileError? {
    val device = device(id) ?: return MobileError.DeviceGone
    val tools = currentTools()
    return when (device.platform) {
      MobilePlatform.Android -> {
        val adb = tools?.adb ?: return MobileError.ToolMissing(MobileTool.Adb)
        android.readLogs(adb, device, sink)
      }
      MobilePlatform.Ios -> {
        val folder = tools?.libimobiledevice ?: return MobileError.ToolMissing(MobileTool.Libimobiledevice)
        ios.readLogs(folder, device, sink)
      }
    }
  }

  override fun screen(id: DeviceId): Flow<DeviceScreen> = captureScreens(timeSource) { capture(id) }

  private suspend fun capture(id: DeviceId): Capture {
    val device = device(id)?.takeIf { it.availability == DeviceAvailability.Ready }
      ?: return Capture.Unavailable(ScreenUnavailable.Failed, "The device isn't connected")
    val tools = currentTools()
    val shot = when (device.platform) {
      MobilePlatform.Android -> tools?.adb?.let { android.capture(it, device) }
      MobilePlatform.Ios -> tools?.libimobiledevice?.let { ios.capture(it, device) }
    } ?: Capture.Unavailable(ScreenUnavailable.Failed, "The tool is missing")
    if (shot is Capture.Picture) frameSizes.update { it + (id to (shot.png.width to shot.png.height)) }
    return shot
  }

  override suspend fun press(id: DeviceId, button: DeviceButton): LemonadeCodeResult<Unit, MobileError> = onDevice(id, { adb, device -> android.press(adb, device, button) })

  override suspend fun tap(id: DeviceId, x: Int, y: Int): LemonadeCodeResult<Unit, MobileError> = onDevice(id, { adb, device -> android.tap(adb, device, x, y) })

  override suspend fun swipe(
    id: DeviceId,
    fromX: Int,
    fromY: Int,
    toX: Int,
    toY: Int,
    durationMillis: Long,
  ): LemonadeCodeResult<Unit, MobileError> = onDevice(id, { adb, device -> android.swipe(adb, device, fromX to fromY, toX to toY, durationMillis) })

  override suspend fun type(id: DeviceId, text: String): LemonadeCodeResult<Unit, MobileError> = onDevice(id, { adb, device -> android.type(adb, device, text) })

  override suspend fun key(id: DeviceId, key: DeviceKey): LemonadeCodeResult<Unit, MobileError> = onDevice(id, { adb, device -> android.key(adb, device, key) })

  override suspend fun rotate(id: DeviceId): LemonadeCodeResult<Unit, MobileError> = onDevice(id, { adb, device -> android.rotate(adb, device, frameSizes.value[id]) })

  override suspend fun screenshot(id: DeviceId): LemonadeCodeResult<String, MobileError> = onDevice(id, android::screenshot, ios::screenshot)

  @OptIn(ExperimentalCoroutinesApi::class)
  override fun nearby(platform: MobilePlatform): Flow<List<NearbyDevice>> = combine(settings.settings, mutableTools) { prefs, tools ->
    when {
      !prefs.mobilePlugin -> null
      platform == MobilePlatform.Android -> tools?.adb
      else -> tools?.libimobiledevice
    }
  }
    .distinctUntilChanged()
    .flatMapLatest { tool ->
      when {
        tool == null -> flowOf(emptyList())
        platform == MobilePlatform.Android -> android.nearby(tool)
        else -> ios.nearby(tool)
      }
    }

  override suspend fun connect(device: NearbyDevice): LemonadeCodeResult<MobileDevice, MobileError> {
    val tools = currentTools()
    return when (device.platform) {
      MobilePlatform.Android -> tools?.adb?.let { android.connect(it, device) } ?: LemonadeCodeResult.failure(MobileError.ToolMissing(MobileTool.Adb))
      MobilePlatform.Ios -> if (tools?.libimobiledevice != null) ios.connect(device) else LemonadeCodeResult.failure(MobileError.ToolMissing(MobileTool.Libimobiledevice))
    }
  }

  override suspend fun pairWithCode(address: String, code: String): LemonadeCodeResult<MobileDevice, MobileError> {
    val adb = currentTools()?.adb ?: return LemonadeCodeResult.failure(MobileError.ToolMissing(MobileTool.Adb))
    return android.pairWithCode(adb, address, code)
  }

  override fun pairWithQr(): Flow<QrPairing> {
    val missing = QrPairing.Failed(MobileError.ToolMissing(MobileTool.Adb))
    val pairing = flow {
      val adb = currentTools()?.adb ?: return@flow emit(missing)
      emitAll(android.pairWithQr(adb))
    }
    return pairing.whileOn(missing) { it is QrPairing.Paired || it is QrPairing.Failed }
  }

  override fun pairOverCable(): Flow<CablePairing> {
    val missing = CablePairing.Failed(MobileError.ToolMissing(MobileTool.Libimobiledevice))
    val pairing = flow {
      val folder = currentTools()?.libimobiledevice ?: return@flow emit(missing)
      emitAll(ios.pairOverCable(folder))
    }
    return pairing.whileOn(missing) { it is CablePairing.Paired || it is CablePairing.Failed }
  }

  /** This flow until it gives a [last] value, or [off] and the end when the plugin is turned off meanwhile. */
  @OptIn(ExperimentalCoroutinesApi::class)
  private fun <T> Flow<T>.whileOn(off: T, last: (T) -> Boolean): Flow<T> = settings.settings
    .map { it.mobilePlugin }
    .distinctUntilChanged()
    .transformLatest { on -> if (on) emitAll(this@whileOn) else emit(off) }
    .transformWhile { value ->
      emit(value)
      !last(value)
    }

  override suspend fun disconnect(id: DeviceId): LemonadeCodeResult<Unit, MobileError> = onDevice(id, android::disconnect)

  private companion object {
    /** How often the tools are looked for again while one is missing, so installing it later works. */
    val ToolRecheck = 30.seconds

    /** How long a call waits for the first search for the tools to finish. */
    val ToolSearchWait = 15.seconds

    val NotOnIos = MobileError.CommandFailed("libimobiledevice can't do this on an iPhone or iPad")
  }
}
