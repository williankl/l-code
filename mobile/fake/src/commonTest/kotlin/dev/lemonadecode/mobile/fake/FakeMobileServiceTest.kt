package dev.lemonadecode.mobile.fake

import dev.lemonadecode.domain.mobile.DeviceApp
import dev.lemonadecode.domain.mobile.DeviceId
import dev.lemonadecode.domain.mobile.DeviceLogs
import dev.lemonadecode.domain.mobile.LogPackages
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeMobileServiceTest {
  private val pixel = DeviceId("38021FDH2000AB")
  private val logs = DeviceLogs(source = "logcat · com.teya.storefront", lines = emptyList())

  @Test
  fun `logs are the ones set, and it remembers whose lines they were followed for`() = runTest {
    val mobile = FakeMobileService()
    mobile.logs.value = mapOf(pixel to logs)

    assertEquals(logs, mobile.logs(pixel).first())
    assertEquals(mapOf(pixel to LogPackages.AppInFront), mobile.logPackages.value)

    val _ = mobile.logs(pixel, LogPackages.Only(setOf("com.teya.storefront", "com.teya.pos"))).first()
    assertEquals(LogPackages.Only(setOf("com.teya.storefront", "com.teya.pos")), mobile.logPackages.value[pixel])
    val _ = mobile.logs(pixel, LogPackages.All).first()
    assertEquals(LogPackages.All, mobile.logPackages.value[pixel])
  }

  @Test
  fun `a device lists the apps set for it, and none otherwise`() = runTest {
    val mobile = FakeMobileService()
    val apps = listOf(DeviceApp("com.teya.storefront", running = true, inFront = true), DeviceApp("com.teya.pos"))
    mobile.apps.value = mapOf(pixel to apps)

    assertEquals(apps, mobile.apps(pixel).first())
    assertEquals(emptyList(), mobile.apps(DeviceId("00008130-001A2C3E")).first())
  }

  @Test
  fun `clearing a device's logs forgets its lines and says so`() = runTest {
    val mobile = FakeMobileService()
    mobile.logs.value = mapOf(pixel to logs)
    mobile.clearLogs(pixel)
    assertEquals(emptyList(), mobile.logs(pixel).first().lines)
    assertEquals(listOf("clear 38021FDH2000AB"), mobile.actions)
  }
}
