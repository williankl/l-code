package dev.lemonadecode.workspace.impl

import dev.lemonadecode.domain.mobile.DeviceAvailability
import dev.lemonadecode.domain.mobile.DeviceLogLine
import dev.lemonadecode.domain.mobile.DeviceLogs
import dev.lemonadecode.domain.mobile.LogLevel
import dev.lemonadecode.domain.mobile.LogPackages
import dev.lemonadecode.domain.settings.DevicesLayout
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
import dev.lemonadecode.workspace.strings.logs_apps_in_front
import dev.lemonadecode.workspace.strings.logs_apps_loading
import dev.lemonadecode.workspace.strings.logs_apps_none
import dev.lemonadecode.workspace.strings.logs_apps_running
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
import dev.lemonadecode.workspace.uimodel.LogLevelModel
import dev.lemonadecode.workspace.uimodel.UpdatePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
  fun `a ready device's row offers a window of its own, and says when one is open`() {
    assertTrue(DevicesMapper.row(ShowcaseDevices.pixel).canOpenWindow)
    assertFalse(DevicesMapper.row(ShowcaseDevices.pixel).windowOpen)
    assertTrue(DevicesMapper.row(ShowcaseDevices.pixel, windowOpen = true).windowOpen)
    val waiting = ShowcaseDevices.pixel.copy(availability = DeviceAvailability.Unauthorized)
    assertFalse(DevicesMapper.row(waiting).canOpenWindow)
    // A window already open can still be closed from its row.
    assertTrue(DevicesMapper.row(waiting, windowOpen = true).canOpenWindow)
  }

  @Test
  fun `device windows are mapped in the order they opened, for devices still connected`() {
    val ids = listOf(ShowcaseDevices.galaxy.id, ShowcaseDevices.pixel7a.id, ShowcaseDevices.pixel.id)
    val view = WorkspaceViewState(deviceWindows = ids.associateWith { DeviceWindowState(DevicePageState(it)) })
    val input = MobileInput(enabled = true, devices = ShowcaseDevices.connected)
    val windows = DevicesMapper.windows(input, view, DevicesLayout.SideBySide, agent = "claude")
    assertEquals(listOf("Galaxy S23", "Pixel 8"), windows.map { it.name })
    assertTrue(windows.all { !it.panel.windowControls && !it.panel.canGoBack })
    assertEquals(emptyList(), DevicesMapper.windows(input.copy(enabled = false), view, DevicesLayout.SideBySide, agent = "claude"))
  }

  @Test
  fun `logs filter by level and text, and pausing stops at the line shown`() {
    val logs = ShowcaseDevices.androidLogs
    val all = DevicesMapper.logs(logs, DevicePageState(ShowcaseDevices.pixel.id), "claude")
    assertEquals(9, all.lines.size)
    assertEquals(2, all.warningCount)
    assertEquals(2, all.errorCount)

    val warnings = DevicesMapper.logs(logs, DevicePageState(ShowcaseDevices.pixel.id, logFilter = LogFilter.Warnings), "claude")
    assertEquals(listOf("RateLimiter", "Choreographer"), warnings.lines.map { it.tag })
    assertEquals(setOf(LogLevelModel.Warning), warnings.lines.map { it.level }.toSet())
    assertNull(warnings.sendErrorsLabel, "no error is shown, so there are none to send")

    val errors = DevicesMapper.logs(logs, DevicePageState(ShowcaseDevices.pixel.id, logFilter = LogFilter.Errors), "claude")
    assertEquals(listOf("RateLimiter", "CheckoutViewModel"), errors.lines.map { it.tag })

    val searched = DevicesMapper.logs(logs, DevicePageState(ShowcaseDevices.pixel.id, logQuery = "choreographer"), "claude")
    assertEquals(listOf("Choreographer"), searched.lines.map { it.tag })
    assertEquals(1, searched.warningCount)
    assertNull(searched.sendErrorsLabel)

    val paused = DevicesMapper.logs(logs, DevicePageState(ShowcaseDevices.pixel.id, pausedAt = 5), "claude")
    assertTrue(paused.paused)
    assertEquals(4, paused.lines.size)

    val none = DevicesMapper.logs(logs, DevicePageState(ShowcaseDevices.pixel.id, logQuery = "nothing like it"), "claude")
    assertEquals(Res.string.logs_no_match.asLemonadeCodeString(), none.emptyText)
  }

  @Test
  fun `warnings show warnings alone, and errors show fatal lines too, as their counts say`() {
    fun line(sequence: Long, level: LogLevel) = DeviceLogLine(sequence, "10:14:03.120", level, "Tag", "message $sequence")
    val levels = listOf(LogLevel.Verbose, LogLevel.Debug, LogLevel.Info, LogLevel.Warning, LogLevel.Error, LogLevel.Fatal)
    val logs = DeviceLogs(source = "logcat", lines = levels.mapIndexed { index, level -> line(index.toLong(), level) })

    val all = DevicesMapper.logs(logs, DevicePageState(ShowcaseDevices.pixel.id), "claude")
    assertEquals(6, all.lines.size)
    assertEquals(1, all.warningCount)
    assertEquals(2, all.errorCount)

    val warnings = DevicesMapper.logs(logs, DevicePageState(ShowcaseDevices.pixel.id, logFilter = LogFilter.Warnings), "claude")
    assertEquals(listOf(LogLevelModel.Warning), warnings.lines.map { it.level })
    assertEquals(warnings.warningCount, warnings.lines.size)

    val errors = DevicesMapper.logs(logs, DevicePageState(ShowcaseDevices.pixel.id, logFilter = LogFilter.Errors), "claude")
    assertEquals(listOf(LogLevelModel.Error, LogLevelModel.Fatal), errors.lines.map { it.level })
    assertEquals(errors.errorCount, errors.lines.size)
    assertEquals(2, DevicesMapper.shownErrors(logs, DevicePageState(ShowcaseDevices.pixel.id, logFilter = LogFilter.Errors)).size)
    assertEquals(emptyList(), DevicesMapper.shownErrors(logs, DevicePageState(ShowcaseDevices.pixel.id, logFilter = LogFilter.Warnings)))
  }

  @Test
  fun `the choice of apps follows the app in front at first, ticks the ones picked and says which run`() {
    val pixel = ShowcaseDevices.pixel
    val loading = assertNotNull(DevicesMapper.logApps(pixel, DevicePageState(ShowcaseDevices.pixel.id), apps = null))
    assertTrue(loading.followsAppInFront)
    assertFalse(loading.showsAll)
    assertEquals(Res.string.logs_apps_loading.asLemonadeCodeString(), loading.emptyText)

    val picked = assertNotNull(DevicesMapper.logApps(pixel, DevicePageState(ShowcaseDevices.pixel.id, logPackages = ShowcaseDevices.pickedApps, logAppsOpen = true), ShowcaseDevices.apps))
    assertTrue(picked.open)
    assertFalse(picked.followsAppInFront)
    assertFalse(picked.showsAll)
    assertNull(picked.emptyText)
    assertEquals(listOf("com.teya.storefront", "com.teya.pos"), picked.apps.filter { it.ticked }.map { it.packageName })
    assertEquals(Res.string.logs_apps_in_front.asLemonadeCodeString(), picked.apps[0].detail)
    assertEquals(Res.string.logs_apps_running.asLemonadeCodeString(), picked.apps[1].detail)
    assertNull(picked.apps[2].detail)

    // A ticked app the device no longer lists, e.g. uninstalled, stays at the end to be unticked.
    val gone = assertNotNull(DevicesMapper.logApps(pixel, DevicePageState(ShowcaseDevices.pixel.id, logPackages = LogPackages.Only(setOf("com.teya.gone"))), ShowcaseDevices.apps.take(1)))
    assertEquals(listOf("com.teya.storefront" to false, "com.teya.gone" to true), gone.apps.map { it.packageName to it.ticked })

    val all = assertNotNull(DevicesMapper.logApps(pixel, DevicePageState(ShowcaseDevices.pixel.id, logPackages = LogPackages.All), emptyList()))
    assertTrue(all.showsAll)
    assertEquals(Res.string.logs_apps_none.asLemonadeCodeString(), all.emptyText)

    assertNull(DevicesMapper.logApps(ShowcaseDevices.iphone, DevicePageState(ShowcaseDevices.pixel.id), ShowcaseDevices.apps), "an iPhone's log can't be narrowed")
  }

  @Test
  fun `an empty log says whether it was cleared or is still waiting`() {
    val empty = DeviceLogs(source = "logcat", lines = emptyList())
    assertEquals(Res.string.logs_waiting.asLemonadeCodeString(), DevicesMapper.logs(empty, DevicePageState(ShowcaseDevices.pixel.id), "claude").emptyText)
    assertEquals(Res.string.logs_cleared.asLemonadeCodeString(), DevicesMapper.logs(empty, DevicePageState(ShowcaseDevices.pixel.id, logsCleared = true), "claude").emptyText)
  }

  @Test
  fun `errors can only be sent with a session to send them to`() {
    assertNull(DevicesMapper.logs(ShowcaseDevices.androidLogs, DevicePageState(ShowcaseDevices.pixel.id), agent = null).sendErrorsLabel)
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
