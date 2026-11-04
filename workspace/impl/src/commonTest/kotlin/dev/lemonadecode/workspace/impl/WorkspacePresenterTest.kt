package dev.lemonadecode.workspace.impl

import dev.lemonadecode.domain.mobile.LogPackages
import dev.lemonadecode.domain.mobile.MobileError
import dev.lemonadecode.domain.review.DiffState
import dev.lemonadecode.domain.review.Review
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.settings.AppLanguage
import dev.lemonadecode.domain.settings.ThemePreference
import dev.lemonadecode.domain.update.AppRelease
import dev.lemonadecode.domain.update.AppVersion
import dev.lemonadecode.domain.update.UpdateChecks
import dev.lemonadecode.domain.update.UpdateFailure
import dev.lemonadecode.domain.update.UpdateProgress
import dev.lemonadecode.domain.update.UpdateState
import dev.lemonadecode.mobile.fake.FakeMobileService
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.localization.asLemonadeCodeString
import dev.lemonadecode.platform.presenter.testing.awaitItemWhere
import dev.lemonadecode.platform.presenter.testing.test
import dev.lemonadecode.platform.presenter.testing.yieldUntil
import dev.lemonadecode.platform.process.HomeDirectory
import dev.lemonadecode.review.fake.FakeReviewService
import dev.lemonadecode.sessions.fake.FakeSessionService
import dev.lemonadecode.settings.fake.FakeSettingsService
import dev.lemonadecode.updates.fake.FakeUpdateService
import dev.lemonadecode.workspace.impl.showcase.ShowcaseDevices
import dev.lemonadecode.workspace.impl.showcase.ShowcaseFixtures
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.file_path_copied
import dev.lemonadecode.workspace.strings.session_path_copied
import dev.lemonadecode.workspace.strings.time_hours_ago
import dev.lemonadecode.workspace.strings.update_failed_checksum
import dev.lemonadecode.workspace.strings.updates_up_to_date
import dev.lemonadecode.workspace.strings.worktree_branch_invalid
import dev.lemonadecode.workspace.strings.worktree_branch_taken
import dev.lemonadecode.workspace.uimodel.DeviceScreenModel
import dev.lemonadecode.workspace.uimodel.DevicesButtonModel
import dev.lemonadecode.workspace.uimodel.DevicesContentModel
import dev.lemonadecode.workspace.uimodel.DevicesLayoutOption
import dev.lemonadecode.workspace.uimodel.DevicesPanelModel
import dev.lemonadecode.workspace.uimodel.DevicesWindow
import dev.lemonadecode.workspace.uimodel.DiffRowModel
import dev.lemonadecode.workspace.uimodel.LanguageOption
import dev.lemonadecode.workspace.uimodel.LogFilter
import dev.lemonadecode.workspace.uimodel.MobilePlatformModel
import dev.lemonadecode.workspace.uimodel.PairingMethod
import dev.lemonadecode.workspace.uimodel.ReleaseNotesModel
import dev.lemonadecode.workspace.uimodel.SessionMenuModel
import dev.lemonadecode.workspace.uimodel.SessionRenameModel
import dev.lemonadecode.workspace.uimodel.SessionsPanelModel
import dev.lemonadecode.workspace.uimodel.SettingsPage
import dev.lemonadecode.workspace.uimodel.ThemeOption
import dev.lemonadecode.workspace.uimodel.UpdatePhase
import dev.lemonadecode.workspace.uimodel.WorkspaceContentModel
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import dev.lemonadecode.workspace.uimodel.WorkspaceModel
import dev.lemonadecode.workspace.uimodel.WorkspaceView
import dev.lemonadecode.workspace.uimodel.WorktreeBranchModel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.lemonadecode.workspace.uimodel.SubSessionLocation as LocationModel

class WorkspacePresenterTest {
  private val ar = SessionId("ar")
  private val sessions = FakeSessionService()
  private val reviews = FakeReviewService()
  private val settings = FakeSettingsService()
  private val updates = FakeUpdateService()
  private val mobile = FakeMobileService()
  private val presenter = WorkspacePresenter(
    sessions,
    reviews,
    settings,
    object : HomeDirectory {
      override val path: String = ShowcaseFixtures.Home
    },
    updates,
    mobile,
  ) { Now }

  private fun withSession() {
    sessions.setSessions(listOf(ShowcaseFixtures.session("ar", "api-ratelimit")))
    reviews.setReview(ar, Review(DiffState.Ready(ShowcaseFixtures.snapshot), emptyList()))
  }

  @Test
  fun `with no sessions it offers a new terminal at home`() = runTest {
    presenter.test {
      val empty = awaitItem()
      assertIs<WorkspaceContentModel.NoSessions>(empty.content)
      empty.eventSink(WorkspaceEvent.NewSession)
      yieldUntil { sessions.opened.isNotEmpty() }
      assertEquals(listOf<String?>(null), sessions.opened)
    }
  }

  @Test
  fun `opening the diff refreshes it and shows the files`() = runTest {
    withSession()
    presenter.test {
      val terminal = awaitItemWhere { it.content is WorkspaceContentModel.Terminal }
      terminal.eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      val diff = awaitItemWhere { it.content is WorkspaceContentModel.Diff }
      assertEquals(6, assertIs<WorkspaceContentModel.Diff>(diff.content).review.files.size)
      yieldUntil { reviews.refreshed.contains(ar) }
    }
  }

