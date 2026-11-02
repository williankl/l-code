package dev.lemonadecode.mobile.impl

import dev.lemonadecode.domain.mobile.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosOutputTest {
  @Test
  fun `idevice_id lists UDIDs, with or without their connection`() {
    assertEquals(
      listOf("00008110-001A2C3E0E12801E", "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"),
      parseUdids("00008110-001A2C3E0E12801E\na1b2c3d4e5f60718293a4b5c6d7e8f9012345678\n"),
    )
    assertEquals(
      listOf("00008110-001A2C3E0E12801E", "00008030-000A1B2C3D4E5F60"),
      parseUdids("00008110-001A2C3E0E12801E (USB)\n00008030-000A1B2C3D4E5F60 (Network)\n00008110-001A2C3E0E12801E (Network)\n"),
    )
    assertEquals(emptyList(), parseUdids("ERROR: Unable to retrieve device list!\n"))
  }

  @Test
  fun `ideviceinfo keeps the top-level keys`() {
    val output = """
      BasebandVersion: 3.50.01
      DeviceName: Willian's iPhone
      NonVolatileRAM:
       auto-boot: dHJ1ZQ==
      ProductType: iPhone15,2
      ProductVersion: 18.1
    """.trimIndent()
    val info = parseIdeviceInfo(output)
    assertEquals("Willian's iPhone", info["DeviceName"])
    assertEquals("18.1", info["ProductVersion"])
    assertEquals("iPhone15,2", info["ProductType"])
    assertEquals(null, info["auto-boot"])
  }

  @Test
  fun `trust errors`() {
    assertTrue(isTrustError("ERROR: Could not connect to lockdownd: Pairing dialog response pending (-19)"))
    assertTrue(isTrustError("ERROR: Could not connect to lockdownd: Password protected (-17)"))
    assertTrue(isTrustError("ERROR: Could not connect to lockdownd: Invalid HostID (-21)"))
    assertFalse(isTrustError("ERROR: Could not connect to lockdownd: Muxer error (-8)"))
  }

  @Test
  fun `idevicepair answers`() {
    assertEquals(PairAttempt.Paired, parsePairAttempt("SUCCESS: Paired with device 00008110-001A2C3E0E12801E"))
    assertEquals(PairAttempt.WaitingForTrust, parsePairAttempt("ERROR: Please accept the trust dialog on the screen of device 00008110-001A2C3E0E12801E, then attempt to pair again."))
    assertEquals(PairAttempt.WaitingForTrust, parsePairAttempt("ERROR: Could not validate with device 00008110-001A2C3E0E12801E because a passcode is set. Please enter the passcode on the device and retry."))
    assertEquals(PairAttempt.WaitingForTrust, parsePairAttempt("ERROR: Device 00008110-001A2C3E0E12801E said that the user denied the trust dialog."))
    assertEquals(PairAttempt.NoDevice, parsePairAttempt("No device found."))
    assertEquals(PairAttempt.Failed, parsePairAttempt("ERROR: Pairing failed with error -5"))
  }

  @Test
  fun `screenshots a device can't take`() {
    assertTrue(isScreenshotUnsupported("Could not start screenshotr service: Invalid service\nRemember that you have to mount the Developer disk image on your device if you want to use the screenshotr service."))
    assertFalse(isScreenshotUnsupported("ERROR: Device 00008110-001A2C3E0E12801E not found!"))
  }

  @Test
  fun `syslog lines`() {
    val parser = SyslogParser()
    assertEquals(
      LogEntry("10:14:03", LogLevel.Info, "storefront(CFNetwork)", "Task <1A2B> finished"),
      parser.parse("Nov  2 10:14:03 iPhone storefront(CFNetwork)[1234] <Notice>: Task <1A2B> finished"),
    )
    assertEquals(
      LogEntry("10:14:03", LogLevel.Info, "storefront(CFNetwork)", "  more of the same message"),
      parser.parse("  more of the same message"),
    )
    assertEquals(
      LogEntry("10:14:04.123456", LogLevel.Error, "Storefront", "Payment failed"),
      parser.parse("\u001B[0;32mNov 12 10:14:04.123456\u001B[0m Willians-iPhone \u001B[1;36mStorefront\u001B[0m[88] <\u001B[31mError\u001B[0m>: Payment failed"),
    )
    val levels = listOf("Debug", "Info", "Notice", "Warning", "Error", "Fault", "Critical").map {
      parser.parse("Nov  2 10:14:03 iPhone kernel[0] <$it>: m").level
    }
    assertEquals(listOf(LogLevel.Debug, LogLevel.Info, LogLevel.Info, LogLevel.Warning, LogLevel.Error, LogLevel.Fatal, LogLevel.Fatal), levels)
  }
}
