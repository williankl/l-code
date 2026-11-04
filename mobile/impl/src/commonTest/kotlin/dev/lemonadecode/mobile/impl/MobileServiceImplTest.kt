package dev.lemonadecode.mobile.impl

import app.cash.turbine.test
import dev.lemonadecode.domain.mobile.CablePairing
import dev.lemonadecode.domain.mobile.DeviceApp
import dev.lemonadecode.domain.mobile.DeviceAvailability
import dev.lemonadecode.domain.mobile.DeviceButton
import dev.lemonadecode.domain.mobile.DeviceCapability
import dev.lemonadecode.domain.mobile.DeviceConnection
import dev.lemonadecode.domain.mobile.DeviceId
import dev.lemonadecode.domain.mobile.DeviceLogs
import dev.lemonadecode.domain.mobile.DeviceScreen
import dev.lemonadecode.domain.mobile.LogLevel
import dev.lemonadecode.domain.mobile.LogPackages
import dev.lemonadecode.domain.mobile.MobileDevice
import dev.lemonadecode.domain.mobile.MobileError
import dev.lemonadecode.domain.mobile.MobilePlatform
import dev.lemonadecode.domain.mobile.MobileTool
import dev.lemonadecode.domain.mobile.MobileTools
import dev.lemonadecode.domain.mobile.NearbyDevice
import dev.lemonadecode.domain.mobile.NearbyKind
import dev.lemonadecode.domain.mobile.QrPairing
import dev.lemonadecode.domain.mobile.ScreenPicture
import dev.lemonadecode.domain.mobile.ScreenUnavailable
import dev.lemonadecode.domain.settings.AppSettings
import dev.lemonadecode.platform.architecture.testing.FakeLemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.process.ProcessOutput
import dev.lemonadecode.settings.fake.FakeSettingsService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MobileServiceImplTest {
  private val settings = FakeSettingsService(AppSettings(mobilePlugin = true))
  private val commands = FakeDeviceCommands()
  private val finder = FakeToolFinder()
  private val files = FakeDeviceFiles()
  private val decoders = FakeScreenDecoders()

  private val pixel = DeviceId("38021FDH2000AB")
  private val logcat = "adb -s 38021FDH2000AB logcat -v threadtime -T 300"

  /** Logcat with each line's user id and the longer backlog, as it's read for some apps only. */
  private val logcatByUser = "adb -s 38021FDH2000AB logcat -v threadtime -v uid -T 2000"

  private fun TestScope.service(): MobileServiceImpl = MobileServiceImpl(
    settings = settings,
    finder = finder,
    commands = commands,
    files = files,
    qr = FakeQrEncoder,
    decoders = decoders,
    dispatchers = FakeLemonadeCodeCoroutineDispatchers(StandardTestDispatcher(testScheduler)),
    scope = backgroundScope,
    timeSource = testScheduler.timeSource,
    random = Random(7),
  )

  private fun answerPixel() {
    commands.answer("adb devices -l", "List of devices attached\n38021FDH2000AB device usb:1-1 product:shiba model:Pixel_8 device:shiba transport_id:1\n")
    commands.answer("adb -s 38021FDH2000AB shell settings get global device_name", androidInfo("Willian's Pixel", "Pixel 8", "15", "Physical size: 1080x2400", "38021FDH2000AB"))
  }

  @Test
  fun `nothing runs while the plugin is off`() = runTest {
    settings.update { it.copy(mobilePlugin = false) }
    answerPixel()
    val service = service()
    advanceTimeBy(5.minutes)
    runCurrent()

    assertNull(service.tools.value)
    assertEquals(emptyList(), service.devices.value)
    assertEquals(LemonadeCodeResult.failure(MobileError.ToolMissing(MobileTool.Adb)), service.pairWithCode("192.168.1.42:37199", "123456"))
    assertEquals(LemonadeCodeResult.failure(MobileError.DeviceGone), service.tap(pixel, 10, 20))
    service.nearby(MobilePlatform.Android).test { assertEquals(emptyList(), awaitItem()) }
    service.pairWithQr().test {
      assertEquals(QrPairing.Failed(MobileError.ToolMissing(MobileTool.Adb)), awaitItem())
      awaitComplete()
    }
    service.logs(pixel).test {
      assertEquals(DeviceLogs("", emptyList()), awaitItem())
      assertEquals(DeviceLogs("", emptyList(), MobileError.DeviceGone), awaitItem())
    }
    service.screen(pixel).test {
      assertEquals(DeviceScreen.Loading, awaitItem())
      assertEquals(DeviceScreen.Unavailable(ScreenUnavailable.Failed), awaitItem())
      cancelAndIgnoreRemainingEvents()
    }
    assertEquals(0, finder.finds)
    assertEquals(emptyList(), commands.ran)
  }

  @Test
  fun `turning the plugin on follows the devices, and off forgets them and stops`() = runTest {
    settings.update { it.copy(mobilePlugin = false) }
    answerPixel()
    val service = service()
    runCurrent()

    settings.update { it.copy(mobilePlugin = true) }
    runCurrent()
    assertEquals(MobileTools(adb = "/sdk/platform-tools/adb", libimobiledevice = "/opt/homebrew/bin"), service.tools.value)
    assertEquals(listOf("Willian's Pixel"), service.devices.value.map { it.name })

    advanceTimeBy(10.seconds)
    runCurrent()
    assertEquals(1, commands.count("adb -s 38021FDH2000AB shell settings"), "the device is read once per connection")
    assertTrue(commands.count("adb devices -l") >= 5)

    settings.update { it.copy(mobilePlugin = false) }
    runCurrent()
    assertEquals(emptyList(), service.devices.value)
    val ran = commands.ran.size
    advanceTimeBy(1.minutes)
    runCurrent()
    assertEquals(ran, commands.ran.size)
  }

  @Test
  fun `devices from both tools, with what each can do`() = runTest {
    commands.answer(
      "adb devices -l",
      """
        List of devices attached
        38021FDH2000AB         device usb:1-1 product:shiba model:Pixel_8 device:shiba transport_id:1
        192.168.1.42:41235     device product:panther model:Pixel_7 device:panther transport_id:2
        R58M123ABC             unauthorized usb:1-2 transport_id:4
      """.trimIndent(),
    )
    commands.answer("adb -s 38021FDH2000AB shell settings get global device_name", androidInfo("Willian's Pixel", "Pixel 8", "15", "Physical size: 1080x2400", "38021FDH2000AB"))
    commands.answer("adb -s 192.168.1.42:41235 shell settings get global device_name", androidInfo("null", "Pixel 7", "14", "Physical size: 1080x2400\nOverride size: 720x1600", "2A1B3C"))
    commands.answer("idevice_id -l", "00008110-001A2C3E0E12801E\n")
    commands.answer("idevice_id -n", "00008110-001A2C3E0E12801E\n00008030-000A1B2C3D4E5F60\n")
    commands.answer("ideviceinfo -u 00008110-001A2C3E0E12801E", "DeviceName: Willian's iPhone\nProductType: iPhone15,2\nProductVersion: 18.1\n")
    commands.answer("ideviceinfo -u 00008030-000A1B2C3D4E5F60 -n", exitCode = 255, stderr = "ERROR: Could not connect to lockdownd: Pairing dialog response pending (-19)")
    commands.answer("ideviceinfo -u 00008030-000A1B2C3D4E5F60 -n -s -k DeviceName", "Willian's iPad\n")
    val service = service()
    runCurrent()

    val android = setOf(DeviceCapability.Mirror, DeviceCapability.Touch, DeviceCapability.Keys, DeviceCapability.Rotate, DeviceCapability.Screenshot, DeviceCapability.Logs, DeviceCapability.LogsByApp)
    assertEquals(
      listOf(
        MobileDevice(pixel, MobilePlatform.Android, "Willian's Pixel", "15", DeviceConnection.Usb, "38021FDH2000AB", DeviceAvailability.Ready, 1080, 2400, android),
        MobileDevice(
          DeviceId("192.168.1.42:41235"),
          MobilePlatform.Android,
          "Pixel 7",
          "14",
          DeviceConnection.Wifi,
          "192.168.1.42:41235",
          DeviceAvailability.Ready,
          720,
          1600,
          android + DeviceCapability.Disconnect,
        ),
        MobileDevice(DeviceId("R58M123ABC"), MobilePlatform.Android, "R58M123ABC", null, DeviceConnection.Usb, "R58M123ABC", DeviceAvailability.Unauthorized),
        MobileDevice(
          DeviceId("00008110-001A2C3E0E12801E"),
          MobilePlatform.Ios,
          "Willian's iPhone",
          "18.1",
          DeviceConnection.Usb,
          "00008110-001A2C3E0E12801E",
          capabilities = setOf(DeviceCapability.Mirror, DeviceCapability.Screenshot, DeviceCapability.Logs),
        ),
        MobileDevice(
          DeviceId("00008030-000A1B2C3D4E5F60"),
          MobilePlatform.Ios,
          "Willian's iPad",
          null,
          DeviceConnection.Wifi,
          "00008030-000A1B2C3D4E5F60",
          DeviceAvailability.Unauthorized,
        ),
      ),
      service.devices.value,
    )
    assertEquals(0, commands.count("adb -s R58M123ABC"), "an unauthorized device isn't asked anything")
  }

  @Test
  fun `a missing tool is looked for again later`() = runTest {
    finder.tools = MobileTools(adb = null, libimobiledevice = null)
    val service = service()
    runCurrent()
    assertEquals(MobileTools(null, null), service.tools.value)
    assertEquals(LemonadeCodeResult.failure(MobileError.ToolMissing(MobileTool.Adb)), service.pairWithCode("192.168.1.42:37199", "123456"))

    finder.tools = MobileTools(adb = "/sdk/platform-tools/adb", libimobiledevice = null)
    answerPixel()
    advanceTimeBy(31.seconds)
    runCurrent()
    assertEquals("/sdk/platform-tools/adb", service.tools.value?.adb)
    assertEquals(listOf(pixel), service.devices.value.map { it.id })
  }

  @Test
  fun `input reaches the device through adb`() = runTest {
    answerPixel()
    commands.answer("adb -s 38021FDH2000AB shell input")
    commands.answer("adb -s 38021FDH2000AB shell settings put")
    val service = service()
    runCurrent()

    assertEquals(LemonadeCodeResult.success(Unit), service.tap(pixel, 10, 20))
    assertEquals(LemonadeCodeResult.success(Unit), service.swipe(pixel, 1, 2, 3, 4, 300))
    assertEquals(LemonadeCodeResult.success(Unit), service.press(pixel, DeviceButton.Back))
    assertEquals(LemonadeCodeResult.success(Unit), service.type(pixel, "it's here\nok"))
    assertEquals(LemonadeCodeResult.success(Unit), service.rotate(pixel))
    assertEquals(
      listOf(
        "adb -s 38021FDH2000AB shell input tap 10 20",
        "adb -s 38021FDH2000AB shell input swipe 1 2 3 4 300",
        "adb -s 38021FDH2000AB shell input keyevent 4",
        "adb -s 38021FDH2000AB shell input text 'it'\\''s%shere'",
        "adb -s 38021FDH2000AB shell input keyevent 66",
        "adb -s 38021FDH2000AB shell input text 'ok'",
        "adb -s 38021FDH2000AB shell settings get system user_rotation",
        "adb -s 38021FDH2000AB shell settings put system accelerometer_rotation 0 && settings put system user_rotation 1",
      ),
      commands.ran.filter { it.startsWith("adb -s 38021FDH2000AB shell input") || it.startsWith("adb -s 38021FDH2000AB shell settings put") || it.contains("user_rotation") },
    )
  }

  @Test
  fun `the screen is captured again and again, with its size and frame rate`() = runTest {
    answerPixel()
    commands.picture("adb -s 38021FDH2000AB exec-out screencap -p", png(2400, 1080))
    commands.answer("adb -s 38021FDH2000AB shell settings put")
    val service = service()
    runCurrent()

    service.screen(pixel).test {
      assertEquals(DeviceScreen.Loading, awaitItem())
      val first = assertIs<DeviceScreen.Frame>(awaitItem())
      assertEquals(2400 to 1080, first.width to first.height)
      assertNull(first.framesPerSecond)
      val second = assertIs<DeviceScreen.Frame>(awaitItem())
      assertEquals(1000f / FrameGap.inWholeMilliseconds, second.framesPerSecond!!, 0.01f)
      cancelAndIgnoreRemainingEvents()
    }
    // The frame was wide, so rotating turns it back to portrait without asking.
    assertEquals(LemonadeCodeResult.success(Unit), service.rotate(pixel))
    assertEquals("adb -s 38021FDH2000AB shell settings put system accelerometer_rotation 0 && settings put system user_rotation 0", commands.ran.last { "user_rotation" in it })
  }

  @Test
  fun `an Android screen plays as a video at its size, recorded smaller, and starts again when it turns`() = runTest {
    answerPixel()
    var screenSize = "1080x2400"
    commands.answer("adb -s 38021FDH2000AB shell dumpsys window displays") { ProcessOutput(0, "  init=1080x2400 420dpi base=1080x2400 cur=$screenSize app=1080x2337\n", "") }
    val portrait = commands.byteStream("adb -s 38021FDH2000AB exec-out screenrecord --output-format=h264 --size 576x1280 --bit-rate 8000000 -")
    val landscape = commands.byteStream("adb -s 38021FDH2000AB exec-out screenrecord --output-format=h264 --size 1280x576 --bit-rate 8000000 -")
    commands.answer("adb -s 38021FDH2000AB shell settings put")
    val service = service()
    runCurrent()

    service.screen(pixel).test {
      assertEquals(DeviceScreen.Loading, awaitItem())
      portrait.send(nal(7, 0x42) + nal(8, 0xCE) + picture(idr = true))
      portrait.send(picture())
      val first = assertIs<DeviceScreen.Frame>(awaitItem())
      assertEquals(1080 to 2400, first.width to first.height)
      assertIs<ScreenPicture.Bgra>(first.picture)
      // The last picture before the screen stops changing shows once the stream goes quiet.
      assertIs<DeviceScreen.Frame>(awaitItem())

      // Turned to landscape: recorded again, the other way round.
      screenSize = "2400x1080"
      landscape.send(picture(idr = true))
      val turned = assertIs<DeviceScreen.Frame>(awaitItem())
      assertEquals(2400 to 1080, turned.width to turned.height)
      cancelAndIgnoreRemainingEvents()
    }
    runCurrent()
    assertEquals(2, decoders.opened)
    assertEquals(2, decoders.closed)
    assertEquals(0, commands.count("adb -s 38021FDH2000AB exec-out screencap"))
    // It knows the screen is wide, so rotating turns it back to portrait without asking.
    assertEquals(LemonadeCodeResult.success(Unit), service.rotate(pixel))
    assertEquals("adb -s 38021FDH2000AB shell settings put system accelerometer_rotation 0 && settings put system user_rotation 0", commands.ran.last { "user_rotation" in it })
  }

  @Test
  fun `an Android screen that can't be recorded falls back on screenshots`() = runTest {
    answerPixel()
    commands.answer("adb -s 38021FDH2000AB shell dumpsys window displays", "cur=1080x2400\n")
    commands.picture("adb -s 38021FDH2000AB exec-out screencap -p", png(1080, 2400))
    // No recording is faked, so each try fails at once.
    val service = service()
    runCurrent()

    service.screen(pixel).test {
      assertEquals(DeviceScreen.Loading, awaitItem())
      val frame = assertIs<DeviceScreen.Frame>(awaitItem())
      assertIs<ScreenPicture.Encoded>(frame.picture)
      cancelAndIgnoreRemainingEvents()
    }
    assertEquals(3, commands.count("adb -s 38021FDH2000AB exec-out screenrecord"))
  }

  @Test
  fun `screenshots are saved under the device's name`() = runTest {
    answerPixel()
    commands.picture("adb -s 38021FDH2000AB exec-out screencap -p", png(1080, 2400))
    val service = service()
    runCurrent()
    assertEquals(LemonadeCodeResult.success("/tmp/lemonade-code/screenshots/Willian's Pixel.png"), service.screenshot(pixel))
    assertEquals(listOf("Willian's Pixel"), files.saved.map { it.first })
  }

  @Test
  fun `an iPhone over Wi-Fi shows its screen over a cable only, and one without its developer image can't`() = runTest {
    commands.answer("idevice_id -l", "00008110-001A2C3E0E12801E\n")
    commands.answer("idevice_id -n", "00008030-000A1B2C3D4E5F60\n")
    commands.answer("ideviceinfo -u", "DeviceName: iPhone\nProductVersion: 17.5\n")
    commands.answer(
      "idevicescreenshot -u 00008110-001A2C3E0E12801E",
      exitCode = 255,
      stdout = "Could not start screenshotr service: Invalid service\nRemember that you have to mount the Developer disk image on your device if you want to use the screenshotr service.",
    )
    val service = service()
    runCurrent()

    service.screen(DeviceId("00008030-000A1B2C3D4E5F60")).test {
      assertEquals(DeviceScreen.Loading, awaitItem())
      assertEquals(DeviceScreen.Unavailable(ScreenUnavailable.CableOnly), awaitItem())
      cancelAndIgnoreRemainingEvents()
    }
    service.screen(DeviceId("00008110-001A2C3E0E12801E")).test {
      assertEquals(DeviceScreen.Loading, awaitItem())
      assertEquals(DeviceScreen.Unavailable(ScreenUnavailable.NotSupported), awaitItem())
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun `an iPhone over a cable is captured with idevicescreenshot`() = runTest {
    val udid = "00008110-001A2C3E0E12801E"
    commands.answer("idevice_id -l", "$udid\n")
    commands.answer("ideviceinfo -u", "DeviceName: iPhone\n")
    commands.answer("idevicescreenshot -u $udid") {
      files.written["/tmp/lemonade-code/captures/capture-0.png"] = png(1179, 2556)
      ProcessOutput(0, "Screenshot saved to /tmp/lemonade-code/captures/capture-0.png", "")
    }
    val service = service()
    runCurrent()

    service.screen(DeviceId(udid)).test {
      assertEquals(DeviceScreen.Loading, awaitItem())
      val frame = assertIs<DeviceScreen.Frame>(awaitItem())
      assertEquals(1179 to 2556, frame.width to frame.height)
      cancelAndIgnoreRemainingEvents()
    }
    assertEquals(LemonadeCodeResult.failure(MobileError.CommandFailed("libimobiledevice can't do this on an iPhone or iPad")), service.tap(DeviceId(udid), 1, 1))
  }

  @Test
  fun `a wrong pairing code says so`() = runTest {
    commands.answer("adb devices -l", "List of devices attached\n")
    commands.answer("adb pair 192.168.1.42:37199 123456", exitCode = 1, stdout = "Failed: Wrong password or connection was dropped.\n")
    val service = service()
    runCurrent()
    assertEquals(LemonadeCodeResult.failure(MobileError.WrongCode), service.pairWithCode("192.168.1.42:37199", "123456"))
    assertEquals(LemonadeCodeResult.failure(MobileError.WrongCode), service.pairWithCode("192.168.1.42:37199", "12; reboot"))
  }

  @Test
  fun `pairing with a code connects to the device's connect service`() = runTest {
    var listing = "List of devices attached\n"
    commands.answer("adb devices -l") { ProcessOutput(0, listing, "") }
    commands.answer("adb pair 192.168.1.42:37199 123456", "Successfully paired to 192.168.1.42:37199 [guid=adb-38021FDH2000AB-Gs9d3x]\n")
    commands.answer("adb mdns services", "List of discovered mdns services\nadb-38021FDH2000AB-Gs9d3x\t_adb-tls-connect._tcp\t192.168.1.42:41235\n")
    commands.answer("adb connect 192.168.1.42:41235") {
      listing += "192.168.1.42:41235 device product:shiba model:Pixel_8 device:shiba transport_id:2\n"
      ProcessOutput(0, "connected to 192.168.1.42:41235\n", "")
    }
    commands.answer("adb -s 192.168.1.42:41235 shell settings get global device_name", androidInfo("null", "Pixel 8", "15", "Physical size: 1080x2400", "38021FDH2000AB"))
    val service = service()
    runCurrent()

    val paired = service.pairWithCode("192.168.1.42:37199", "123 456")
    assertEquals("Pixel 8", (paired as LemonadeCodeResult.Success).value.name)
    assertEquals(DeviceConnection.Wifi, paired.value.connection)
  }

  @Test
  fun `the QR code shows, then the phone that scanned it pairs and connects`() = runTest {
    var listing = "List of devices attached\n"
    var services = "List of discovered mdns services\n"
    commands.answer("adb devices -l") { ProcessOutput(0, listing, "") }
    commands.answer("adb mdns check", "mdns daemon version [Openscreen discovery 0.0.0]\n")
    commands.answer("adb mdns services") { ProcessOutput(0, services, "") }
    commands.answer("adb connect 192.168.1.42:41235") {
      listing += "192.168.1.42:41235 device product:shiba model:Pixel_8 device:shiba transport_id:2\n"
      ProcessOutput(0, "connected to 192.168.1.42:41235\n", "")
    }
    commands.answer("adb -s 192.168.1.42:41235 shell settings get global device_name", androidInfo("null", "Pixel 8", "15", "Physical size: 1080x2400", "38021FDH2000AB"))
    val service = service()

    service.pairWithQr().test {
      val showing = assertIs<QrPairing.Showing>(awaitItem())
      val (name, password) = Regex("""^WIFI:T:ADB;S:(lemonade-[a-z0-9]{6});P:([a-z0-9]{12});;$""").find(showing.payload)!!.destructured
      commands.answer("adb pair 192.168.1.42:37199 $password", "Successfully paired to 192.168.1.42:37199 [guid=adb-38021FDH2000AB-Gs9d3x]\n")
      advanceTimeBy(5.seconds)
      services += "$name\t_adb-tls-pairing._tcp\t192.168.1.42:37199\nadb-38021FDH2000AB-Gs9d3x\t_adb-tls-connect._tcp\t192.168.1.42:41235\n"

      assertEquals(QrPairing.Pairing, awaitItem())
      val paired = assertIs<QrPairing.Paired>(awaitItem())
      assertEquals(DeviceId("192.168.1.42:41235"), paired.device.id)
      assertEquals("Pixel 8", paired.device.name)
      awaitComplete()
    }
  }

  @Test
  fun `turning the plugin off ends a QR pairing and its polling`() = runTest {
    commands.answer("adb devices -l", "List of devices attached\n")
    commands.answer("adb mdns check", "mdns daemon version [Openscreen discovery 0.0.0]\n")
    commands.answer("adb mdns services", "List of discovered mdns services\n")
    val service = service()

    service.pairWithQr().test {
      assertIs<QrPairing.Showing>(awaitItem())
      advanceTimeBy(3.seconds)
      settings.update { it.copy(mobilePlugin = false) }
      assertEquals(QrPairing.Failed(MobileError.ToolMissing(MobileTool.Adb)), awaitItem())
      awaitComplete()
    }
    val polls = commands.count("adb mdns services")
    advanceTimeBy(1.minutes)
    runCurrent()
    assertEquals(polls, commands.count("adb mdns services"))
  }

  @Test
  fun `the QR code needs adb's network discovery`() = runTest {
    commands.answer("adb devices -l", "List of devices attached\n")
    commands.answer("adb mdns check", exitCode = 1, stderr = "ERROR: mdns daemon unavailable\n")
    val service = service()
    service.pairWithQr().test {
      assertEquals(QrPairing.Failed(MobileError.DiscoveryUnavailable), awaitItem())
      awaitComplete()
    }
  }

  @Test
  fun `nearby Android devices leave out connected ones and are named by their serial`() = runTest {
    answerPixel()
    commands.answer(
      "adb devices -l",
      "List of devices attached\n38021FDH2000AB device usb:1-1 model:Pixel_8 transport_id:1\n192.168.1.50:40001 device model:Pixel_7 transport_id:2\n",
    )
    commands.answer("adb -s 192.168.1.50:40001 shell settings get global device_name", androidInfo("null", "Pixel 7", "14", "", "2A1B3C"))
    commands.answer("adb mdns check", "mdns daemon version [Openscreen discovery 0.0.0]\n")
    commands.answer(
      "adb mdns services",
      """
        List of discovered mdns services
        adb-38021FDH2000AB-Gs9d3x	_adb-tls-connect._tcp	192.168.1.42:41235
        adb-ZZ99-Ab12Cd	_adb-tls-pairing._tcp	192.168.1.43:37199
        adb-2A1B3C-x1y2z3	_adb-tls-connect._tcp	192.168.1.50:40001
        lemonade-abc123	_adb-tls-pairing._tcp	192.168.1.44:37000
      """.trimIndent(),
    )
    val service = service()
    runCurrent()

    service.nearby(MobilePlatform.Android).test {
      assertEquals(
        listOf(
          NearbyDevice(MobilePlatform.Android, "Willian's Pixel", "192.168.1.42:41235", NearbyKind.Connectable),
          NearbyDevice(MobilePlatform.Android, "adb-ZZ99-Ab12Cd", "192.168.1.43:37199", NearbyKind.AwaitingCode),
        ),
        awaitItem(),
      )
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun `an unpaired device refuses to connect`() = runTest {
    commands.answer("adb devices -l", "List of devices attached\n")
    commands.answer("adb connect 192.168.1.42:41235", "failed to authenticate to 192.168.1.42:41235\n")
    val service = service()
    runCurrent()
    val nearby = NearbyDevice(MobilePlatform.Android, "Pixel 8", "192.168.1.42:41235", NearbyKind.Connectable)
    assertEquals(LemonadeCodeResult.failure(MobileError.NotPaired), service.connect(nearby))
  }

  @Test
  fun `pairing over a cable waits for Trust, then for the device`() = runTest {
    val udid = "00008110-001A2C3E0E12801E"
    var plugged = ""
    var trusted = false
    commands.answer("idevice_id -l") { ProcessOutput(0, plugged, "") }
    commands.answer("idevice_id -n", "")
    commands.answer("ideviceinfo -u $udid") {
      if (trusted) ProcessOutput(0, "DeviceName: Willian's iPhone\nProductVersion: 18.1\n", "") else ProcessOutput(255, "", "ERROR: Could not connect to lockdownd: Pairing dialog response pending (-19)")
    }
    commands.answer("ideviceinfo -u $udid -s -k DeviceName", "Willian's iPhone\n")
    commands.answer("idevicepair -u $udid pair") {
      if (trusted) {
        ProcessOutput(0, "SUCCESS: Paired with device $udid\n", "")
      } else {
        ProcessOutput(1, "ERROR: Please accept the trust dialog on the screen of device $udid, then attempt to pair again.\n", "")
      }
    }
    val service = service()
    runCurrent()

    service.pairOverCable().test {
      assertEquals(CablePairing.WaitingForDevice, awaitItem())
      plugged = "$udid\n"
      assertEquals(CablePairing.WaitingForTrust("Willian's iPhone"), awaitItem())
      trusted = true
      val paired = assertIs<CablePairing.Paired>(awaitItem())
      assertEquals(DeviceAvailability.Ready, paired.device.availability)
      awaitComplete()
    }
  }

  @Test
  fun `logs keep the last thousand lines of the app in front`() = runTest {
    answerPixel()
    commands.answer("adb -s 38021FDH2000AB shell dumpsys activity activities", "  topResumedActivity=ActivityRecord{c8d2f5e u0 com.teya.storefront/.MainActivity t123}\n")
    commands.answer("adb -s 38021FDH2000AB shell pidof com.teya.storefront", "1234\n")
    val service = service()
    runCurrent()

    val latest = MutableStateFlow<DeviceLogs?>(null)
    val _ = backgroundScope.launch { service.logs(pixel).collect { latest.value = it } }
    runCurrent()
    // This Android lists no user ids, so the app's lines are told by its processes.
    val lines = commands.stream("adb -s 38021FDH2000AB logcat -v threadtime -T 2000")
    repeat(1200) { lines.send("11-02 10:14:03.120  1234  1250 I Storefront: line ${it + 1}") }
    lines.send("11-02 10:14:03.121   999   999 E Other: not the app in front")
    runCurrent()

    val logs = latest.value!!
    assertEquals("logcat · com.teya.storefront", logs.source)
    assertEquals(1000, logs.lines.size)
    assertEquals(201L to "line 201", logs.lines.first().let { it.sequence to it.message })
    assertEquals(1200L to "line 1200", logs.lines.last().let { it.sequence to it.message })
    assertEquals(setOf("Storefront"), logs.lines.map { it.tag }.toSet())

    service.clearLogs(pixel)
    runCurrent()
    assertEquals(emptyList(), latest.value!!.lines)
    lines.send("11-02 10:14:05.000  1234  1250 W Storefront: after clearing")
    runCurrent()
    assertEquals(listOf(1201L to LogLevel.Warning), latest.value!!.lines.map { it.sequence to it.level })
  }

  @Test
  fun `the picked app's lines are told by its user id, so they stay when it restarts`() = runTest {
    answerPixel()
    commands.answer("adb -s 38021FDH2000AB shell pm list packages -U", PackageUserIds)
    val service = service()
    runCurrent()

    val latest = MutableStateFlow<DeviceLogs?>(null)
    val _ = backgroundScope.launch { service.logs(pixel, LogPackages.Only(setOf(DustSample))).collect { latest.value = it } }
    runCurrent()
    val lines = commands.stream(logcatByUser)
    lines.send("11-04 11:57:19.320 10174 12163 12163 I tem.dust.sample: Late-enabling -Xcheck:jni")
    lines.send("11-04 11:57:19.400 10153  1058  1532 D EGL_emulation: app_time_stats: avg=961.14ms min=930.21ms max=992.07ms count=2")
    lines.send("11-04 11:57:19.534  1000   509   553 I ActivityManager: Killing 12163:com.teya.designsystem.dust.sample/u0a174 (adj 0): stop")
    lines.send("11-04 11:57:24.100 10174 12226 12226 I tem.dust.sample: started again with a new process")
    runCurrent()

    assertEquals("logcat · $DustSample", latest.value?.source)
    assertEquals(listOf("Late-enabling -Xcheck:jni", "started again with a new process"), latest.value?.lines?.map { it.message })
    assertEquals(0, commands.count("adb -s 38021FDH2000AB shell pidof"), "the user id is enough")
  }

  @Test
  fun `several apps, or all of them, can be picked, and the source says which`() = runTest {
    answerPixel()
    commands.answer("adb -s 38021FDH2000AB shell pm list packages -U", PackageUserIds)
    val service = service()
    runCurrent()

    service.logs(pixel, LogPackages.Only(setOf(DustSample, "com.teya.pos"))).test {
      runCurrent()
      commands.stream(logcatByUser).send("11-04 11:57:19.320 10174 12163 12163 I tem.dust.sample: from the sample")
      commands.stream(logcatByUser).send("11-04 11:57:19.321 10180 13001 13001 W Pos: from the till")
      commands.stream(logcatByUser).send("11-04 11:57:19.322 10153  1058  1532 D EGL_emulation: from the launcher")
      runCurrent()
      val logs = expectMostRecentItem()
      assertEquals("logcat · 2 apps", logs.source)
      assertEquals(listOf("from the sample", "from the till"), logs.lines.map { it.message })
      cancelAndIgnoreRemainingEvents()
    }

    service.logs(pixel, LogPackages.All).test {
      runCurrent()
      val everything = "adb -s 38021FDH2000AB logcat -v threadtime -v uid -T 300"
      commands.stream(everything).send("11-04 11:57:19.322 10153  1058  1532 D EGL_emulation: from the launcher")
      commands.stream(everything).send("11-04 11:40:36.885  root   298   298 D Zygote  : Forked child process 9816")
      runCurrent()
      val logs = expectMostRecentItem()
      assertEquals("logcat · all", logs.source)
      assertEquals(listOf("from the launcher", "Forked child process 9816"), logs.lines.map { it.message })
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun `following a device for other apps forgets the lines and reads again for those`() = runTest {
    answerPixel()
    commands.answer("adb -s 38021FDH2000AB shell pm list packages -U", PackageUserIds)
    commands.answer("adb -s 38021FDH2000AB shell dumpsys activity activities", "  topResumedActivity=ActivityRecord{4197f47 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity} t135}\n")
    val service = service()
    runCurrent()

    val front = backgroundScope.launch { service.logs(pixel).collect {} }
    runCurrent()
    commands.stream(logcatByUser).send("11-04 11:57:19.322 10153  1058  1532 D EGL_emulation: from the launcher")
    runCurrent()
    front.cancel()

    service.logs(pixel, LogPackages.Only(setOf(DustSample))).test {
      runCurrent()
      assertEquals(emptyList(), expectMostRecentItem().lines, "the launcher's lines are gone")
      assertEquals(1, commands.openStreams[logcatByUser], "the launcher's reader stopped")
      commands.stream(logcatByUser).send("11-04 11:57:24.100 10174 12226 12226 I tem.dust.sample: the sample's")
      runCurrent()
      assertEquals(listOf("the sample's"), expectMostRecentItem().lines.map { it.message })
      cancelAndIgnoreRemainingEvents()
    }
    assertEquals(2, commands.count(logcatByUser), "logcat was started again for the sample")
  }

  @Test
  fun `the app in front is followed by its user id, and the source names it`() = runTest {
    answerPixel()
    commands.answer("adb -s 38021FDH2000AB shell pm list packages -U", PackageUserIds)
    var resumed = "com.google.android.apps.nexuslauncher/.NexusLauncherActivity"
    commands.answer("adb -s 38021FDH2000AB shell dumpsys activity activities") { ProcessOutput(0, "  topResumedActivity=ActivityRecord{4197f47 u0 $resumed} t135}\n", "") }
    val service = service()
    runCurrent()

    val latest = MutableStateFlow<DeviceLogs?>(null)
    val _ = backgroundScope.launch { service.logs(pixel).collect { latest.value = it } }
    runCurrent()
    val lines = commands.stream(logcatByUser)
    lines.send("11-04 11:57:19.322 10153  1058  1532 D EGL_emulation: the launcher's")
    lines.send("11-04 11:57:19.323 10174 12163 12163 I tem.dust.sample: not in front yet")
    runCurrent()
    assertEquals("logcat · com.google.android.apps.nexuslauncher", latest.value?.source)
    assertEquals(listOf("the launcher's"), latest.value?.lines?.map { it.message })

    resumed = "$DustSample/.MainActivity"
    advanceTimeBy(3.seconds)
    runCurrent()
    lines.send("11-04 11:57:22.000 10174 12163 12163 I tem.dust.sample: in front now")
    runCurrent()
    assertEquals("logcat · $DustSample", latest.value?.source)
    assertEquals(listOf("the launcher's", "in front now"), latest.value?.lines?.map { it.message })
  }

  @Test
  fun `without user ids, the picked apps are followed by their processes, looked at again as they restart`() = runTest {
    answerPixel()
    var pids = "12163\n"
    commands.answer("adb -s 38021FDH2000AB shell pidof $DustSample") { ProcessOutput(0, pids, "") }
    val service = service()
    runCurrent()

    val latest = MutableStateFlow<DeviceLogs?>(null)
    val _ = backgroundScope.launch { service.logs(pixel, LogPackages.Only(setOf(DustSample, "not a package; reboot"))).collect { latest.value = it } }
    runCurrent()
    val lines = commands.stream("adb -s 38021FDH2000AB logcat -v threadtime -T 2000")
    lines.send("11-04 11:57:19.320 12163 12163 I tem.dust.sample: first process")
    pids = "12226\n"
    advanceTimeBy(3.seconds)
    runCurrent()
    lines.send("11-04 11:57:19.320 12163 12163 I tem.dust.sample: an old process's late line")
    lines.send("11-04 11:57:24.100 12226 12226 I tem.dust.sample: second process")
    runCurrent()

    assertEquals(listOf("first process", "second process"), latest.value?.lines?.map { it.message })
    assertTrue(commands.ran.none { "reboot" in it }, "only package names reach the device's shell")
  }

  @Test
  fun `the apps to pick from are the ones installed, the one in front first, then the running ones`() = runTest {
    answerPixel()
    commands.answer(
      "adb -s 38021FDH2000AB shell pm list packages -3 -U",
      "package:com.teya.pos uid:10180\npackage:$DustSample uid:10174\npackage:com.teya.terminal.cds.sample uid:10176\n$InfoMarker\n10174 $DustSample\n10176 com.teya.terminal.cds.sample:remote\n10153 com.google.android.apps.nexuslauncher\n",
    )
    commands.answer("adb -s 38021FDH2000AB shell dumpsys activity activities", "  topResumedActivity=ActivityRecord{4197f47 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity} t135}\n")
    val service = service()
    runCurrent()

    service.apps(pixel).test {
      assertEquals(
        listOf(
          DeviceApp("com.google.android.apps.nexuslauncher", running = true, inFront = true),
          DeviceApp(DustSample, running = true),
          DeviceApp("com.teya.terminal.cds.sample", running = true),
          DeviceApp("com.teya.pos"),
        ),
        awaitItem(),
      )
      cancelAndIgnoreRemainingEvents()
    }
    service.apps(DeviceId("00008130-001A2C3E")).test {
      assertEquals(emptyList(), awaitItem(), "a device that isn't connected has none")
      awaitComplete()
    }
  }

  @Test
  fun `one log stream per device, kept a while after the last viewer and stopped when the device goes`() = runTest {
    var listing = "List of devices attached\n38021FDH2000AB device usb:1-1 product:shiba model:Pixel_8 device:shiba transport_id:1\n"
    commands.answer("adb devices -l") { ProcessOutput(0, listing, "") }
    commands.answer("adb -s 38021FDH2000AB shell settings get global device_name", androidInfo("null", "Pixel 8", "15", "", ""))
    val service = service()
    runCurrent()

    val first = backgroundScope.launch { service.logs(pixel).collect {} }
    val second = backgroundScope.launch { service.logs(pixel).collect {} }
    runCurrent()
    assertEquals(1, commands.count(logcat))
    assertEquals(1, commands.openStreams[logcat])
    commands.stream(logcat).send("11-02 10:14:03.120  1234  1250 I Storefront: before leaving")
    first.cancel()
    second.cancel()
    advanceTimeBy(4.minutes)
    runCurrent()
    assertEquals(1, commands.openStreams[logcat], "still read a while after the last viewer left")

    service.logs(pixel).test {
      assertEquals(listOf("before leaving"), expectMostRecentItem().lines.map { it.message })
      listing = "List of devices attached\n"
      advanceTimeBy(3.seconds)
      assertEquals(MobileError.DeviceGone, expectMostRecentItem().error)
      cancelAndIgnoreRemainingEvents()
    }
    runCurrent()
    assertEquals(0, commands.openStreams[logcat])
    assertEquals(1, commands.count(logcat))
  }

  @Test
  fun `a log nobody comes back to stops after five minutes`() = runTest {
    answerPixel()
    val service = service()
    runCurrent()
    val viewer = backgroundScope.launch { service.logs(pixel).collect {} }
    runCurrent()
    viewer.cancel()
    advanceTimeBy(LogLinger + 1.seconds)
    runCurrent()
    assertEquals(0, commands.openStreams[logcat])
  }

  @Test
  fun `iOS logs come from idevicesyslog, over the network when the device is there`() = runTest {
    val udid = "00008030-000A1B2C3D4E5F60"
    commands.answer("idevice_id -l", "")
    commands.answer("idevice_id -n", "$udid\n")
    commands.answer("ideviceinfo -u $udid -n", "DeviceName: iPad\n")
    val service = service()
    runCurrent()

    val latest = MutableStateFlow<DeviceLogs?>(null)
    val _ = backgroundScope.launch { service.logs(DeviceId(udid)).collect { latest.value = it } }
    runCurrent()
    commands.stream("idevicesyslog -u $udid -n").send("Nov  2 10:14:03 iPad storefront(CFNetwork)[1234] <Error>: Task failed")
    runCurrent()
    assertEquals("idevicesyslog · all processes", latest.value?.source)
    assertEquals(listOf("storefront(CFNetwork)" to LogLevel.Error), latest.value?.lines?.map { it.tag to it.level })
  }
}

/** A package an Android 13 emulator had installed, with its user id below. */
private const val DustSample = "com.teya.designsystem.dust.sample"

/** What `pm list packages -U` lists, as that emulator does. */
private const val PackageUserIds = "package:com.teya.designsystem.dust.sample uid:10174\npackage:com.google.android.apps.nexuslauncher uid:10153\npackage:com.teya.pos uid:10180\n"