  @Test
  fun `the diff tab compares with a branch picked from the list`() = runTest {
    withSession()
    reviews.setReview(ar, Review(DiffState.Ready(ShowcaseFixtures.snapshot), emptyList(), base = "main", bases = listOf("main", "release")))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      awaitItemWhere { it.content is WorkspaceContentModel.Diff }.eventSink(WorkspaceEvent.ToggleDiffBasePicker)
      val open = awaitItemWhere { (it.content as? WorkspaceContentModel.Diff)?.review?.toolbar?.base?.pickerOpen == true }
      val base = assertIs<WorkspaceContentModel.Diff>(open.content).review.toolbar.base
      assertEquals("main", base.selected)
      assertEquals(listOf("main", "release", "HEAD"), base.options.map { it.name })
      open.eventSink(WorkspaceEvent.CompareWith("release"))
      awaitItemWhere { (it.content as? WorkspaceContentModel.Diff)?.review?.toolbar?.base?.pickerOpen == false }
      yieldUntil { reviews.comparedWith.isNotEmpty() }
      assertEquals(listOf(ar to "release"), reviews.comparedWith)
    }
  }

  @Test
  fun `with no changes the diff still offers the branch picker`() = runTest {
    withSession()
    reviews.setReview(ar, Review(DiffState.Ready(ShowcaseFixtures.snapshot.copy(files = emptyList())), emptyList(), base = "main", bases = listOf("main", "release")))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      val message = awaitItemWhere { it.content is WorkspaceContentModel.Message && it.viewSwitcher?.selected == WorkspaceView.Diff }
      assertEquals(listOf("main", "release", "HEAD"), assertIs<WorkspaceContentModel.Message>(message.content).diffToolbar?.base?.options?.map { it.name })
    }
  }

  @Test
  fun `dragging across lines opens the composer and adding posts the comment`() = runTest {
    withSession()
    val path = "src/middleware/rateLimit.ts"
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      val diff = awaitItemWhere { it.content is WorkspaceContentModel.Diff }
      diff.eventSink(WorkspaceEvent.StartCommentRange(path, 27))
      diff.eventSink(WorkspaceEvent.DragCommentRange(path, 31))
      diff.eventSink(WorkspaceEvent.EndCommentRange)
      val composing = awaitItemWhere { model -> model.lines(path).any { it.composer != null } }
      assertEquals((28..32).toList(), composing.lines(path).filter { it.selected }.map { it.newNumber })
      composing.eventSink(WorkspaceEvent.UpdateCommentDraft("Return early at the cap."))
      awaitItemWhere { model -> model.lines(path).any { it.composer?.canSubmit == true } }.eventSink(WorkspaceEvent.AddComment)

      val posted = awaitItemWhere { model -> model.lines(path).any { it.comments.isNotEmpty() } }
      val comment = reviews.reviewOf(ar).comments.single()
      assertEquals(28 to 32, comment.start.line to comment.end.line)
      assertEquals(1, posted.viewSwitcher?.unsentCommentCount)
    }
  }

  @Test
  fun `sending returns to the terminal`() = runTest {
    withSession()
    reviews.setReview(ar, Review(DiffState.Ready(ShowcaseFixtures.snapshot), listOf(ShowcaseFixtures.comment("rl", 17))))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      val diff = awaitItemWhere { it.content is WorkspaceContentModel.Diff }
      diff.eventSink(WorkspaceEvent.SendToSession)
      yieldUntil { reviews.sentToSession.contains(ar) }
      awaitItemWhere { it.content is WorkspaceContentModel.Terminal }
    }
  }

  @Test
  fun `theme and keep awake are saved as settings`() = runTest {
    withSession()
    presenter.test {
      val model = awaitItem()
      model.eventSink(WorkspaceEvent.SetTheme(ThemeOption.Light))
      model.eventSink(WorkspaceEvent.SetKeepAwake(true))
      val updated = awaitItemWhere { it.settings.theme == ThemeOption.Light && it.settings.keepAwake }
      assertTrue(updated.settings.keepAwake)
      assertEquals(ThemePreference.Light, settings.settings.value.theme)
    }
  }

  @Test
  fun `an update puts a dot on the gear, and its page downloads it and then restarts`() = runTest {
    val available = UpdateState.Available(
      current = AppVersion(1, 2, 0),
      release = AppRelease(AppVersion(1, 3, 0), "https://github.com/williankl/loupe/releases/tag/v1.3.0", "Faster diffs"),
    )
    presenter.test {
      assertEquals(false, awaitItem().topBar.updateAttention)
      updates.setState(available)
      val offered = awaitItemWhere { it.topBar.updateAttention }
      assertEquals(UpdatePhase.Available, offered.settings.updates.phase)
      assertEquals(ReleaseNotesModel("1.3.0", "Faster diffs", available.release.pageUrl), offered.settings.updates.release)

      offered.eventSink(WorkspaceEvent.ToggleSettings)
      awaitItemWhere { it.settings.open }.eventSink(WorkspaceEvent.OpenSettingsPage(SettingsPage.Updates))
      awaitItemWhere { it.settings.page == SettingsPage.Updates }.eventSink(WorkspaceEvent.InstallUpdate)
      assertEquals(1, updates.installCount)

      updates.setState(available.copy(progress = UpdateProgress.Downloading(0.5f)))
      val downloading = awaitItemWhere { it.settings.updates.phase == UpdatePhase.Downloading }
      assertEquals(0.5f, downloading.settings.updates.progress)
      updates.setState(available.copy(progress = UpdateProgress.Downloaded))
      awaitItemWhere { it.settings.updates.phase == UpdatePhase.Downloaded }.eventSink(WorkspaceEvent.RestartToUpdate)
      assertEquals(1, updates.restartCount)

      updates.setState(available.copy(progress = UpdateProgress.Failed(UpdateFailure.Checksum)))
      val failed = awaitItemWhere { it.settings.updates.phase == UpdatePhase.Failed }
      assertEquals(Res.string.update_failed_checksum.asLemonadeCodeString(), failed.settings.updates.status)
    }
  }

  @Test
  fun `with nothing newer the Updates page says when it last checked, and checks again`() = runTest {
    updates.setChecks(UpdateChecks(lastCheckedMillis = Now - 2 * 60 * 60_000))
    presenter.test {
      val model = awaitItem()
      assertEquals(UpdatePhase.UpToDate, model.settings.updates.phase)
      assertEquals(Res.string.updates_up_to_date.asLemonadeCodeString(Res.plurals.time_hours_ago.asLemonadeCodeString(2)), model.settings.updates.status)
      model.eventSink(WorkspaceEvent.CheckForUpdates)
      yieldUntil { updates.checkCount == 1 }
    }
  }

  @Test
  fun `each settings item opens its own page, and Esc goes back to the list, then closes`() = runTest {
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleSettings)
      val list = awaitItemWhere { it.settings.open }
      assertEquals(SettingsPage.List, list.settings.page)
      list.eventSink(WorkspaceEvent.OpenSettingsPage(SettingsPage.Language))
      awaitItemWhere { it.settings.page == SettingsPage.Language }.eventSink(WorkspaceEvent.SettingsBack)
      awaitItemWhere { it.settings.page == SettingsPage.List && it.settings.open }.eventSink(WorkspaceEvent.SettingsBack)
      awaitItemWhere { !it.settings.open }
    }
  }

  @Test
  fun `picking a language keeps it`() = runTest {
    presenter.test {
      val first = awaitItem()
      assertEquals(LanguageOption.System, first.settings.language)
      first.eventSink(WorkspaceEvent.SetLanguage(LanguageOption.German))
      assertEquals(LanguageOption.German, awaitItemWhere { it.settings.language == LanguageOption.German }.settings.language)
      assertEquals(AppLanguage.German, settings.settings.value.language)
    }
  }

  @Test
  fun `turning the Mobile plugin on shows the phone button with the devices connected`() = runTest {
    mobile.setDevices(ShowcaseDevices.connected)
    presenter.test {
      val off = awaitItem()
      assertEquals(null, off.topBar.devices)
      off.eventSink(WorkspaceEvent.SetMobilePlugin(true))
      val on = awaitItemWhere { it.topBar.devices != null }
      assertEquals(DevicesButtonModel(count = 4, docked = false, separate = false), on.topBar.devices)
      assertEquals(SettingsMapper.mobile(true, ShowcaseDevices.connected), on.settings.mobile)

      on.eventSink(WorkspaceEvent.ToggleDevices)
      val open = awaitItemWhere { it.devices != null }
      val list = assertIs<DevicesContentModel.Devices>(open.devices?.content)
      assertEquals(listOf(2, 2), list.groups.map { it.devices.size })
      assertEquals(true, open.topBar.devices?.docked)

      open.eventSink(WorkspaceEvent.SetMobilePlugin(false))
      val closed = awaitItemWhere { it.topBar.devices == null }
      assertEquals(null, closed.devices)
    }
  }

  @Test
  fun `the docked devices panel keeps one width for a device's screen and logs together, and one for the rest`() = runTest {
    settings.update { it.copy(mobilePlugin = true) }
    mobile.setDevices(ShowcaseDevices.connected)
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleDevices)
      val list = awaitItemWhere { it.devices != null }
      assertEquals(DevicesPanelModel.DefaultWidth, list.devices?.width)
      list.eventSink(WorkspaceEvent.ResizeDevicesPanel(500))
      val resized = awaitItemWhere { it.devices?.width == 500 }
      assertEquals(500, settings.settings.value.devicesPanelWidth)

      // A device's screen and logs together start wider, and dragging them leaves the list's width alone.
      resized.eventSink(WorkspaceEvent.SelectDevice(ShowcaseDevices.pixel.id.value))
      val device = awaitItemWhere { it.devices?.content is DevicesContentModel.Device }
      assertEquals(DevicesPanelModel.DefaultWideWidth, device.devices?.width)
      device.eventSink(WorkspaceEvent.ResizeDevicesPanel(5_000))
      val widest = awaitItemWhere { it.devices?.width == DevicesPanelModel.MaxWidth }
      assertEquals(DevicesPanelModel.MaxWidth, settings.settings.value.devicesPanelWideWidth)
      assertEquals(500, settings.settings.value.devicesPanelWidth)

      // In tabs the device takes the narrow width again.
      widest.eventSink(WorkspaceEvent.SetDevicesLayout(DevicesLayoutOption.Tabs))
      awaitItemWhere { it.devices?.width == 500 }
    }
  }

  @Test
  fun `a device shows its screen and logs, and its errors go to the session's agent`() = runTest {
    withSession()
    settings.update { it.copy(mobilePlugin = true) }
    mobile.setDevices(ShowcaseDevices.connected)
    mobile.logs.value = mapOf(ShowcaseDevices.pixel.id to ShowcaseDevices.androidLogs)
    mobile.screens.value = mapOf(ShowcaseDevices.pixel.id to ShowcaseDevices.screen())
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleDevices)
      awaitItemWhere { it.devices != null }.eventSink(WorkspaceEvent.SelectDevice(ShowcaseDevices.pixel.id.value))
      val device = awaitItemWhere { (it.devices?.content as? DevicesContentModel.Device)?.logs?.lines?.isNotEmpty() == true }
      val page = assertIs<DevicesContentModel.Device>(device.devices?.content)
      assertIs<DeviceScreenModel.Frame>(page.screen)
      assertEquals(2, page.logs.errorCount)
      assertEquals("logcat · com.teya.storefront", page.logs.source)

      device.eventSink(WorkspaceEvent.SetLogFilter(LogFilter.Errors))
      val errors = awaitItemWhere { (it.devices?.content as? DevicesContentModel.Device)?.logs?.filter == LogFilter.Errors }
      assertEquals(2, (errors.devices?.content as DevicesContentModel.Device).logs.lines.size)
      errors.eventSink(WorkspaceEvent.SendLogErrors)
      yieldUntil { sessions.pasted.isNotEmpty() }
      val (to, text) = sessions.pasted.single()
      assertEquals(ar, to)
      assertTrue(text.startsWith("Errors from Pixel 8 (logcat · com.teya.storefront):\n"))
      assertTrue("RateLimiter: 429 Too Many Requests" in text)

      errors.eventSink(WorkspaceEvent.TapDevice(540, 1200))
      errors.eventSink(WorkspaceEvent.PressDeviceHome)
      yieldUntil { mobile.actions.size >= 2 }
      assertEquals(listOf("tap 38021FDH2000AB 540,1200", "press 38021FDH2000AB Home"), mobile.actions)
    }
  }

  @Test
  fun `a device's log follows the app in front until apps are ticked, one, several or none`() = runTest {
    settings.update { it.copy(mobilePlugin = true) }
    mobile.setDevices(ShowcaseDevices.connected)
    mobile.logs.value = mapOf(ShowcaseDevices.pixel.id to ShowcaseDevices.androidLogs)
    mobile.apps.value = mapOf(ShowcaseDevices.pixel.id to ShowcaseDevices.apps)
    fun WorkspaceModel.logs() = (devices?.content as? DevicesContentModel.Device)?.logs
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleDevices)
      awaitItemWhere { it.devices != null }.eventSink(WorkspaceEvent.SelectDevice(ShowcaseDevices.pixel.id.value))
      val front = awaitItemWhere { it.logs()?.lines?.isNotEmpty() == true }
      assertEquals(true, front.logs()?.apps?.followsAppInFront)
      assertEquals(LogPackages.AppInFront, mobile.logPackages.value[ShowcaseDevices.pixel.id])

      front.eventSink(WorkspaceEvent.ToggleLogsPaused)
      awaitItemWhere { it.logs()?.paused == true }.eventSink(WorkspaceEvent.ToggleLogApps)
      val open = awaitItemWhere { it.logs()?.apps?.apps?.isNotEmpty() == true }
      assertEquals(true, open.logs()?.apps?.open)
      assertEquals(ShowcaseDevices.apps.map { it.packageName }, open.logs()?.apps?.apps?.map { it.packageName })

      open.eventSink(WorkspaceEvent.ToggleLogApp("com.teya.pos"))
      val one = awaitItemWhere { it.logs()?.apps?.apps?.any { app -> app.ticked } == true }
      assertEquals(listOf("com.teya.pos"), one.logs()?.apps?.apps?.filter { it.ticked }?.map { it.packageName })
      assertEquals(false, one.logs()?.paused, "a new choice reads the log again, live")
      assertEquals(true, one.logs()?.apps?.open, "ticking keeps the list open for more")
      yieldUntil { mobile.logPackages.value[ShowcaseDevices.pixel.id] == LogPackages.Only(setOf("com.teya.pos")) }

      one.eventSink(WorkspaceEvent.ToggleLogApp("com.teya.storefront"))
      yieldUntil { mobile.logPackages.value[ShowcaseDevices.pixel.id] == LogPackages.Only(setOf("com.teya.pos", "com.teya.storefront")) }
      val two = awaitItemWhere { it.logs()?.apps?.apps?.count { app -> app.ticked } == 2 }

      two.eventSink(WorkspaceEvent.ToggleLogApp("com.teya.pos"))
      two.eventSink(WorkspaceEvent.ToggleLogApp("com.teya.storefront"))
      val none = awaitItemWhere { it.logs()?.apps?.showsAll == true }
      assertEquals(emptyList(), none.logs()?.apps?.apps?.filter { it.ticked })
      yieldUntil { mobile.logPackages.value[ShowcaseDevices.pixel.id] == LogPackages.All }

      none.eventSink(WorkspaceEvent.FollowAppInFront)
      val back = awaitItemWhere { it.logs()?.apps?.followsAppInFront == true }
      assertEquals(false, back.logs()?.apps?.open)
      yieldUntil { mobile.logPackages.value[ShowcaseDevices.pixel.id] == LogPackages.AppInFront }

      back.eventSink(WorkspaceEvent.ShowAllApps)
      awaitItemWhere { it.logs()?.apps?.showsAll == true }.eventSink(WorkspaceEvent.DevicesBack)
      awaitItemWhere { it.devices?.content is DevicesContentModel.Devices }.eventSink(WorkspaceEvent.SelectDevice(ShowcaseDevices.pixel.id.value))
      val again = awaitItemWhere { it.logs() != null }
      assertEquals(true, again.logs()?.apps?.followsAppInFront, "opening a device follows its app in front")

      again.eventSink(WorkspaceEvent.DevicesBack)
      awaitItemWhere { it.devices?.content is DevicesContentModel.Devices }.eventSink(WorkspaceEvent.SelectDevice(ShowcaseDevices.iphone.id.value))
      val iphone = awaitItemWhere { (it.devices?.content as? DevicesContentModel.Device)?.platform == MobilePlatformModel.Ios }
      assertNull(iphone.logs()?.apps, "an iPhone's log can't be narrowed")
    }
  }

  @Test
  fun `a screenshot is handed to the session as a path`() = runTest {
    withSession()
    settings.update { it.copy(mobilePlugin = true) }
    mobile.setDevices(ShowcaseDevices.connected)
    mobile.screenshotPath = "/tmp/lemonade-code/screenshots/Pixel 8-20261102.png"
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleDevices)
      awaitItemWhere { it.devices != null }.eventSink(WorkspaceEvent.SelectDevice(ShowcaseDevices.pixel.id.value))
      awaitItemWhere { it.devices?.content is DevicesContentModel.Device }.eventSink(WorkspaceEvent.ScreenshotToSession)
      yieldUntil { sessions.pasted.isNotEmpty() }
      assertEquals(ar to "/tmp/lemonade-code/screenshots/Pixel\\ 8-20261102.png ", sessions.pasted.single())
      awaitItemWhere { it.toast != null }
    }
  }

  @Test
  fun `pairing with a code goes through the three steps and opens the new device`() = runTest {
    settings.update { it.copy(mobilePlugin = true) }
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleDevices)
      awaitItemWhere { it.devices != null }.eventSink(WorkspaceEvent.StartPairing)
      awaitItemWhere { it.devices?.content == DevicesContentModel.ChoosePlatform }.eventSink(WorkspaceEvent.ChoosePairingPlatform(MobilePlatformModel.Android))
      awaitItemWhere { it.devices?.content is DevicesContentModel.ChooseMethod }.eventSink(WorkspaceEvent.ChoosePairingMethod(PairingMethod.PairingCode))
      val code = awaitItemWhere { it.devices?.content is DevicesContentModel.EnterCode }
      assertEquals(false, (code.devices?.content as DevicesContentModel.EnterCode).canPair)
      code.eventSink(WorkspaceEvent.SetPairingAddress("192.168.1.57:37099"))
      code.eventSink(WorkspaceEvent.SetPairingCode("48 29 13"))
      val ready = awaitItemWhere { (it.devices?.content as? DevicesContentModel.EnterCode)?.canPair == true }
      assertEquals("482913", (ready.devices?.content as DevicesContentModel.EnterCode).code)
      ready.eventSink(WorkspaceEvent.PairWithCode)
      val paired = awaitItemWhere { it.devices?.content is DevicesContentModel.Paired }
      assertEquals(listOf("pair 192.168.1.57:37099 482913"), mobile.actions)
      paired.eventSink(WorkspaceEvent.OpenPairedDevice)
      awaitItemWhere { it.devices?.content is DevicesContentModel.Device }
    }
  }

  @Test
  fun `a wrong code stays on the step and says so`() = runTest {
    settings.update { it.copy(mobilePlugin = true) }
    mobile.nextPairResult = LemonadeCodeResult.Failure(MobileError.WrongCode)
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleDevices)
      awaitItemWhere { it.devices != null }.eventSink(WorkspaceEvent.StartPairing)
      awaitItemWhere { it.devices?.content == DevicesContentModel.ChoosePlatform }.eventSink(WorkspaceEvent.ChoosePairingPlatform(MobilePlatformModel.Android))
      awaitItemWhere { it.devices?.content is DevicesContentModel.ChooseMethod }.eventSink(WorkspaceEvent.ChoosePairingMethod(PairingMethod.PairingCode))
      val code = awaitItemWhere { it.devices?.content is DevicesContentModel.EnterCode }
      code.eventSink(WorkspaceEvent.SetPairingAddress("192.168.1.57:37099"))
      code.eventSink(WorkspaceEvent.SetPairingCode("000000"))
      awaitItemWhere { (it.devices?.content as? DevicesContentModel.EnterCode)?.canPair == true }.eventSink(WorkspaceEvent.PairWithCode)
      val wrong = awaitItemWhere { (it.devices?.content as? DevicesContentModel.EnterCode)?.error != null }
      assertEquals(DevicesMapper.errorText(MobileError.WrongCode), (wrong.devices?.content as DevicesContentModel.EnterCode).error)
      wrong.eventSink(WorkspaceEvent.DevicesBack)
      awaitItemWhere { it.devices?.content is DevicesContentModel.ChooseMethod }
    }
  }

  @Test
  fun `the panel moves to its own window and back, and the phone button brings that window forward`() = runTest {
    settings.update { it.copy(mobilePlugin = true) }
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleDevices)
      awaitItemWhere { it.devices != null }.eventSink(WorkspaceEvent.MoveDevices(DevicesWindow.Separate))
      val separate = awaitItemWhere { it.devices?.window == DevicesWindow.Separate }
      assertEquals(DevicesButtonModel(count = 0, docked = false, separate = true), separate.topBar.devices)
      val before = separate.devices?.focusRequest ?: 0
      separate.eventSink(WorkspaceEvent.ToggleDevices)
      awaitItemWhere { (it.devices?.focusRequest ?: 0) > before }.eventSink(WorkspaceEvent.MoveDevices(DevicesWindow.Docked))
      awaitItemWhere { it.devices?.window == DevicesWindow.Docked }.eventSink(WorkspaceEvent.CloseDevices)
      awaitItemWhere { it.devices == null }
    }
  }

  @Test
  fun `the changed files can be hidden, and picking a folded one opens it`() = runTest {
    withSession()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      val diff = awaitItemWhere { it.content is WorkspaceContentModel.Diff }
      assertEquals(null, (diff.content as WorkspaceContentModel.Diff).review.fileList)
      diff.eventSink(WorkspaceEvent.ShowFileList(false))
      awaitItemWhere { (it.content as? WorkspaceContentModel.Diff)?.review?.fileList == false }
      val folded = "src/config.ts"
      diff.eventSink(WorkspaceEvent.ToggleFileExpanded(folded))
      awaitItemWhere { (it.content as? WorkspaceContentModel.Diff)?.review?.files?.first { file -> file.id == folded }?.expanded == false }
      diff.eventSink(WorkspaceEvent.RevealFile(folded))
      awaitItemWhere { (it.content as? WorkspaceContentModel.Diff)?.review?.files?.first { file -> file.id == folded }?.expanded == true }
    }
  }

  @Test
  fun `the sidebar width is saved, kept within its limits`() = runTest {
    withSession()
    presenter.test {
      val model = awaitItem()
      assertEquals(232, model.sessions.sidebarWidth)
      model.eventSink(WorkspaceEvent.ResizeSessionsSidebar(300))
      assertEquals(300, awaitItemWhere { it.sessions.sidebarWidth != 232 }.sessions.sidebarWidth)
      assertEquals(300, settings.settings.value.sidebarWidth)

      model.eventSink(WorkspaceEvent.ResizeSessionsSidebar(5_000))
      awaitItemWhere { it.sessions.sidebarWidth == SessionsPanelModel.MaxSidebarWidth }
      assertEquals(SessionsPanelModel.MaxSidebarWidth, settings.settings.value.sidebarWidth)
    }
  }

  @Test
  fun `a new sub-session opens on the last location and gets selected`() = runTest {
    withSession()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleNewSubSessionMenu)
      val menu = awaitItemWhere { it.newSubSession.open }
      menu.eventSink(WorkspaceEvent.SetSubSessionLocation(dev.lemonadecode.workspace.uimodel.SubSessionLocation.NewWorktree))
      awaitItemWhere { it.newSubSession.location == dev.lemonadecode.workspace.uimodel.SubSessionLocation.NewWorktree }
        .eventSink(WorkspaceEvent.CreateSubSession)
      val created = awaitItemWhere { it.header?.name == "sub-1" }
      assertEquals(false, created.newSubSession.open)
      assertEquals(dev.lemonadecode.domain.session.SubSessionLocation.NewWorktree, settings.settings.value.subSessionLocation)
    }
  }

  @Test
  fun `a new worktree from the panel suggests a branch, follows what is typed and is created on it`() = runTest {
    withSession()
    sessions.worktreeBranchSuggestion = "worktree/generating-bugs"
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleNewSubSessionMenu)
      awaitItemWhere { it.newSubSession.open }.eventSink(WorkspaceEvent.SetSubSessionLocation(LocationModel.NewWorktree))
      val suggested = awaitItemWhere { it.newSubSession.worktreeBranch != null }
      assertEquals(
        WorktreeBranchModel(name = "worktree/generating-bugs", path = "~/code/storefront.worktrees/generating-bugs", problem = null, canCreate = true),
        suggested.newSubSession.worktreeBranch,
      )

      suggested.eventSink(WorkspaceEvent.SetWorktreeBranch("feat/login"))
      val typed = awaitItemWhere { it.newSubSession.worktreeBranch?.name == "feat/login" }
      assertEquals("~/code/storefront.worktrees/login", typed.newSubSession.worktreeBranch?.path)

      typed.eventSink(WorkspaceEvent.CreateSubSession)
      // Enter held down, or pressed again while git works: still one worktree.
      typed.eventSink(WorkspaceEvent.CreateSubSession)
      val created = awaitItemWhere { it.header?.name == "sub-1" }
      assertEquals(false, created.newSubSession.open)
      assertEquals(listOf<String?>("feat/login"), sessions.started.map { it.branch })
    }
  }

  @Test
  fun `the panel suggests a fresh branch each time it opens`() = runTest {
    withSession()
    settings.update { it.copy(subSessionLocation = dev.lemonadecode.domain.session.SubSessionLocation.NewWorktree) }
    sessions.worktreeBranchSuggestion = "worktree/herding-yaks"
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleNewSubSessionMenu)
      awaitItemWhere { it.newSubSession.worktreeBranch?.name == "worktree/herding-yaks" }.eventSink(WorkspaceEvent.SetWorktreeBranch("typed"))
      awaitItemWhere { it.newSubSession.worktreeBranch?.name == "typed" }.eventSink(WorkspaceEvent.DismissNewSubSessionMenu)
      sessions.worktreeBranchSuggestion = "worktree/taming-gremlins"
      awaitItemWhere { !it.newSubSession.open }.eventSink(WorkspaceEvent.ToggleNewSubSessionMenu)
      awaitItemWhere { it.newSubSession.worktreeBranch?.name == "worktree/taming-gremlins" }
    }
  }

  @Test
  fun `a branch git won't take, or one it already has, isn't created`() = runTest {
    withSession()
    sessions.existingBranches = setOf("feat/taken")
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleNewSubSessionMenu)
      awaitItemWhere { it.newSubSession.open }.eventSink(WorkspaceEvent.SetSubSessionLocation(LocationModel.NewWorktree))

      awaitItemWhere { it.newSubSession.worktreeBranch != null }.eventSink(WorkspaceEvent.SetWorktreeBranch("my branch"))
      val invalid = awaitItemWhere { it.newSubSession.worktreeBranch?.name == "my branch" }
      assertEquals(Res.string.worktree_branch_invalid.asLemonadeCodeString(), invalid.newSubSession.worktreeBranch?.problem)
      assertEquals(false, invalid.newSubSession.worktreeBranch?.canCreate)
      invalid.eventSink(WorkspaceEvent.CreateSubSession)

      invalid.eventSink(WorkspaceEvent.SetWorktreeBranch(""))
      val blank = awaitItemWhere { it.newSubSession.worktreeBranch?.name == "" }
      assertEquals(null, blank.newSubSession.worktreeBranch?.problem, "nothing to complain about yet")
      assertEquals(false, blank.newSubSession.worktreeBranch?.canCreate)
      blank.eventSink(WorkspaceEvent.CreateSubSession)

      blank.eventSink(WorkspaceEvent.SetWorktreeBranch("feat/taken"))
      awaitItemWhere { it.newSubSession.worktreeBranch?.name == "feat/taken" }.eventSink(WorkspaceEvent.CreateSubSession)
      val taken = awaitItemWhere { it.newSubSession.worktreeBranch?.problem != null }
      assertEquals(Res.string.worktree_branch_taken.asLemonadeCodeString("feat/taken"), taken.newSubSession.worktreeBranch?.problem)
      assertEquals(false, taken.newSubSession.worktreeBranch?.canCreate)
      assertEquals(true, taken.newSubSession.open, "the panel stays open to pick another name")
      assertEquals(null, taken.error, "said under the field, not in a banner")
      assertEquals(listOf<String?>("feat/taken"), sessions.started.map { it.branch }, "only a name git takes reaches it")

      taken.eventSink(WorkspaceEvent.SetWorktreeBranch("feat/taken-2"))
      val renamed = awaitItemWhere { it.newSubSession.worktreeBranch?.name == "feat/taken-2" }
      assertEquals(null, renamed.newSubSession.worktreeBranch?.problem)
      assertEquals(true, renamed.newSubSession.worktreeBranch?.canCreate)
    }
  }

  /** api-ratelimit with a sub-session and a grandchild, and checkout-flake outside a repository. */
  private fun withFamily() {
    sessions.setSessions(
      listOf(
        ShowcaseFixtures.session("ar", "api-ratelimit"),
        ShowcaseFixtures.session("r1", "review-1", parent = "ar"),
        ShowcaseFixtures.session("d2", "atomic-refill", parent = "r1"),
        ShowcaseFixtures.session("cf", "checkout-flake").copy(repositoryRoot = null),
      ),
    )
  }

  @Test
  fun `closing a session that isn't selected closes it and its sub-sessions and keeps the selection`() = runTest {
    withFamily()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectSession("cf"))
      awaitItemWhere { it.header?.name == "checkout-flake" }.eventSink(WorkspaceEvent.CloseSession("ar"))
      val closed = awaitItemWhere { it.sessions.tiles.size == 1 }
      assertEquals(listOf(ar), sessions.closed)
      assertEquals("checkout-flake", closed.header?.name)
    }
  }

  @Test
  fun `closing a session from its menu closes the menu with it`() = runTest {
    withFamily()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.OpenSessionMenu("cf"))
      awaitItemWhere { it.sessions.menu?.sessionId == "cf" }.eventSink(WorkspaceEvent.CloseSession("cf"))
      awaitItemWhere { it.sessions.tiles.none { tile -> tile.id == "cf" } && it.sessions.menu == null }
      assertEquals(listOf(SessionId("cf")), sessions.closed)

      // Not just hidden while the session is gone: one back under the same id finds no menu open.
      sessions.setSessions(sessions.sessions.value + ShowcaseFixtures.session("cf", "checkout-flake"))
      val back = awaitItemWhere { it.sessions.tiles.any { tile -> tile.id == "cf" } }
      assertEquals(null, back.sessions.menu)
    }
  }

  @Test
  fun `the session menu names the session and offers what fits it`() = runTest {
    withFamily()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleSettings)
      awaitItemWhere { it.settings.open }.eventSink(WorkspaceEvent.OpenSessionMenu("r1"))
      val sub = awaitItemWhere { it.sessions.menu != null }
      assertEquals(false, sub.settings.open, "one popover at a time")
      assertEquals(
        SessionMenuModel(
          sessionId = "r1",
          title = "review-1",
          location = "~/code/storefront · ${ShowcaseFixtures.Branch}",
          folderPath = ShowcaseFixtures.Repo,
          canStartSubSession = true,
          branch = ShowcaseFixtures.Branch,
          worktreeAvailable = true,
        ),
        sub.sessions.menu,
      )

      sub.eventSink(WorkspaceEvent.OpenSessionMenu("cf"))
      val plain = awaitItemWhere { it.sessions.menu?.sessionId == "cf" }.sessions.menu!!
      assertEquals(false, plain.worktreeAvailable)

      sub.eventSink(WorkspaceEvent.DismissSessionMenu)
      awaitItemWhere { it.sessions.menu == null }
    }
  }

  @Test
  fun `a new worktree from the menu asks for its branch, then starts under that session and gets selected`() = runTest {
    withFamily()
    sessions.worktreeBranchSuggestion = "worktree/generating-bugs"
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.OpenSessionMenu("d2"))
      awaitItemWhere { it.sessions.menu != null }.eventSink(WorkspaceEvent.StartNamingWorktree("d2"))
      val naming = awaitItemWhere { it.sessions.menu?.newWorktree != null }
      assertEquals("d2", naming.sessions.menu?.sessionId, "the field takes the menu's place")
      assertEquals(
        WorktreeBranchModel(name = "worktree/generating-bugs", path = "~/code/storefront.worktrees/generating-bugs", problem = null, canCreate = true),
        naming.sessions.menu?.newWorktree,
      )
      assertTrue(sessions.started.isEmpty(), "nothing is created before the branch is named")

      naming.eventSink(WorkspaceEvent.SetWorktreeBranch("fix/flaky-login"))
      awaitItemWhere { it.sessions.menu?.newWorktree?.name == "fix/flaky-login" }
        .eventSink(WorkspaceEvent.CreateSubSessionFrom("d2", LocationModel.NewWorktree))
      val created = awaitItemWhere { it.header?.name == "sub-1" }
      assertEquals(null, created.sessions.menu)
      val call = sessions.started.single()
      assertEquals(Triple(SessionId("d2"), dev.lemonadecode.domain.session.SubSessionLocation.NewWorktree, "fix/flaky-login"), Triple(call.parentId, call.location, call.branch))
      assertEquals(dev.lemonadecode.domain.session.SubSessionLocation.NewWorktree, settings.settings.value.subSessionLocation)
    }
  }

  @Test
  fun `closing the branch field closes the menu, and the menu opens on its items again`() = runTest {
    withFamily()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.OpenSessionMenu("d2"))
      awaitItemWhere { it.sessions.menu != null }.eventSink(WorkspaceEvent.StartNamingWorktree("d2"))
      awaitItemWhere { it.sessions.menu?.newWorktree != null }.eventSink(WorkspaceEvent.DismissSessionMenu)
      awaitItemWhere { it.sessions.menu == null }.eventSink(WorkspaceEvent.OpenSessionMenu("d2"))
      assertEquals(null, awaitItemWhere { it.sessions.menu != null }.sessions.menu?.newWorktree)
      assertTrue(sessions.started.isEmpty())
    }
  }

  @Test
  fun `renaming in the menu keeps the trimmed name, the automatic one follows the folder again`() = runTest {
    withFamily()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.OpenSessionMenu("ar"))
      awaitItemWhere { it.sessions.menu != null }.eventSink(WorkspaceEvent.StartRenamingSession("ar"))
      val editing = awaitItemWhere { it.sessions.menu?.rename != null }
      assertEquals("ar", editing.sessions.menu?.sessionId, "the field takes the menu's place")
      assertEquals(SessionRenameModel("api-ratelimit", "api-ratelimit"), editing.sessions.menu?.rename)

      editing.eventSink(WorkspaceEvent.RenameSession("ar", "  rate limits "))
      // A second Enter on its way out: only the first one counts.
      editing.eventSink(WorkspaceEvent.RenameSession("ar", "rate limits!"))
      val renamed = awaitItemWhere { it.header?.name == "rate limits" }
      assertEquals(null, renamed.sessions.menu)
      assertEquals(listOf<Pair<SessionId, String?>>(ar to "rate limits"), sessions.renamed)

      renamed.eventSink(WorkspaceEvent.StartRenamingSession("ar"))
      awaitItemWhere { it.sessions.menu?.rename?.name == "rate limits" }.eventSink(WorkspaceEvent.RenameSession("ar", "api-ratelimit"))
      awaitItemWhere { it.sessions.menu == null }
      assertEquals(ar to null, sessions.renamed.last())
    }
  }

  @Test
  fun `cancelling or saving the same name renames nothing`() = runTest {
    withFamily()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.StartRenamingSession("cf"))
      awaitItemWhere { it.sessions.menu?.rename != null }.eventSink(WorkspaceEvent.CancelRenamingSession)
      val cancelled = awaitItemWhere { it.sessions.menu == null }
      cancelled.eventSink(WorkspaceEvent.RenameSession("cf", "late enter"))
      cancelled.eventSink(WorkspaceEvent.StartRenamingSession("cf"))
      awaitItemWhere { it.sessions.menu?.rename != null }.eventSink(WorkspaceEvent.RenameSession("cf", "checkout-flake "))
      awaitItemWhere { it.sessions.menu == null }
      assertEquals(emptyList(), sessions.renamed)
    }
  }

  @Test
  fun `copying a folder path closes the menu and confirms with a toast for a moment`() = runTest {
    withFamily()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.OpenSessionMenu("cf"))
      awaitItemWhere { it.sessions.menu != null }.eventSink(WorkspaceEvent.CopiedSessionPath(ShowcaseFixtures.Repo))
      val copied = awaitItemWhere { it.toast != null }
      assertEquals(null, copied.sessions.menu)
      assertEquals(Res.string.session_path_copied.asLemonadeCodeString(ShowcaseFixtures.Repo), copied.toast)
      awaitItemWhere { it.toast == null }
    }
  }

  @Test
  fun `copying a file's path from the diff confirms with a toast for a moment`() = runTest {
    withSession()
    val path = ShowcaseFixtures.path("rl")
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      awaitItemWhere { it.content is WorkspaceContentModel.Diff }.eventSink(WorkspaceEvent.CopiedFilePath(path))
      val copied = awaitItemWhere { it.toast != null }
      assertEquals(Res.string.file_path_copied.asLemonadeCodeString(path), copied.toast)
      assertIs<WorkspaceContentModel.Diff>(copied.content)
      awaitItemWhere { it.toast == null }
    }
  }

  private fun dev.lemonadecode.workspace.uimodel.WorkspaceModel.lines(path: String): List<DiffRowModel.Line> = (content as? WorkspaceContentModel.Diff)?.review?.files?.first { it.path == path }?.rows?.filterIsInstance<DiffRowModel.Line>().orEmpty()

  private companion object {
    const val Now: Long = 1_793_600_000_000
  }
}
