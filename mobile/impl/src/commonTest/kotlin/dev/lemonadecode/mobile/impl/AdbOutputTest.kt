package dev.lemonadecode.mobile.impl

import dev.lemonadecode.domain.mobile.DeviceAvailability
import dev.lemonadecode.domain.mobile.DeviceConnection
import dev.lemonadecode.domain.mobile.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdbOutputTest {
  @Test
  fun `adb devices -l gives each device's state, connection and model`() {
    val output = """
      * daemon not running; starting now at tcp:5037
      * daemon started successfully
      List of devices attached
      38021FDH2000AB         device usb:1-1 product:shiba model:Pixel_8 device:shiba transport_id:1
      192.168.1.42:41235     device product:panther model:Pixel_7 device:panther transport_id:2
      adb-2A1B3C-x1y2z3._adb-tls-connect._tcp device product:husky model:Pixel_8_Pro device:husky transport_id:5
      emulator-5554          device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:3
      R58M123ABC             unauthorized usb:1-2 transport_id:4
      0123456789ABCDEF       no permissions (missing udev rules? user is in the plugdev group); see [http://developer.android.com/tools/device.html] usb:1-3 transport_id:6
      ZY22                   offline transport_id:7

    """.trimIndent()

    assertEquals(
      listOf(
        AdbEntry("38021FDH2000AB", DeviceAvailability.Ready, DeviceConnection.Usb, "Pixel 8"),
        AdbEntry("192.168.1.42:41235", DeviceAvailability.Ready, DeviceConnection.Wifi, "Pixel 7"),
        AdbEntry("adb-2A1B3C-x1y2z3._adb-tls-connect._tcp", DeviceAvailability.Ready, DeviceConnection.Wifi, "Pixel 8 Pro"),
        AdbEntry("emulator-5554", DeviceAvailability.Ready, DeviceConnection.Usb, "sdk gphone64 x86 64"),
        AdbEntry("R58M123ABC", DeviceAvailability.Unauthorized, DeviceConnection.Usb, null),
        AdbEntry("0123456789ABCDEF", DeviceAvailability.Unauthorized, DeviceConnection.Usb, null),
        AdbEntry("ZY22", DeviceAvailability.Offline, DeviceConnection.Usb, null),
      ),
      parseAdbDevices(output),
    )
  }

  @Test
  fun `no devices is an empty list`() {
    assertEquals(emptyList(), parseAdbDevices("List of devices attached\n\n"))
  }

  @Test
  fun `addresses and mdns names`() {
    assertEquals("adb-2A1B3C-x1y2z3", mdnsInstanceOf("adb-2A1B3C-x1y2z3._adb-tls-connect._tcp"))
    assertNull(mdnsInstanceOf("192.168.1.42:41235"))
    assertEquals("192.168.1.42", hostOf("192.168.1.42:41235"))
    assertEquals("fe80::1", hostOf("[fe80::1]:41235"))
    assertTrue(isHostAndPort("192.168.1.42:37199"))
    assertFalse(isHostAndPort("192.168.1.42"))
    assertFalse(isHostAndPort("192.168.1.42:port"))
    assertFalse(isHostAndPort(":37199"))
    assertEquals("38021FDH2000AB", serialInInstance("adb-38021FDH2000AB-Gs9d3x"))
    assertNull(serialInInstance("lemonade-abc123"))
  }

  @Test
  fun `wm size prefers the override and turns it to portrait`() {
    assertEquals(1080 to 2400, parseWmSize("Physical size: 1080x2400\n"))
    assertEquals(720 to 1600, parseWmSize("Physical size: 1080x2400\nOverride size: 720x1600\n"))
    assertEquals(1600 to 2560, parseWmSize("Physical size: 2560x1600\n"))
    assertNull(parseWmSize("cmd: Can't find service: window\n"))
  }

  @Test
  fun `adb mdns services lists connect and pairing services`() {
    val output = """
      List of discovered mdns services
      adb-38021FDH2000AB-Gs9d3x	_adb-tls-connect._tcp	192.168.1.42:41235
      adb-38021FDH2000AB-Gs9d3x	_adb-tls-pairing._tcp.	192.168.1.42:37199
      adb-38021FDH2000AB-Gs9d3x	_adb-tls-connect._tcp	192.168.1.42:41235
      studio-Ab12Cd	_adb-tls-pairing._tcp	192.168.1.50:40001
      legacy	_adb._tcp	192.168.1.60:5555
    """.trimIndent()

    assertEquals(
      listOf(
        MdnsService("adb-38021FDH2000AB-Gs9d3x", MdnsKind.Connect, "192.168.1.42:41235"),
        MdnsService("adb-38021FDH2000AB-Gs9d3x", MdnsKind.Pairing, "192.168.1.42:37199"),
        MdnsService("studio-Ab12Cd", MdnsKind.Pairing, "192.168.1.50:40001"),
        MdnsService("legacy", MdnsKind.Other, "192.168.1.60:5555"),
      ),
      parseMdnsServices(output),
    )
  }

  @Test
  fun `connect and pair answers`() {
    assertTrue(connectSucceeded("connected to 192.168.1.42:41235"))
    assertTrue(connectSucceeded("already connected to 192.168.1.42:41235"))
    assertFalse(connectSucceeded("failed to connect to '192.168.1.42:41235': Connection refused"))
    assertFalse(connectSucceeded("failed to authenticate to 192.168.1.42:41235"))
    assertEquals("adb-38021FDH2000AB-Gs9d3x", pairedGuid("Successfully paired to 192.168.1.42:37199 [guid=adb-38021FDH2000AB-Gs9d3x]"))
    assertNull(pairedGuid("Failed: Wrong password or connection was dropped."))
  }

  @Test
  fun `the app in front comes from the resumed activity, else the focused window`() {
    val activities = """
        topResumedActivity=ActivityRecord{c8d2f5e u0 com.teya.storefront/.MainActivity t123}
          mResumedActivity: ActivityRecord{3c3a9b1 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t2}
    """.trimIndent()
    assertEquals("com.teya.storefront", parseForegroundPackage(activities))
    assertEquals("com.android.chrome", parseForegroundPackage("  mResumedActivity: ActivityRecord{3c3a9b1 u0 com.android.chrome/org.chromium.chrome.browser.ChromeTabbedActivity t9}"))
    assertEquals("com.teya.storefront", parseForegroundPackage("  mCurrentFocus=Window{a1b2c3 u0 com.teya.storefront/com.teya.storefront.MainActivity}"))
    assertNull(parseForegroundPackage("  mCurrentFocus=Window{a1b2c3 u0 NotificationShade}"))
    assertNull(parseForegroundPackage(""))
    assertEquals(setOf(1234, 5678), parsePids("1234 5678\n"))
    assertEquals(emptySet(), parsePids(""))
  }

  @Test
  fun `device info reads the name, version, screen and serial number`() {
    assertEquals(
      AndroidInfo("Willian's Pixel", "15", 1080 to 2400, "38021FDH2000AB"),
      parseAndroidInfo(androidInfo("Willian's Pixel", "Pixel 8", "15", "Physical size: 1080x2400", "38021FDH2000AB")),
    )
    assertEquals(
      AndroidInfo("Pixel 7", "14", 720 to 1600, null),
      parseAndroidInfo(androidInfo("null", "Pixel 7", "14", "Physical size: 1080x2400\nOverride size: 720x1600", "")),
    )
    assertEquals(AndroidInfo(null, null, null, null), parseAndroidInfo(""))
  }

  @Test
  fun `logcat threadtime lines`() {
    val parser = LogcatParser()
    assertNull(parser.parse("--------- beginning of main"))
    assertEquals(
      LogcatLine(1234, LogEntry("10:14:03.120", LogLevel.Error, "Storefront", "Payment failed: timeout")),
      parser.parse("11-02 10:14:03.120  1234  1250 E Storefront: Payment failed: timeout"),
    )
    assertEquals(
      LogcatLine(1234, LogEntry("10:14:03.120", LogLevel.Info, "Storefront", "\tat com.teya.Pay.run(Pay.kt:12)")),
      parser.parse("\tat com.teya.Pay.run(Pay.kt:12)"),
    )
    assertEquals(
      LogcatLine(42, LogEntry("10:14:04.001", LogLevel.Debug, "chatty", "uid=1000 expire 3 lines")),
      parser.parse("11-02 10:14:04.001    42    42 D chatty  : uid=1000 expire 3 lines\r"),
    )
    assertEquals(
      LogcatLine(7, LogEntry("10:14:05.000", LogLevel.Fatal, "DEBUG", "")),
      parser.parse("11-02 10:14:05.000     7     7 F DEBUG   : "),
    )
    val levels = "VDIWEFA".map { parser.parse("11-02 10:14:03.120  1  1 $it T: m")?.entry?.level }
    assertEquals(listOf(LogLevel.Verbose, LogLevel.Debug, LogLevel.Info, LogLevel.Warning, LogLevel.Error, LogLevel.Fatal, LogLevel.Fatal), levels)
  }

  @Test
  fun `typed text is one quoted argument with spaces as percent-s`() {
    assertEquals("'hello%sworld'", inputTextArgument("hello world"))
    assertEquals("'it'\\''s'", inputTextArgument("it's"))
    assertEquals("'\$(reboot);%s`id`%s&&%s\"x\"'", inputTextArgument("\$(reboot); `id` && \"x\""))
  }
}
