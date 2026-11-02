package dev.lemonadecode.workspace.impl

import dev.lemonadecode.domain.mobile.DeviceAvailability
import dev.lemonadecode.domain.mobile.DeviceLogs
import dev.lemonadecode.domain.update.AppInstallation
import dev.lemonadecode.domain.update.AppVersion
import dev.lemonadecode.domain.update.UpdateChannel
import dev.lemonadecode.domain.update.UpdateChecks
import dev.lemonadecode.domain.update.UpdateState
import dev.lemonadecode.platform.localization.asLemonadeCodeString
import dev.lemonadecode.workspace.impl.showcase.ShowcaseDevices
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.detail_three
import dev.lemonadecode.workspace.strings.detail_two
import dev.lemonadecode.workspace.strings.device_allow_debugging
import dev.lemonadecode.workspace.strings.device_android_version
import dev.lemonadecode.workspace.strings.device_usb
import dev.lemonadecode.workspace.strings.device_wifi
import dev.lemonadecode.workspace.strings.logs_cleared
import dev.lemonadecode.workspace.strings.logs_no_match
import dev.lemonadecode.workspace.strings.logs_waiting
import dev.lemonadecode.workspace.strings.time_days_ago
import dev.lemonadecode.workspace.strings.time_hours_ago
import dev.lemonadecode.workspace.strings.time_just_now
import dev.lemonadecode.workspace.strings.time_minutes_ago
import dev.lemonadecode.workspace.strings.time_yesterday
import dev.lemonadecode.workspace.strings.updates_development
import dev.lemonadecode.workspace.strings.updates_not_checked
import dev.lemonadecode.workspace.uimodel.LanguageOption
import dev.lemonadecode.workspace.uimodel.LogFilter
import dev.lemonadecode.workspace.uimodel.UpdatePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevicesMapperTest {
  private val minute = 60_000L
  private val now = 1_793_600_000_000L

  @Test
  fun `times read as how long ago they were`() {
    assertEquals(Res.string.time_just_now.asLemonadeCodeString(), timeAgo(now - 20_000, now))
    assertEquals(Res.plurals.time_minutes_ago.asLemonadeCodeString(5), timeAgo(now - 5 * minute, now))
    assertEquals(Res.plurals.time_hours_ago.asLemonadeCodeString(2), timeAgo(now - 125 * minute, now))
    assertEquals(Res.string.time_yesterday.asLemonadeCodeString(), timeAgo(now - 30 * 60 * minute, now))
    assertEquals(Res.plurals.time_days_ago.asLemonadeCodeString(4), timeAgo(now - 4 * 24 * 60 * minute, now))
    // A clock that went backwards still reads as now.
    assertEquals(Res.string.time_just_now.asLemonadeCodeString(), timeAgo(now + minute, now))
  }

  @Test
  fun `the computer's language picks the one Match system stands for`() {
    assertEquals(LanguageOption.Portuguese, SettingsMapper.languageFor("pt-BR"))
    assertEquals(LanguageOption.German, SettingsMapper.languageFor("de_AT"))
    assertEquals(LanguageOption.English, SettingsMapper.languageFor("ja-JP"))
    assertEquals(LanguageOption.English, SettingsMapper.languageFor(null))
  }

  @Test
  fun `a pairing code needs the address with its port and six digits`() {
    assertTrue(DevicesMapper.canPairWithCode("192.168.1.57:37099", "482913"))
    assertTrue(DevicesMapper.canPairWithCode(" 192.168.1.57:37099 ", "482913 "))
    assertFalse(DevicesMapper.canPairWithCode("192.168.1.57", "482913"))
    assertFalse(DevicesMapper.canPairWithCode("192.168.1.57:37099", "48291"))
    assertFalse(DevicesMapper.canPairWithCode("phone:37099", "482913"))
  }

  @Test
  fun `a device row says how it's connected and what it runs`() {
    val usb = DevicesMapper.row(ShowcaseDevices.pixel)
    assertEquals(
      Res.string.detail_two.asLemonadeCodeString(Res.string.device_usb.asLemonadeCodeString(), Res.string.device_android_version.asLemonadeCodeString("15")),
      usb.detail,
    )
    val wifi = DevicesMapper.row(ShowcaseDevices.galaxy)
    assertEquals(
      Res.string.detail_three.asLemonadeCodeString(
        Res.string.device_wifi.asLemonadeCodeString(),
        "192.168.1.42".asLemonadeCodeString(),
        Res.string.device_android_version.asLemonadeCodeString("14"),
      ),
      wifi.detail,
    )
    val waiting = DevicesMapper.row(ShowcaseDevices.pixel.copy(availability = DeviceAvailability.Unauthorized))
    assertFalse(waiting.ready)
    assertEquals(Res.string.device_allow_debugging.asLemonadeCodeString(), waiting.detail)
  }

  @Test
  fun `logs filter by level and text, and pausing stops at the line shown`() {
    val logs = ShowcaseDevices.androidLogs
    val all = DevicesMapper.logs(logs, DevicesViewState(), "claude")
    assertEquals(9, all.lines.size)
    assertEquals(4, all.warningCount)
    assertEquals(2, all.errorCount)

    val errors = DevicesMapper.logs(logs, DevicesViewState(logFilter = LogFilter.Errors), "claude")
    assertEquals(listOf("RateLimiter", "CheckoutViewModel"), errors.lines.map { it.tag })

    val searched = DevicesMapper.logs(logs, DevicesViewState(logQuery = "choreographer"), "claude")
    assertEquals(listOf("Choreographer"), searched.lines.map { it.tag })
    assertEquals(1, searched.warningCount)
    assertNull(searched.sendErrorsLabel)

    val paused = DevicesMapper.logs(logs, DevicesViewState(pausedAt = 5), "claude")
    assertTrue(paused.paused)
    assertEquals(4, paused.lines.size)

    val none = DevicesMapper.logs(logs, DevicesViewState(logQuery = "nothing like it"), "claude")
    assertEquals(Res.string.logs_no_match.asLemonadeCodeString(), none.emptyText)
  }

  @Test
  fun `an empty log says whether it was cleared or is still waiting`() {
    val empty = DeviceLogs(source = "logcat", lines = emptyList())
    assertEquals(Res.string.logs_waiting.asLemonadeCodeString(), DevicesMapper.logs(empty, DevicesViewState(), "claude").emptyText)
    assertEquals(Res.string.logs_cleared.asLemonadeCodeString(), DevicesMapper.logs(empty, DevicesViewState(logsCleared = true), "claude").emptyText)
  }

  @Test
  fun `errors can only be sent with a session to send them to`() {
    assertNull(DevicesMapper.logs(ShowcaseDevices.androidLogs, DevicesViewState(), agent = null).sendErrorsLabel)
  }

  @Test
  fun `an installation that doesn't update itself has nothing to press`() {
    val updates = SettingsMapper.updates(AppInstallation(null, UpdateChannel.Development), UpdateState.None, UpdateChecks(), now)
    assertEquals(UpdatePhase.Elsewhere, updates.phase)
    assertEquals(Res.string.updates_development.asLemonadeCodeString(), updates.status)
  }

  @Test
  fun `before the first check the Updates page says so`() {
    val updates = SettingsMapper.updates(AppInstallation(AppVersion(1, 2, 0), UpdateChannel.SelfUpdate), UpdateState.None, UpdateChecks(), now)
    assertEquals(UpdatePhase.UpToDate, updates.phase)
    assertEquals(Res.string.updates_not_checked.asLemonadeCodeString(), updates.status)
    assertEquals(UpdatePhase.Checking, SettingsMapper.updates(AppInstallation(AppVersion(1, 2, 0), UpdateChannel.SelfUpdate), UpdateState.None, UpdateChecks(checking = true), now).phase)
  }
}
