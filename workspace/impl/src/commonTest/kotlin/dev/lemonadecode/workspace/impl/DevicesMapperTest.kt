package dev.lemonadecode.workspace.impl

import dev.lemonadecode.domain.mobile.DeviceAvailability
import dev.lemonadecode.domain.mobile.DeviceLogLine
import dev.lemonadecode.domain.mobile.DeviceLogs
import dev.lemonadecode.domain.mobile.DeviceScreen
import dev.lemonadecode.domain.mobile.LogLevel
import dev.lemonadecode.domain.mobile.LogPackages
import dev.lemonadecode.domain.mobile.MobileDevice
import dev.lemonadecode.domain.mobile.RecordingFailure
import dev.lemonadecode.domain.mobile.RecordingStop
import dev.lemonadecode.domain.mobile.ScreenPicture
import dev.lemonadecode.domain.mobile.ScreenRecording
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
import dev.lemonadecode.workspace.strings.mirror_clicks
import dev.lemonadecode.workspace.strings.mirror_fps
import dev.lemonadecode.workspace.strings.mirror_loading
import dev.lemonadecode.workspace.strings.mirror_record_screenshots
import dev.lemonadecode.workspace.strings.mirror_size
import dev.lemonadecode.workspace.strings.recording_failed_no_video
import dev.lemonadecode.workspace.strings.recording_failed_partly
import dev.lemonadecode.workspace.strings.recording_failed_unwritable
import dev.lemonadecode.workspace.strings.recording_saved
import dev.lemonadecode.workspace.strings.recording_saved_device_gone
import dev.lemonadecode.workspace.strings.recording_saved_partly
import dev.lemonadecode.workspace.strings.recording_saved_plugin_off
import dev.lemonadecode.workspace.strings.recording_saved_toast
import dev.lemonadecode.workspace.strings.recording_saved_videos
import dev.lemonadecode.workspace.strings.time_days_ago
import dev.lemonadecode.workspace.strings.time_hours_ago
import dev.lemonadecode.workspace.strings.time_just_now
import dev.lemonadecode.workspace.strings.time_minutes_ago
import dev.lemonadecode.workspace.strings.time_yesterday
import dev.lemonadecode.workspace.strings.updates_development
import dev.lemonadecode.workspace.strings.updates_not_checked
import dev.lemonadecode.workspace.uimodel.DeviceScreenModel
import dev.lemonadecode.workspace.uimodel.DevicesContentModel
import dev.lemonadecode.workspace.uimodel.LanguageOption
import dev.lemonadecode.workspace.uimodel.LogFilter
import dev.lemonadecode.workspace.uimodel.LogLevelModel
import dev.lemonadecode.workspace.uimodel.SavedRecordingModel
import dev.lemonadecode.workspace.uimodel.ScreenRecordingModel
import dev.lemonadecode.workspace.uimodel.UpdatePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
  fun `a frame mapped again is the same picture, so it isn't decoded again`() {
    val view = WorkspaceViewState(deviceWindows = mapOf(ShowcaseDevices.pixel.id to DeviceWindowState(DevicePageState(ShowcaseDevices.pixel.id))))
    val input = MobileInput(enabled = true, devices = ShowcaseDevices.connected, screens = mapOf(ShowcaseDevices.pixel.id to ShowcaseDevices.screen()))
    fun picture(from: MobileInput) = ((DevicesMapper.windows(from, view, DevicesLayout.SideBySide, "claude").single().panel.content as DevicesContentModel.Device).screen as DeviceScreenModel.Frame).picture
    assertEquals(picture(input), picture(input))
    val next = input.copy(screens = mapOf(ShowcaseDevices.pixel.id to DeviceScreen.Frame(ScreenPicture.Encoded(byteArrayOf(1, 2, 3)), 1080, 2400, 60f)))
    assertNotEquals(picture(input), picture(next))
  }

  @Test
  fun `an Android device records its screen while it comes as video, not as screenshots, and an iPhone can't`() {
    val video = DevicesMapper.recording(recording = null, frame = ShowcaseDevices.screen(), nowMillis = now)
    assertEquals(ScreenRecordingModel(), video)

    val screenshots = DevicesMapper.recording(recording = null, frame = ShowcaseDevices.screen(video = false), nowMillis = now)
    assertEquals(Res.string.mirror_record_screenshots.asLemonadeCodeString(), screenshots.unavailable)
    val loading = DevicesMapper.recording(recording = null, frame = null, nowMillis = now)
    assertEquals(Res.string.mirror_loading.asLemonadeCodeString(), loading.unavailable)

    // Under way, it can always be stopped, even if the screen fell back on screenshots meanwhile.
    val running = DevicesMapper.recording(ScreenRecording.Recording(startedAtMillis = now - 83_000), ShowcaseDevices.screen(video = false), now)
    assertEquals(ScreenRecordingModel(recording = true, elapsed = "1:23"), running)

    fun device(recording: ScreenRecording?, device: MobileDevice = ShowcaseDevices.pixel): DevicesContentModel.Device {
      val view = WorkspaceViewState(deviceWindows = mapOf(device.id to DeviceWindowState(DevicePageState(device.id))))
      val input = MobileInput(
        enabled = true,
        devices = ShowcaseDevices.connected,
        screens = mapOf(device.id to ShowcaseDevices.screen()),
        recordings = listOfNotNull(recording?.let { device.id to it }).toMap(),
        nowMillis = now,
      )
      return DevicesMapper.windows(input, view, DevicesLayout.SideBySide, "claude").single().panel.content as DevicesContentModel.Device
    }
    assertNull(device(recording = null, device = ShowcaseDevices.iphone).recording, "an iPhone has no record button")
    val fps = Res.string.mirror_fps.asLemonadeCodeString(60)
    val size = Res.string.mirror_size.asLemonadeCodeString(1080, 2400)
    val clicks = Res.string.mirror_clicks.asLemonadeCodeString()
    assertEquals(Res.string.detail_three.asLemonadeCodeString(size, fps, clicks), device(recording = null).screenDetail)
    // While it records, how long for takes the frame rate's place.
    val recording = device(ScreenRecording.Recording(startedAtMillis = now - 42_000))
    assertEquals("0:42", recording.recording?.elapsed)
    assertEquals(Res.string.detail_two.asLemonadeCodeString(size, clicks), recording.screenDetail)
  }

  @Test
  fun `a saved recording says how long it is and where it went, and opens in its folder`() {
    val saved = DevicesMapper.recording(ShowcaseDevices.savedRecording, ShowcaseDevices.screen(), now).saved
    assertEquals(
      SavedRecordingModel(
        label = Res.string.recording_saved.asLemonadeCodeString("0:42"),
        paths = "/Users/me/Movies/Lemonade Code/Pixel-8-20261125-101403.mp4",
        revealUri = "file:///Users/me/Movies/Lemonade%20Code/Pixel-8-20261125-101403.mp4",
      ),
      saved,
    )
    // The screen turned while recording: one video each way.
    val turned = ScreenRecording.Saved(listOf("/home/me/Videos/Lemonade Code/a.mp4", "/home/me/Videos/Lemonade Code/b.mp4"), durationMillis = 61_000)
    val both = assertNotNull(DevicesMapper.recording(turned, ShowcaseDevices.screen(), now).saved)
    assertEquals(Res.plurals.recording_saved_videos.asLemonadeCodeString(2, 2), both.label)
    assertEquals("/home/me/Videos/Lemonade Code/a.mp4\n/home/me/Videos/Lemonade Code/b.mp4", both.paths)
    assertEquals("file:///home/me/Videos/Lemonade%20Code/a.mp4", both.revealUri)
  }

  @Test
  fun `a recording that failed while writing still shows where what it wrote went`() {
    val partly = ScreenRecording.Failed(
      RecordingFailure.Unwritable("No space left on device"),
      listOf("/Users/me/Movies/Lemonade Code/Pixel-8-a.mp4", "/Users/me/Movies/Lemonade Code/Pixel-8-b.mp4"),
    )
    assertEquals(
      SavedRecordingModel(
        label = Res.string.recording_saved_partly.asLemonadeCodeString(),
        paths = "/Users/me/Movies/Lemonade Code/Pixel-8-a.mp4\n/Users/me/Movies/Lemonade Code/Pixel-8-b.mp4",
        revealUri = "file:///Users/me/Movies/Lemonade%20Code/Pixel-8-a.mp4",
      ),
      DevicesMapper.recording(partly, ShowcaseDevices.screen(), now).saved,
    )
    assertNull(DevicesMapper.recording(ScreenRecording.Failed(RecordingFailure.NoVideo), ShowcaseDevices.screen(), now).saved)
  }

  @Test
  fun `file URIs are percent-encoded, and Windows paths get their drive`() {
    assertEquals("file:///Users/Jos%C3%A9/Movies/a%23b.mp4", DevicesMapper.fileUri("/Users/José/Movies/a#b.mp4"))
    assertEquals("file:///C:/Users/me/Videos/Lemonade%20Code/a.mp4", DevicesMapper.fileUri("C:\\Users\\me\\Videos\\Lemonade Code\\a.mp4"))
  }

  @Test
  fun `how long a recording runs reads as minutes and seconds, then hours`() {
    assertEquals("0:00", DevicesMapper.elapsed(-5))
    assertEquals("0:42", DevicesMapper.elapsed(42_999))
    assertEquals("12:05", DevicesMapper.elapsed(725_000))
    assertEquals("1:02:05", DevicesMapper.elapsed(3_725_000))
  }

  @Test
  fun `a device being recorded says so in the list, with how long`() {
    assertNull(DevicesMapper.row(ShowcaseDevices.pixel).recordingElapsed)
    assertEquals("0:07", DevicesMapper.row(ShowcaseDevices.pixel, recording = ScreenRecording.Recording(now - 7_000), nowMillis = now).recordingElapsed)
    assertNull(DevicesMapper.row(ShowcaseDevices.pixel, recording = ShowcaseDevices.savedRecording, nowMillis = now).recordingElapsed)
  }

  @Test
  fun `a recording that ends says where it went, or why nothing was saved`() {
    val home = "/Users/me"
    assertEquals(
      Res.string.recording_saved_toast.asLemonadeCodeString("Pixel 8", "~/Movies/Lemonade Code") to false,
      DevicesMapper.recordingEnded(ShowcaseDevices.savedRecording, "Pixel 8", home),
    )
    assertEquals(
      Res.string.recording_saved_device_gone.asLemonadeCodeString("Pixel 8", "~/Movies/Lemonade Code") to false,
      DevicesMapper.recordingEnded(ShowcaseDevices.savedRecording.copy(stop = RecordingStop.DeviceGone), "Pixel 8", home),
    )
    assertEquals(
      Res.string.recording_saved_plugin_off.asLemonadeCodeString("Pixel 8", "~/Movies/Lemonade Code") to false,
      DevicesMapper.recordingEnded(ShowcaseDevices.savedRecording.copy(stop = RecordingStop.PluginOff), "Pixel 8", home),
    )
    assertEquals(
      Res.string.recording_failed_no_video.asLemonadeCodeString("Pixel 8") to true,
      DevicesMapper.recordingEnded(ScreenRecording.Failed(RecordingFailure.NoVideo), "Pixel 8", home),
    )
    // FFmpeg's and the system's own words are in English only, so the error says it in the app's language.
    assertEquals(
      Res.string.recording_failed_unwritable.asLemonadeCodeString() to true,
      DevicesMapper.recordingEnded(ScreenRecording.Failed(RecordingFailure.Unwritable("No space left on device")), "Pixel 8", home),
    )
    val partly = ScreenRecording.Failed(RecordingFailure.Unwritable("No space left on device"), listOf("/Users/me/Movies/Lemonade Code/Pixel-8.mp4"))
    assertEquals(
      Res.string.recording_failed_partly.asLemonadeCodeString("~/Movies/Lemonade Code") to true,
      DevicesMapper.recordingEnded(partly, "Pixel 8", home),
    )
    assertNull(DevicesMapper.recordingEnded(ScreenRecording.Recording(now), "Pixel 8", home))
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
