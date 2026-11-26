package dev.lemonadecode.workspace.impl

import dev.lemonadecode.domain.mobile.DeviceApp
import dev.lemonadecode.domain.mobile.LogPackages
import dev.lemonadecode.domain.mobile.MobileError
import dev.lemonadecode.domain.mobile.RecordingFailure
import dev.lemonadecode.domain.mobile.RecordingResolution
import dev.lemonadecode.domain.mobile.RecordingStop
import dev.lemonadecode.domain.mobile.ScreenRecording
import dev.lemonadecode.domain.review.DiffState
import dev.lemonadecode.domain.review.Review
import dev.lemonadecode.domain.session.ImportBlock
import dev.lemonadecode.domain.session.ImportResult
import dev.lemonadecode.domain.session.ImportableSession
import dev.lemonadecode.domain.session.LinkedWorktree
import dev.lemonadecode.domain.session.Session
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SessionPlacement
import dev.lemonadecode.domain.session.SourceScan
import dev.lemonadecode.domain.settings.AppLanguage
import dev.lemonadecode.domain.settings.ThemePreference
import dev.lemonadecode.domain.update.AppRelease
import dev.lemonadecode.domain.update.AppVersion
import dev.lemonadecode.domain.update.UpdateChecks
import dev.lemonadecode.domain.update.UpdateFailure
import dev.lemonadecode.domain.update.UpdateProgress
import dev.lemonadecode.domain.update.UpdateState
import dev.lemonadecode.imports.fake.FakeSessionImportService
import dev.lemonadecode.mobile.fake.FakeMobileService
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.localization.asLemonadeCodeString
import dev.lemonadecode.platform.presenter.testing.awaitItemWhere
import dev.lemonadecode.platform.presenter.testing.test
import dev.lemonadecode.platform.presenter.testing.yieldUntil
import dev.lemonadecode.platform.process.HomeDirectory
import dev.lemonadecode.platform.sound.Chime
import dev.lemonadecode.platform.sound.testing.FakeSoundPlayer
import dev.lemonadecode.review.fake.FakeReviewService
import dev.lemonadecode.sessions.api.SessionError
import dev.lemonadecode.sessions.api.SessionService
import dev.lemonadecode.sessions.fake.FakeSessionService
import dev.lemonadecode.settings.fake.FakeSettingsService
import dev.lemonadecode.updates.fake.FakeUpdateService
import dev.lemonadecode.workspace.impl.showcase.ShowcaseDevices
import dev.lemonadecode.workspace.impl.showcase.ShowcaseFixtures
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.archived_parent_archived
import dev.lemonadecode.workspace.strings.archived_restored_at_home
import dev.lemonadecode.workspace.strings.archived_restored_at_home_many
import dev.lemonadecode.workspace.strings.archived_some_stayed
import dev.lemonadecode.workspace.strings.error_launch
import dev.lemonadecode.workspace.strings.error_parent_archived
import dev.lemonadecode.workspace.strings.error_worktree_delete
import dev.lemonadecode.workspace.strings.file_path_copied
import dev.lemonadecode.workspace.strings.import_done
import dev.lemonadecode.workspace.strings.import_interrupted
import dev.lemonadecode.workspace.strings.import_some_failed
import dev.lemonadecode.workspace.strings.mirror_size
import dev.lemonadecode.workspace.strings.recording_failed_no_video
import dev.lemonadecode.workspace.strings.recording_failed_partly
import dev.lemonadecode.workspace.strings.recording_low_space
import dev.lemonadecode.workspace.strings.recording_low_space_toast
import dev.lemonadecode.workspace.strings.recording_saved
import dev.lemonadecode.workspace.strings.recording_saved_device_gone
import dev.lemonadecode.workspace.strings.recording_saved_toast
import dev.lemonadecode.workspace.strings.session_archived
import dev.lemonadecode.workspace.strings.session_path_copied
import dev.lemonadecode.workspace.strings.size_gigabytes
import dev.lemonadecode.workspace.strings.time_hours_ago
import dev.lemonadecode.workspace.strings.update_failed_checksum
import dev.lemonadecode.workspace.strings.updates_up_to_date
import dev.lemonadecode.workspace.strings.worktree_branch_invalid
import dev.lemonadecode.workspace.strings.worktree_branch_taken
import dev.lemonadecode.workspace.strings.worktree_deleted
import dev.lemonadecode.workspace.uimodel.ArchivedSessionRowModel
import dev.lemonadecode.workspace.uimodel.ArchivedSessionsModel
import dev.lemonadecode.workspace.uimodel.CloseSessionDialogModel
import dev.lemonadecode.workspace.uimodel.ClosingWorktreeModel
import dev.lemonadecode.workspace.uimodel.DeviceRowModel
import dev.lemonadecode.workspace.uimodel.DeviceScreenModel
import dev.lemonadecode.workspace.uimodel.DeviceWindowModel
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
import dev.lemonadecode.workspace.uimodel.RecordingResolutionModel
import dev.lemonadecode.workspace.uimodel.ReleaseNotesModel
import dev.lemonadecode.workspace.uimodel.ScreenRecordingModel
import dev.lemonadecode.workspace.uimodel.SessionDragModel
import dev.lemonadecode.workspace.uimodel.SessionDropModel
import dev.lemonadecode.workspace.uimodel.SessionMenuModel
import dev.lemonadecode.workspace.uimodel.SessionNavigationModel
import dev.lemonadecode.workspace.uimodel.SessionRenameModel
import dev.lemonadecode.workspace.uimodel.SessionsPanelModel
import dev.lemonadecode.workspace.uimodel.SettingsPage
import dev.lemonadecode.workspace.uimodel.StatusSound
import dev.lemonadecode.workspace.uimodel.ThemeOption
import dev.lemonadecode.workspace.uimodel.UpdatePhase
import dev.lemonadecode.workspace.uimodel.WorkspaceContentModel
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import dev.lemonadecode.workspace.uimodel.WorkspaceModel
import dev.lemonadecode.workspace.uimodel.WorkspaceView
import dev.lemonadecode.workspace.uimodel.WorktreeBranchModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
  private val sounds = FakeSoundPlayer()
  private val imports = FakeSessionImportService()
  private val presenter = WorkspacePresenter(
    sessions,
    reviews,
    settings,
    object : HomeDirectory {
      override val path: String = ShowcaseFixtures.Home
    },
    updates,
    mobile,
    sounds,
    imports,
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
  fun `status sounds are saved as a setting, and either sound plays on Play, on or off`() = runTest {
    withSession()
    presenter.test {
      val model = awaitItem()
      assertTrue(model.settings.statusSounds)
      model.eventSink(WorkspaceEvent.SetStatusSounds(false))
      awaitItemWhere { !it.settings.statusSounds }
      assertEquals(false, settings.settings.value.statusSounds)

      model.eventSink(WorkspaceEvent.PlayStatusSound(StatusSound.Finished))
      model.eventSink(WorkspaceEvent.PlayStatusSound(StatusSound.NeedsInput))
      assertEquals(listOf(Chime.Ding, Chime.DoubleDing), sounds.played)
    }
  }

  @Test
  fun `animations are on at first, and turning them off is saved`() = runTest {
    withSession()
    presenter.test {
      val model = awaitItem()
      assertTrue(model.settings.animations)
      model.eventSink(WorkspaceEvent.SetAnimations(false))
      awaitItemWhere { !it.settings.animations }
      assertEquals(false, settings.settings.value.animations)
    }
  }

  @Test
  fun `copy on select is off at first, and turning it on is saved`() = runTest {
    withSession()
    presenter.test {
      val model = awaitItem()
      assertFalse(model.settings.copyOnSelect)
      model.eventSink(WorkspaceEvent.SetCopyOnSelect(true))
      awaitItemWhere { it.settings.copyOnSelect }
      assertEquals(true, settings.settings.value.copyOnSelect)
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
  fun `import sessions looks at what herdr has and opens the ticked ones`() = runTest {
    withSession()
    imports.scans = listOf(SourceScan.Found(FakeSessionImportService.Herdr, listOf(storefrontImport, worktreeImport, importedBefore)))
    imports.importResult = ImportResult(listOf(ar))
    presenter.test {
      val first = awaitItem()
      assertEquals("herdr", first.settings.imports.sourceNames)
      first.eventSink(WorkspaceEvent.ToggleSettings)
      awaitItemWhere { it.settings.open }.eventSink(WorkspaceEvent.OpenSettingsPage(SettingsPage.ImportSessions))
      val page = awaitItemWhere { it.settings.imports.sources.isNotEmpty() }
      assertEquals(1, imports.scanCount)
      val rows = page.settings.imports.sources.single().rows
      assertEquals(listOf(false, true, false), rows.map { it.nested })
      assertEquals(listOf(true, true, false), rows.map { it.enabled })
      assertEquals(false, page.settings.imports.canImport)

      // One imported before can't be ticked, by itself or with the rest.
      page.eventSink(WorkspaceEvent.ToggleImportSession(importedBefore.key))
      page.eventSink(WorkspaceEvent.TickAllImports("herdr", ticked = true))
      val all = awaitItemWhere { it.settings.imports.tickedCount == 2 }
      assertTrue(all.settings.imports.sources.single().allTicked)
      all.eventSink(WorkspaceEvent.ToggleImportSession(worktreeImport.key))
      awaitItemWhere { it.settings.imports.tickedCount == 1 }.eventSink(WorkspaceEvent.ImportSessions)

      val done = awaitItemWhere { it.toast != null }
      assertEquals(listOf(listOf(storefrontImport)), imports.imported)
      assertEquals(false, done.settings.open)
      assertEquals(Res.plurals.import_done.asLemonadeCodeString(1), done.toast)
    }
  }

  @Test
  fun `an import that opens nothing says why and looks again`() = runTest {
    imports.scans = listOf(SourceScan.Found(FakeSessionImportService.Herdr, listOf(storefrontImport)))
    imports.importResult = ImportResult(emptyList(), failed = listOf(storefrontImport))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.OpenSettingsPage(SettingsPage.ImportSessions))
      awaitItemWhere { it.settings.imports.sources.isNotEmpty() }.eventSink(WorkspaceEvent.ToggleImportSession(storefrontImport.key))
      awaitItemWhere { it.settings.imports.tickedCount == 1 }.eventSink(WorkspaceEvent.ImportSessions)
      val failed = awaitItemWhere { it.error != null }
      assertEquals(Res.plurals.import_some_failed.asLemonadeCodeString(1), failed.error)
      assertTrue(failed.settings.open)
      yieldUntil { imports.scanCount == 2 }
    }
  }

  @Test
  fun `an import that breaks off says so and frees the page`() = runTest {
    imports.scans = listOf(SourceScan.Found(FakeSessionImportService.Herdr, listOf(storefrontImport)))
    imports.importError = IllegalStateException("No space left on device")
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.OpenSettingsPage(SettingsPage.ImportSessions))
      awaitItemWhere { it.settings.imports.sources.isNotEmpty() }.eventSink(WorkspaceEvent.ToggleImportSession(storefrontImport.key))
      awaitItemWhere { it.settings.imports.tickedCount == 1 }.eventSink(WorkspaceEvent.ImportSessions)
      val failed = awaitItemWhere { it.error != null && !it.settings.imports.importing }
      assertEquals(Res.string.import_interrupted.asLemonadeCodeString(), failed.error)
      assertTrue(failed.settings.open)
      yieldUntil { imports.scanCount == 2 }
    }
  }

  @Test
  fun `an import with nothing left to open frees the page and looks again`() = runTest {
    imports.scans = listOf(SourceScan.Found(FakeSessionImportService.Herdr, listOf(storefrontImport)))
    imports.importResult = ImportResult(emptyList())
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.OpenSettingsPage(SettingsPage.ImportSessions))
      awaitItemWhere { it.settings.imports.sources.isNotEmpty() }.eventSink(WorkspaceEvent.ToggleImportSession(storefrontImport.key))
      awaitItemWhere { it.settings.imports.tickedCount == 1 }.eventSink(WorkspaceEvent.ImportSessions)
      yieldUntil { imports.scanCount == 2 }
      val page = awaitItemWhere { !it.settings.imports.importing && !it.settings.imports.loading }
      assertEquals(null, page.error)
      assertTrue(page.settings.open)
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
  fun `recording a device's screen counts its time, streams on off the page, and says where it saved`() = runTest {
    withSession()
    settings.update { it.copy(mobilePlugin = true) }
    mobile.setDevices(ShowcaseDevices.connected)
    mobile.screens.value = mapOf(ShowcaseDevices.pixel.id to ShowcaseDevices.screen())
    mobile.recordingStartsAt = Now - 5_000
    mobile.nextRecordingEnd = ScreenRecording.Saved(listOf("${ShowcaseFixtures.Home}/Movies/Lemonade Code/Pixel-8.mp4"), durationMillis = 5_000)
    val pixel = ShowcaseDevices.pixel.id.value
    fun WorkspaceModel.page() = devices?.content as? DevicesContentModel.Device
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleDevices)
      awaitItemWhere { it.devices != null }.eventSink(WorkspaceEvent.SelectDevice(pixel))
      val shown = awaitItemWhere { it.page()?.screen is DeviceScreenModel.Frame }
      assertEquals(ScreenRecordingModel(), shown.page()?.recording)
      // Recording asks what size first, each time.
      shown.eventSink(WorkspaceEvent.ToggleScreenRecording)
      val choosing = awaitItemWhere { it.page()?.recording?.choice != null }
      val options = choosing.page()?.recording?.choice?.options.orEmpty()
      assertEquals(listOf(RecordingResolutionModel.Full, RecordingResolutionModel.Mirror), options.map { it.resolution })
      assertEquals(listOf(Res.string.mirror_size.asLemonadeCodeString(1080, 2400), Res.string.mirror_size.asLemonadeCodeString(576, 1280)), options.map { it.size })
      assertEquals(listOf(false, false), options.map { it.last }, "nothing was picked before")
      choosing.eventSink(WorkspaceEvent.StartScreenRecording(RecordingResolutionModel.Mirror))
      val recording = awaitItemWhere { it.page()?.recording?.recording == true }
      assertNull(recording.page()?.recording?.choice)
      assertEquals("0:05", recording.page()?.recording?.elapsed)

      // Back to the list: the recording goes on, so the screen streams on, and the row says so.
      recording.eventSink(WorkspaceEvent.DevicesBack)
      val list = awaitItemWhere { it.rowOf(pixel)?.recordingElapsed != null }
      assertEquals("0:05", list.rowOf(pixel)?.recordingElapsed)
      assertEquals(1, mobile.screenCollectors.value[ShowcaseDevices.pixel.id])

      list.eventSink(WorkspaceEvent.SelectDevice(pixel))
      awaitItemWhere { it.page()?.recording?.recording == true }.eventSink(WorkspaceEvent.ToggleScreenRecording)
      val saved = awaitItemWhere { it.toast != null }
      assertEquals(Res.string.recording_saved_toast.asLemonadeCodeString("Pixel 8", "~/Movies/Lemonade Code"), saved.toast)
      assertEquals(Res.string.recording_saved.asLemonadeCodeString("0:05"), saved.page()?.recording?.saved?.label)

      saved.eventSink(WorkspaceEvent.DismissSavedRecording)
      val dismissed = awaitItemWhere { it.page()?.recording?.saved == null }
      assertEquals(listOf("record $pixel mirror", "stop recording $pixel", "dismiss recording $pixel"), mobile.actions)

      // Next time the choice ticks the size picked last; closing it records nothing.
      dismissed.eventSink(WorkspaceEvent.ToggleScreenRecording)
      val again = awaitItemWhere { it.page()?.recording?.choice != null }
      assertEquals(listOf(false, true), again.page()?.recording?.choice?.options?.map { it.last })
      again.eventSink(WorkspaceEvent.DismissRecordingChoice)
      awaitItemWhere { it.page()?.recording?.choice == null }
      assertEquals(3, mobile.actions.size)
    }
  }

  @Test
  fun `full resolution is asked of the service, and a disk running low is a warning under the screen, not an error`() = runTest {
    withSession()
    settings.update { it.copy(mobilePlugin = true) }
    mobile.setDevices(ShowcaseDevices.connected)
    mobile.screens.value = mapOf(ShowcaseDevices.pixel.id to ShowcaseDevices.screen())
    val pixel = ShowcaseDevices.pixel.id
    fun WorkspaceModel.page() = devices?.content as? DevicesContentModel.Device
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleDevices)
      awaitItemWhere { it.devices != null }.eventSink(WorkspaceEvent.SelectDevice(pixel.value))
      awaitItemWhere { it.page()?.screen is DeviceScreenModel.Frame }.eventSink(WorkspaceEvent.ToggleScreenRecording)
      awaitItemWhere { it.page()?.recording?.choice != null }.eventSink(WorkspaceEvent.StartScreenRecording(RecordingResolutionModel.Full))
      awaitItemWhere { it.page()?.recording?.recording == true }
      assertEquals(listOf("record ${pixel.value} full"), mobile.actions)

      mobile.recordings.value = mapOf(pixel to ScreenRecording.Recording(Now, RecordingResolution.Full, spaceLeft = 1_600_000_000L))
      val warning = Res.string.recording_low_space.asLemonadeCodeString(Res.string.size_gigabytes.asLemonadeCodeString(1))
      val low = awaitItemWhere { it.page()?.recording?.lowSpace != null }
      assertEquals(warning, low.page()?.recording?.lowSpace)
      assertNull(low.error, "the app has no warning banner; the line under the screen says it")
    }
  }

  @Test
  fun `a disk running low is a warning toast, even with the Devices panel closed, once per time it runs low`() = runTest {
    withSession()
    settings.update { it.copy(mobilePlugin = true) }
    mobile.setDevices(ShowcaseDevices.connected)
    val pixel = ShowcaseDevices.pixel.id
    fun warning(left: Int) = Res.string.recording_low_space_toast.asLemonadeCodeString(Res.string.size_gigabytes.asLemonadeCodeString(left), "Pixel 8")
    presenter.test {
      awaitItem()
      // Recording goes on with the panel closed.
      mobile.recordings.value = mapOf(pixel to ScreenRecording.Recording(Now))
      mobile.recordings.value = mapOf(pixel to ScreenRecording.Recording(Now, spaceLeft = 1_600_000_000L))
      val low = awaitItemWhere { it.toast != null }
      assertNull(low.devices, "the panel is closed")
      assertEquals(warning(1), low.toast)
      assertTrue(low.toastWarning)
      awaitItemWhere { it.toast == null }
      // Lower still: already said.
      mobile.recordings.value = mapOf(pixel to ScreenRecording.Recording(Now, spaceLeft = 900_000_000L))
      // Room again, then low again: said again.
      mobile.recordings.value = mapOf(pixel to ScreenRecording.Recording(Now))
      mobile.recordings.value = mapOf(pixel to ScreenRecording.Recording(Now, spaceLeft = 1_200_000_000L))
      val again = awaitItemWhere { it.toast != null }
      assertEquals(warning(1), again.toast, "no toast came at 900 MB")
      assertTrue(again.toastWarning)
      // A confirmation after it is no warning.
      again.eventSink(WorkspaceEvent.CopiedFilePath("/tmp/a"))
      assertEquals(false, awaitItemWhere { it.toast == Res.string.file_path_copied.asLemonadeCodeString("/tmp/a") }.toastWarning)
    }
  }

  @Test
  fun `a choice of size left open closes once the device records, so it doesn't open again after`() = runTest {
    withSession()
    settings.update { it.copy(mobilePlugin = true) }
    mobile.setDevices(ShowcaseDevices.connected)
    mobile.screens.value = mapOf(ShowcaseDevices.pixel.id to ShowcaseDevices.screen())
    val pixel = ShowcaseDevices.pixel.id
    fun WorkspaceModel.page() = devices?.content as? DevicesContentModel.Device
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleDevices)
      awaitItemWhere { it.devices != null }.eventSink(WorkspaceEvent.SelectDevice(pixel.value))
      awaitItemWhere { it.page()?.screen is DeviceScreenModel.Frame }.eventSink(WorkspaceEvent.ToggleScreenRecording)
      awaitItemWhere { it.page()?.recording?.choice != null }
      // Recording starts from another page of the device, e.g. its own window.
      mobile.recordings.value = mapOf(pixel to ScreenRecording.Recording(Now))
      awaitItemWhere { it.page()?.recording?.recording == true }
      testScheduler.runCurrent()
      mobile.recordings.value = mapOf(pixel to ShowcaseDevices.savedRecording)
      val stopped = awaitItemWhere { it.page()?.recording?.saved != null }
      assertNull(stopped.page()?.recording?.choice)
    }
  }

  @Test
  fun `a choice of size left open closes when the screen comes as screenshots, and stays closed when video returns`() = runTest {
    withSession()
    settings.update { it.copy(mobilePlugin = true) }
    mobile.setDevices(ShowcaseDevices.connected)
    mobile.screens.value = mapOf(ShowcaseDevices.pixel.id to ShowcaseDevices.screen())
    val pixel = ShowcaseDevices.pixel.id
    fun WorkspaceModel.page() = devices?.content as? DevicesContentModel.Device
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleDevices)
      awaitItemWhere { it.devices != null }.eventSink(WorkspaceEvent.SelectDevice(pixel.value))
      awaitItemWhere { it.page()?.screen is DeviceScreenModel.Frame }.eventSink(WorkspaceEvent.ToggleScreenRecording)
      awaitItemWhere { it.page()?.recording?.choice != null }
      mobile.screens.value = mapOf(pixel to ShowcaseDevices.screen(video = false))
      awaitItemWhere { it.page()?.recording?.unavailable != null }
      testScheduler.runCurrent()
      mobile.screens.value = mapOf(pixel to ShowcaseDevices.screen())
      val back = awaitItemWhere { it.page()?.recording?.unavailable == null }
      assertNull(back.page()?.recording?.choice)
    }
  }

  @Test
  fun `a recording that ends by itself says so, kept when the device went or why nothing was saved`() = runTest {
    withSession()
    settings.update { it.copy(mobilePlugin = true) }
    mobile.setDevices(ShowcaseDevices.connected)
    mobile.screens.value = mapOf(ShowcaseDevices.pixel.id to ShowcaseDevices.screen())
    val pixel = ShowcaseDevices.pixel.id
    fun WorkspaceModel.recording() = (devices?.content as? DevicesContentModel.Device)?.recording?.recording == true
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleDevices)
      awaitItemWhere { it.devices != null }.eventSink(WorkspaceEvent.SelectDevice(pixel.value))
      awaitItemWhere { (it.devices?.content as? DevicesContentModel.Device)?.screen is DeviceScreenModel.Frame }.eventSink(WorkspaceEvent.StartScreenRecording(RecordingResolutionModel.Mirror))
      awaitItemWhere { it.recording() }

      // Unplugged: the service stops it and keeps what was recorded.
      mobile.setDevices(ShowcaseDevices.connected - ShowcaseDevices.pixel)
      mobile.recordings.value = mapOf(pixel to ScreenRecording.Saved(listOf("${ShowcaseFixtures.Home}/Movies/Lemonade Code/Pixel-8.mp4"), 3_000, stop = RecordingStop.DeviceGone))
      val gone = awaitItemWhere { it.toast != null }
      assertEquals(Res.string.recording_saved_device_gone.asLemonadeCodeString("Pixel 8", "~/Movies/Lemonade Code"), gone.toast)

      mobile.setDevices(ShowcaseDevices.connected)
      mobile.recordings.value = mapOf(pixel to ScreenRecording.Recording(Now))
      awaitItemWhere { it.recording() }
      mobile.recordings.value = mapOf(pixel to ScreenRecording.Failed(RecordingFailure.NoVideo))
      val failed = awaitItemWhere { it.error != null }
      assertEquals(Res.string.recording_failed_no_video.asLemonadeCodeString("Pixel 8"), failed.error)

      // The disk filled up: the error says where what was written is, and the page still finds it.
      failed.eventSink(WorkspaceEvent.DismissError)
      mobile.recordings.value = mapOf(pixel to ScreenRecording.Recording(Now))
      awaitItemWhere { it.recording() && it.error == null }
      val written = "${ShowcaseFixtures.Home}/Movies/Lemonade Code/Pixel-8.mp4"
      mobile.recordings.value = mapOf(pixel to ScreenRecording.Failed(RecordingFailure.Unwritable("No space left on device"), listOf(written)))
      val full = awaitItemWhere { it.error != null }
      assertEquals(Res.string.recording_failed_partly.asLemonadeCodeString("~/Movies/Lemonade Code"), full.error)
      assertEquals("file:///Users/you/Movies/Lemonade%20Code/Pixel-8.mp4", (full.devices?.content as? DevicesContentModel.Device)?.recording?.saved?.revealUri)
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
  fun `a device opens in a window of its own, and its button or the window's close closes it`() = runTest {
    settings.update { it.copy(mobilePlugin = true) }
    mobile.setDevices(ShowcaseDevices.connected)
    val pixel = ShowcaseDevices.pixel.id.value
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleDevices)
      awaitItemWhere { it.devices != null }.eventSink(WorkspaceEvent.ToggleDeviceWindow(pixel))
      val open = awaitItemWhere { it.deviceWindows.isNotEmpty() }
      val window = open.deviceWindows.single()
      assertEquals("Pixel 8", window.name)
      assertIs<DevicesContentModel.Device>(window.panel.content)
      assertEquals(false, window.panel.canGoBack)
      assertEquals(false, window.panel.windowControls)
      assertEquals(true, open.rowOf(pixel)?.windowOpen)

      open.eventSink(WorkspaceEvent.ToggleDeviceWindow(pixel))
      val closed = awaitItemWhere { it.deviceWindows.isEmpty() }
      assertEquals(false, closed.rowOf(pixel)?.windowOpen)
      closed.eventSink(WorkspaceEvent.ToggleDeviceWindow(pixel))
      awaitItemWhere { it.deviceWindows.isNotEmpty() }.eventSink(WorkspaceEvent.CloseDeviceWindow(pixel))
      awaitItemWhere { it.deviceWindows.isEmpty() }
    }
  }

  @Test
  fun `each device window reaches its own device and keeps its own page`() = runTest {
    settings.update { it.copy(mobilePlugin = true) }
    val (pixel, galaxy, iphone) = Triple(ShowcaseDevices.pixel.id, ShowcaseDevices.galaxy.id, ShowcaseDevices.iphone.id)
    mobile.setDevices(ShowcaseDevices.connected)
    mobile.logs.value = listOf(pixel, galaxy, iphone).associateWith { ShowcaseDevices.androidLogs }
    mobile.screens.value = listOf(pixel, galaxy, iphone).associateWith { ShowcaseDevices.screen() }
    mobile.apps.value = mapOf(pixel to listOf(DeviceApp("com.teya.pixel")), galaxy to listOf(DeviceApp("com.teya.galaxy")))
    presenter.test {
      val first = awaitItem()
      first.eventSink(WorkspaceEvent.ToggleDeviceWindow(pixel.value))
      first.eventSink(WorkspaceEvent.ToggleDeviceWindow(galaxy.value))
      first.eventSink(WorkspaceEvent.ToggleDevices)
      awaitItemWhere { it.devices != null }.eventSink(WorkspaceEvent.SelectDevice(iphone.value))
      val all = awaitItemWhere { model ->
        model.devices?.content is DevicesContentModel.Device && model.deviceWindows.size == 2 && model.deviceWindows.all { it.page().screen is DeviceScreenModel.Frame }
      }
      assertEquals(listOf("Pixel 8", "Galaxy S23"), all.deviceWindows.map { it.name })

      all.eventSink(WorkspaceEvent.InDeviceWindow(pixel.value, WorkspaceEvent.TapDevice(10, 20)))
      all.eventSink(WorkspaceEvent.InDeviceWindow(galaxy.value, WorkspaceEvent.PressDeviceHome))
      all.eventSink(WorkspaceEvent.PressDeviceHome)
      yieldUntil { mobile.actions.size >= 3 }
      assertEquals(listOf("tap ${pixel.value} 10,20", "press ${galaxy.value} Home", "press ${iphone.value} Home"), mobile.actions)

      all.eventSink(WorkspaceEvent.InDeviceWindow(pixel.value, WorkspaceEvent.SetLogFilter(LogFilter.Errors)))
      val filtered = awaitItemWhere { it.deviceWindows.first().page().logs.filter == LogFilter.Errors }
      assertEquals(LogFilter.All, filtered.deviceWindows.last().page().logs.filter)
      assertEquals(LogFilter.All, (filtered.devices?.content as DevicesContentModel.Device).logs.filter)

      // Whose lines the log shows is picked per window too, from that device's own apps.
      filtered.eventSink(WorkspaceEvent.InDeviceWindow(galaxy.value, WorkspaceEvent.ToggleLogApps))
      val picking = awaitItemWhere { it.deviceWindows.last().page().logs.apps?.apps?.isNotEmpty() == true }
      assertEquals(listOf("com.teya.galaxy"), picking.deviceWindows.last().page().logs.apps?.apps?.map { it.packageName })
      assertEquals(false, picking.deviceWindows.first().page().logs.apps?.open)
    }
  }

  @Test
  fun `picking a device that has its own window brings that window forward, and it outlives the panel`() = runTest {
    settings.update { it.copy(mobilePlugin = true) }
    mobile.setDevices(ShowcaseDevices.connected)
    val pixel = ShowcaseDevices.pixel.id.value
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleDevices)
      // The panel's own device moves into the window, and the panel goes back to the list.
      awaitItemWhere { it.devices != null }.eventSink(WorkspaceEvent.SelectDevice(pixel))
      awaitItemWhere { it.devices?.content is DevicesContentModel.Device }.eventSink(WorkspaceEvent.ToggleDeviceWindow(pixel))
      val open = awaitItemWhere { it.deviceWindows.isNotEmpty() }
      assertIs<DevicesContentModel.Devices>(open.devices?.content)
      assertEquals(0, open.deviceWindows.single().focusRequest)

      open.eventSink(WorkspaceEvent.SelectDevice(pixel))
      val forward = awaitItemWhere { it.deviceWindows.single().focusRequest == 1 }
      assertIs<DevicesContentModel.Devices>(forward.devices?.content)

      forward.eventSink(WorkspaceEvent.CloseDevices)
      assertEquals(1, awaitItemWhere { it.devices == null }.deviceWindows.size)
    }
  }

  @Test
  fun `disconnecting a device from its own window leaves the panel on the device it shows`() = runTest {
    settings.update { it.copy(mobilePlugin = true) }
    mobile.setDevices(ShowcaseDevices.connected)
    val (pixel, galaxy) = ShowcaseDevices.pixel.id.value to ShowcaseDevices.galaxy.id.value
    presenter.test {
      val first = awaitItem()
      first.eventSink(WorkspaceEvent.ToggleDeviceWindow(galaxy))
      first.eventSink(WorkspaceEvent.ToggleDevices)
      awaitItemWhere { it.devices != null }.eventSink(WorkspaceEvent.SelectDevice(pixel))
      // Disconnect isn't a page event, so the window sends it as it is.
      awaitItemWhere { it.devices?.content is DevicesContentModel.Device && it.deviceWindows.size == 1 }.eventSink(WorkspaceEvent.DisconnectDevice(galaxy))
      val after = awaitItemWhere { it.toast != null }
      assertEquals(emptyList(), after.deviceWindows)
      assertEquals(pixel, (after.devices?.content as? DevicesContentModel.Device)?.id)
    }
  }

  @Test
  fun `opening a just-paired device that has its own window brings that window forward`() = runTest {
    settings.update { it.copy(mobilePlugin = true) }
    mobile.setDevices(ShowcaseDevices.connected)
    mobile.nextPairResult = LemonadeCodeResult.Success(ShowcaseDevices.galaxy)
    presenter.test {
      val first = awaitItem()
      first.eventSink(WorkspaceEvent.ToggleDeviceWindow(ShowcaseDevices.galaxy.id.value))
      first.eventSink(WorkspaceEvent.ToggleDevices)
      awaitItemWhere { it.devices != null }.eventSink(WorkspaceEvent.StartPairing)
      awaitItemWhere { it.devices?.content == DevicesContentModel.ChoosePlatform }.eventSink(WorkspaceEvent.ChoosePairingPlatform(MobilePlatformModel.Android))
      awaitItemWhere { it.devices?.content is DevicesContentModel.ChooseMethod }.eventSink(WorkspaceEvent.ChoosePairingMethod(PairingMethod.PairingCode))
      val code = awaitItemWhere { it.devices?.content is DevicesContentModel.EnterCode }
      code.eventSink(WorkspaceEvent.SetPairingAddress("192.168.1.42:37099"))
      code.eventSink(WorkspaceEvent.SetPairingCode("482913"))
      awaitItemWhere { (it.devices?.content as? DevicesContentModel.EnterCode)?.canPair == true }.eventSink(WorkspaceEvent.PairWithCode)
      awaitItemWhere { it.devices?.content is DevicesContentModel.Paired }.eventSink(WorkspaceEvent.OpenPairedDevice)
      val forward = awaitItemWhere { it.deviceWindows.singleOrNull()?.focusRequest == 1 }
      assertIs<DevicesContentModel.Devices>(forward.devices?.content)
    }
  }

  @Test
  fun `a device's window closes when the device goes, and every window closes with the plugin`() = runTest {
    settings.update { it.copy(mobilePlugin = true) }
    mobile.setDevices(ShowcaseDevices.connected)
    val (pixel, galaxy) = ShowcaseDevices.pixel.id.value to ShowcaseDevices.galaxy.id.value
    presenter.test {
      val first = awaitItem()
      first.eventSink(WorkspaceEvent.ToggleDevices)
      first.eventSink(WorkspaceEvent.ToggleDeviceWindow(pixel))
      first.eventSink(WorkspaceEvent.ToggleDeviceWindow(galaxy))
      awaitItemWhere { it.deviceWindows.size == 2 }

      mobile.setDevices(ShowcaseDevices.connected - ShowcaseDevices.pixel)
      assertEquals(listOf(galaxy), awaitItemWhere { it.deviceWindows.size == 1 }.deviceWindows.map { it.deviceId })
      testScheduler.runCurrent()
      // Plugged back in, it's in the list again but not in a window.
      mobile.setDevices(ShowcaseDevices.connected)
      val back = awaitItemWhere { it.rowOf(pixel) != null }
      assertEquals(false, back.rowOf(pixel)?.windowOpen)
      assertEquals(listOf(galaxy), back.deviceWindows.map { it.deviceId })

      back.eventSink(WorkspaceEvent.SetMobilePlugin(false))
      awaitItemWhere { it.topBar.devices == null && it.deviceWindows.isEmpty() }.eventSink(WorkspaceEvent.SetMobilePlugin(true))
      assertEquals(emptyList(), awaitItemWhere { it.topBar.devices != null }.deviceWindows)
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
      // Named after its worktree, from the suggested `worktree/testing-things`.
      val created = awaitItemWhere { it.header?.name == "testing-things" }
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
        WorktreeBranchModel(
          name = "worktree/generating-bugs",
          path = "~/code/storefront.worktrees/generating-bugs",
          sessionName = "generating-bugs",
          problem = null,
          canCreate = true,
        ),
        suggested.newSubSession.worktreeBranch,
      )

      suggested.eventSink(WorkspaceEvent.SetWorktreeBranch("feat/login"))
      val typed = awaitItemWhere { it.newSubSession.worktreeBranch?.name == "feat/login" }
      assertEquals("~/code/storefront.worktrees/login", typed.newSubSession.worktreeBranch?.path)
      assertEquals("login", typed.newSubSession.worktreeBranch?.sessionName)

      typed.eventSink(WorkspaceEvent.CreateSubSession)
      // Enter held down, or pressed again while git works: still one worktree.
      typed.eventSink(WorkspaceEvent.CreateSubSession)
      val created = awaitItemWhere { it.header?.name == "login" }
      assertEquals(false, created.newSubSession.open)
      assertEquals(listOf<String?>("feat/login"), sessions.started.map { it.branch })
      val rows = assertIs<SessionNavigationModel.Tree>(created.sessions.navigation).rows
      assertEquals(listOf("api-ratelimit", "login"), rows.map { it.name }, "the list shows the worktree's name too")
    }
  }

  @Test
  fun `the name the plan gives a worktree, even one kept apart, is the one shown, and a rename still wins`() = runTest {
    withSession()
    sessions.worktreeBranchSuggestion = "worktree/generating-bugs"
    // As the real planner names it beside a `generating-bugs` folder already there.
    sessions.plannedWorktreeName = "generating-bugs-2"
    settings.update { it.copy(subSessionLocation = dev.lemonadecode.domain.session.SubSessionLocation.NewWorktree) }
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleNewSubSessionMenu)
      val open = awaitItemWhere { it.newSubSession.worktreeBranch != null }
      assertEquals("generating-bugs-2", open.newSubSession.worktreeBranch?.sessionName)
      assertEquals("~/code/storefront.worktrees/generating-bugs-2", open.newSubSession.worktreeBranch?.path)
      open.eventSink(WorkspaceEvent.CreateSubSession)
      val created = awaitItemWhere { it.header?.name == "generating-bugs-2" }
      assertEquals(listOf("api-ratelimit", "generating-bugs-2"), assertIs<SessionNavigationModel.Tree>(created.sessions.navigation).rows.map { it.name })

      val id = sessions.sessions.value.single { it.name == "generating-bugs-2" }.id.value
      created.eventSink(WorkspaceEvent.StartRenamingSession(id))
      awaitItemWhere { it.sessions.menu?.rename != null }.eventSink(WorkspaceEvent.RenameSession(id, "flaky login"))
      val renamed = awaitItemWhere { it.header?.name == "flaky login" }
      assertEquals(listOf("api-ratelimit", "flaky login"), assertIs<SessionNavigationModel.Tree>(renamed.sessions.navigation).rows.map { it.name })
    }
  }

  @Test
  fun `a sub-session is planned only while the panel or a menu's branch field shows the plan`() = runTest {
    withFamily()
    presenter.test {
      val first = awaitItem()
      // Statuses changing, and a plain menu, draw the workspace again without planning anything.
      sessions.setStatus(SessionId("ar"), dev.lemonadecode.domain.session.SessionStatus.Working)
      awaitItemWhere { it.sessions.tiles.first { tile -> tile.id == "ar" }.status == dev.lemonadecode.workspace.uimodel.SessionStatus.Working }
      first.eventSink(WorkspaceEvent.OpenSessionMenu("d2"))
      awaitItemWhere { it.sessions.menu != null }.eventSink(WorkspaceEvent.DismissSessionMenu)
      awaitItemWhere { it.sessions.menu == null }
      assertEquals(emptyList(), sessions.planned, "nothing open asks for a plan")

      first.eventSink(WorkspaceEvent.ToggleNewSubSessionMenu)
      awaitItemWhere { it.newSubSession.open }.eventSink(WorkspaceEvent.DismissNewSubSessionMenu)
      awaitItemWhere { !it.newSubSession.open }
      assertTrue(sessions.planned.isNotEmpty(), "the open panel shows a plan")

      sessions.planned.clear()
      first.eventSink(WorkspaceEvent.StartNamingWorktree("d2"))
      awaitItemWhere { it.sessions.menu?.newWorktree != null }.eventSink(WorkspaceEvent.DismissSessionMenu)
      awaitItemWhere { it.sessions.menu == null }
      assertEquals(SessionId("d2"), sessions.planned.firstOrNull(), "the menu's branch field shows a plan")

      sessions.planned.clear()
      sessions.setStatus(SessionId("ar"), dev.lemonadecode.domain.session.SessionStatus.Idle)
      awaitItemWhere { it.sessions.tiles.first { tile -> tile.id == "ar" }.status == dev.lemonadecode.workspace.uimodel.SessionStatus.Idle }
      assertEquals(emptyList(), sessions.planned, "closed again, nothing is planned")
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

  private val worktree = LinkedWorktree(
    path = "${ShowcaseFixtures.Repo}.worktrees/sub-2",
    branch = "feat/rate-limit-sub-2",
    mainCheckout = ShowcaseFixtures.Repo,
    hasChanges = true,
  )

  private fun inWorktree(id: String, name: String, parent: String = "ar") = ShowcaseFixtures.session(
    id,
    name,
    parent = parent,
    branch = worktree.branch,
    placement = SessionPlacement.Worktree,
    folder = worktree.path,
  )

  /** api-ratelimit in the main checkout, and its sub-session sub-2 in a worktree of its own. */
  private fun withWorktree(vararg more: Session) {
    sessions.setSessions(listOf(ShowcaseFixtures.session("ar", "api-ratelimit"), inWorktree("s2", "sub-2")) + more)
    sessions.worktrees = listOf(worktree)
  }

  private val worktreeDialog = CloseSessionDialogModel(
    sessionName = "sub-2",
    subSessionCount = 0,
    worktrees = listOf(ClosingWorktreeModel(path = "~/code/storefront.worktrees/sub-2", branch = "feat/rate-limit-sub-2", hasChanges = true)),
  )

  @Test
  fun `closing a session in no worktree closes it straight away, without asking`() = runTest {
    withFamily()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.CloseSession("r1"))
      val closed = awaitItemWhere { it.sessions.tiles.size == 2 }
      assertEquals(listOf(SessionId("r1")), sessions.closed)
      assertEquals(null, closed.closeSession)
      // Outside git there's nothing to ask git about: it closes in the same breath.
      closed.eventSink(WorkspaceEvent.CloseSession("cf"))
      assertEquals(listOf(SessionId("r1"), SessionId("cf")), sessions.closed)
    }
  }

  @Test
  fun `closing a session in a worktree no other session uses asks first`() = runTest {
    withWorktree()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.CloseSession("s2"))
      val asking = awaitItemWhere { it.closeSession != null }
      assertEquals(worktreeDialog, asking.closeSession)
      assertEquals(emptyList(), sessions.closed, "nothing closes before the answer")
      assertEquals(2, asking.sessions.tiles.size)
    }
  }

  @Test
  fun `cancelling keeps the session and its worktree`() = runTest {
    withWorktree()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.CloseSession("s2"))
      awaitItemWhere { it.closeSession != null }.eventSink(WorkspaceEvent.CancelCloseSession)
      val cancelled = awaitItemWhere { it.closeSession == null }
      assertEquals(2, cancelled.sessions.tiles.size)
      assertEquals(emptyList(), sessions.closed)
      assertEquals(emptyList(), sessions.removedWorktrees)
    }
  }

  @Test
  fun `closing and keeping the worktree closes only the session`() = runTest {
    withWorktree()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.CloseSession("s2"))
      awaitItemWhere { it.closeSession != null }.eventSink(WorkspaceEvent.ConfirmCloseSession(deleteWorktrees = false))
      val closed = awaitItemWhere { it.closeSession == null && it.sessions.tiles.size == 1 }
      assertEquals(listOf(SessionId("s2")), sessions.closed)
      assertEquals(emptyList(), sessions.removedWorktrees)
      assertEquals(null, closed.toast)
    }
  }

  @Test
  fun `closing and deleting closes the session first, then forces the worktree it warned about`() = runTest {
    withWorktree()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.CloseSession("s2"))
      awaitItemWhere { it.closeSession != null }.eventSink(WorkspaceEvent.ConfirmCloseSession(deleteWorktrees = true))
      val deleted = awaitItemWhere { it.toast != null }
      assertEquals(listOf(SessionId("s2")), sessions.closed)
      assertEquals(listOf(FakeSessionService.RemoveWorktreeCall(worktree, force = true, stillOpen = emptyList())), sessions.removedWorktrees)
      assertEquals(Res.string.worktree_deleted.asLemonadeCodeString("~/code/storefront.worktrees/sub-2"), deleted.toast)
      assertEquals(null, deleted.closeSession)
    }
  }

  @Test
  fun `a clean worktree is deleted without force`() = runTest {
    withWorktree()
    sessions.worktrees = listOf(worktree.copy(hasChanges = false))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.CloseSession("s2"))
      val asking = awaitItemWhere { it.closeSession != null }
      assertEquals(false, asking.closeSession?.worktrees?.single()?.hasChanges)
      asking.eventSink(WorkspaceEvent.ConfirmCloseSession(deleteWorktrees = true))
      yieldUntil { sessions.removedWorktrees.isNotEmpty() }
      assertEquals(false, sessions.removedWorktrees.single().force)
    }
  }

  @Test
  fun `a worktree git won't delete says why in the error banner`() = runTest {
    withWorktree()
    sessions.removeWorktreeResult = LemonadeCodeResult.failure(SessionError.WorktreeNotRemoved(worktree.path, "it is locked"))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.CloseSession("s2"))
      awaitItemWhere { it.closeSession != null }.eventSink(WorkspaceEvent.ConfirmCloseSession(deleteWorktrees = true))
      val failed = awaitItemWhere { it.error != null }
      assertEquals(Res.string.error_worktree_delete.asLemonadeCodeString("~/code/storefront.worktrees/sub-2", "it is locked"), failed.error)
      assertEquals(listOf(SessionId("s2")), sessions.closed, "the session closed all the same")
      assertEquals(null, failed.toast)
    }
  }

  @Test
  fun `a worktree another open session still uses isn't offered`() = runTest {
    withWorktree(inWorktree("s3", "sub-3"))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.CloseSession("s2"))
      val closed = awaitItemWhere { it.sessions.tiles.size == 2 }
      assertEquals(listOf(SessionId("s2")), sessions.closed)
      assertEquals(null, closed.closeSession)
      assertEquals(emptyList(), sessions.removedWorktrees)
    }
  }

  @Test
  fun `a worktree an open session's agent was started in isn't offered, wherever it works now`() = runTest {
    val repo = ShowcaseFixtures.Repo
    withWorktree(inWorktree("s3", "sub-3").copy(workingDirectory = repo, repositoryRoot = repo, processDirectory = worktree.path))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.CloseSession("s2"))
      val closed = awaitItemWhere { it.sessions.tiles.size == 2 }
      assertEquals(null, closed.closeSession)
      assertEquals(emptyList(), sessions.removedWorktrees)
    }
  }

  @Test
  fun `closing a session whose agent works in another worktree asks about both`() = runTest {
    val agents = LinkedWorktree(path = "${ShowcaseFixtures.Repo}/.claude/worktrees/rate-limit", branch = "worktree-rate-limit", mainCheckout = ShowcaseFixtures.Repo, hasChanges = false)
    sessions.setSessions(
      listOf(
        ShowcaseFixtures.session("ar", "api-ratelimit"),
        inWorktree("s2", "sub-2").copy(workingDirectory = agents.path, repositoryRoot = agents.path, branch = agents.branch, processDirectory = worktree.path),
      ),
    )
    sessions.worktrees = listOf(worktree, agents)
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.CloseSession("s2"))
      val asking = awaitItemWhere { it.closeSession != null }
      assertEquals(listOf("~/code/storefront/.claude/worktrees/rate-limit", "~/code/storefront.worktrees/sub-2"), asking.closeSession?.worktrees?.map { it.path })
    }
  }

  @Test
  fun `closing a parent asks about the worktree its sub-sessions leave, once`() = runTest {
    withWorktree(inWorktree("s3", "sub-3"))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.CloseSession("ar"))
      val asking = awaitItemWhere { it.closeSession != null }
      assertEquals(worktreeDialog.copy(sessionName = "api-ratelimit", subSessionCount = 2), asking.closeSession)
      asking.eventSink(WorkspaceEvent.ConfirmCloseSession(deleteWorktrees = true))
      yieldUntil { sessions.removedWorktrees.isNotEmpty() }
      assertEquals(listOf(ar), sessions.closed)
      assertEquals(emptyList(), sessions.removedWorktrees.single().stillOpen, "every session in it closed first")
    }
  }

  @Test
  fun `command W and the session menu both ask first`() = runTest {
    withWorktree()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectSession("s2"))
      awaitItemWhere { it.header?.name == "sub-2" }.eventSink(WorkspaceEvent.CloseSelectedSession)
      awaitItemWhere { it.closeSession != null }.eventSink(WorkspaceEvent.CancelCloseSession)

      awaitItemWhere { it.closeSession == null }.eventSink(WorkspaceEvent.OpenSessionMenu("s2"))
      awaitItemWhere { it.sessions.menu?.sessionId == "s2" }.eventSink(WorkspaceEvent.CloseSession("s2"))
      val asking = awaitItemWhere { it.closeSession != null }
      assertEquals(null, asking.sessions.menu, "the menu gives way to the dialog")
      assertEquals(worktreeDialog, asking.closeSession)
      assertEquals(emptyList(), sessions.closed)
    }
  }

  @Test
  fun `archiving from the menu puts a session and its sub-sessions under Archived, folded, and says so`() = runTest {
    withFamily()
    presenter.test {
      val start = awaitItem()
      assertEquals(null, start.sessions.archived, "no Archived section while nothing is archived")
      start.eventSink(WorkspaceEvent.OpenSessionMenu("r1"))
      awaitItemWhere { it.sessions.menu?.sessionId == "r1" }.eventSink(WorkspaceEvent.ArchiveSession("r1"))
      val archived = awaitItemWhere { it.sessions.archived != null && it.toast != null }
      assertEquals(listOf(SessionId("r1")), sessions.archivedIds)
      assertEquals(emptyList(), sessions.closed)
      assertEquals(listOf("ar", "cf"), archived.sessions.tiles.map { it.id })
      assertEquals(
        ArchivedSessionsModel(
          open = false,
          rows = listOf(ArchivedSessionRowModel("r1", "review-1", SessionsMapper.meta(sessions.archived.value.single().session, ShowcaseFixtures.Home), subSessionCount = 1)),
        ),
        archived.sessions.archived,
      )
      assertEquals(null, archived.sessions.menu)
      assertEquals("api-ratelimit", archived.header?.name, "the selection stays where it was")
      assertEquals(Res.string.session_archived.asLemonadeCodeString("review-1"), archived.toast)

      archived.eventSink(WorkspaceEvent.ToggleArchivedSessions)
      assertEquals(true, awaitItemWhere { it.sessions.archived?.open == true }.sessions.archived?.open)
    }
  }

  @Test
  fun `archiving the selected session selects the next one at its level, and the last one leaves nothing open`() = runTest {
    withFamily()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ArchiveSession("ar"))
      val next = awaitItemWhere { it.header?.name == "checkout-flake" }
      assertEquals(listOf("cf"), next.sessions.tiles.map { it.id })

      next.eventSink(WorkspaceEvent.ArchiveSession("cf"))
      val none = awaitItemWhere { it.content is WorkspaceContentModel.NoSessions && it.sessions.archived?.rows?.size == 2 }
      assertEquals(null, none.header)
      assertEquals(listOf("cf", "ar"), none.sessions.archived?.rows?.map { it.id }, "the most recently archived first")
    }
  }

  @Test
  fun `archiving the selected session selects its parent, else the one before it`() = runTest {
    withFamily()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectSession("cf"))
      awaitItemWhere { it.header?.name == "checkout-flake" }.eventSink(WorkspaceEvent.ArchiveSession("cf"))
      awaitItemWhere { it.header?.name == "api-ratelimit" }.eventSink(WorkspaceEvent.SelectSession("d2"))
      awaitItemWhere { it.header?.name == "atomic-refill" }.eventSink(WorkspaceEvent.ArchiveSession("d2"))
      awaitItemWhere { it.header?.name == "review-1" }
    }
  }

  @Test
  fun `restoring an archived session brings it back selected, on its terminal`() = runTest {
    withFamily()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ArchiveSession("r1"))
      val archived = awaitItemWhere { it.sessions.archived != null }
      archived.eventSink(WorkspaceEvent.RestoreArchivedSession("r1"))
      // A second click on its way out restores nothing twice.
      archived.eventSink(WorkspaceEvent.RestoreArchivedSession("r1"))
      val back = awaitItemWhere { it.header?.name == "review-1" }
      assertEquals(listOf(SessionId("r1")), sessions.unarchivedIds)
      assertEquals(null, back.sessions.archived)
      assertEquals(WorkspaceContentModel.Terminal("r1"), back.content)
      assertEquals(setOf("ar", "r1", "d2", "cf"), back.sessions.tiles.map { it.id }.toSet())
      assertEquals(null, back.error)
    }
  }

  @Test
  fun `a session whose folder had gone comes back at home, and says so`() = runTest {
    withFamily()
    sessions.missingFolders = setOf(ShowcaseFixtures.Repo)
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ArchiveSession("cf"))
      awaitItemWhere { it.sessions.archived != null }.eventSink(WorkspaceEvent.RestoreArchivedSession("cf"))
      val one = awaitItemWhere { it.error != null }
      assertEquals(Res.string.archived_restored_at_home.asLemonadeCodeString("checkout-flake", "~/code/storefront"), one.error)
      assertEquals("checkout-flake", one.header?.name)

      one.eventSink(WorkspaceEvent.DismissError)
      awaitItemWhere { it.error == null }.eventSink(WorkspaceEvent.ArchiveSession("r1"))
      awaitItemWhere { it.sessions.archived != null }.eventSink(WorkspaceEvent.RestoreArchivedSession("r1"))
      assertEquals(Res.plurals.archived_restored_at_home_many.asLemonadeCodeString(2, 2), awaitItemWhere { it.error != null }.error)
    }
  }

  @Test
  fun `a session that can't come back stays archived and says why`() = runTest {
    withFamily()
    sessions.unarchiveResult = LemonadeCodeResult.failure(SessionError.LaunchFailed("no terminal"))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ArchiveSession("cf"))
      awaitItemWhere { it.sessions.archived != null }.eventSink(WorkspaceEvent.RestoreArchivedSession("cf"))
      val failed = awaitItemWhere { it.error != null }
      assertEquals(Res.string.error_launch.asLemonadeCodeString("no terminal"), failed.error)
      assertEquals(listOf("cf"), failed.sessions.archived?.rows?.map { it.id })
    }
  }

  @Test
  fun `sub-sessions whose shell can't start stay archived, and the banner says how many`() = runTest {
    withFamily()
    sessions.failingToStart = setOf(SessionId("r1"))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ArchiveSession("ar"))
      awaitItemWhere { it.sessions.archived != null }.eventSink(WorkspaceEvent.RestoreArchivedSession("ar"))
      val partly = awaitItemWhere { it.error != null }
      assertEquals(Res.plurals.archived_some_stayed.asLemonadeCodeString(2, 2), partly.error, "review-1, and atomic-refill under it")
      assertEquals("api-ratelimit", partly.header?.name)
      assertEquals(listOf("r1"), partly.sessions.archived?.rows?.map { it.id })
      assertEquals(1, partly.sessions.archived?.rows?.single()?.subSessionCount)
    }
  }

  @Test
  fun `while a restore runs, its rows hide, and neither Restore nor Delete reaches it twice`() = runTest {
    withFamily()
    val gate = CompletableDeferred<Unit>()
    sessions.unarchiveGate = gate
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ArchiveSession("ar"))
      awaitItemWhere { it.sessions.archived != null }.eventSink(WorkspaceEvent.ArchiveSession("cf"))
      awaitItemWhere { it.sessions.archived?.rows?.size == 2 }.eventSink(WorkspaceEvent.RestoreArchivedSession("ar"))
      val restoring = awaitItemWhere { it.sessions.archived?.rows?.size == 1 }
      assertEquals(listOf("cf"), restoring.sessions.archived?.rows?.map { it.id }, "only the other archive shows")
      yieldUntil { sessions.unarchivedIds.isNotEmpty() }
      restoring.eventSink(WorkspaceEvent.RestoreArchivedSession("ar"))
      restoring.eventSink(WorkspaceEvent.DeleteArchivedSession("ar"))
      assertEquals(listOf(ar), sessions.unarchivedIds)
      assertEquals(emptyList(), sessions.closed)

      gate.complete(Unit)
      val back = awaitItemWhere { it.header?.name == "api-ratelimit" && it.sessions.tiles.any { tile -> tile.id == "ar" } }
      assertEquals(listOf("cf"), back.sessions.archived?.rows?.map { it.id })
      assertEquals(null, back.error)
    }
  }

  @Test
  fun `a restore that stayed archived, its parent archived meanwhile, says so and selects nothing new`() = runTest {
    withFamily()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectSession("cf"))
      awaitItemWhere { it.header?.name == "checkout-flake" }.eventSink(WorkspaceEvent.ArchiveSession("r1"))
      sessions.unarchiveResult = LemonadeCodeResult.failure(SessionError.ParentArchived)
      awaitItemWhere { it.sessions.archived != null }.eventSink(WorkspaceEvent.RestoreArchivedSession("r1"))
      val stayed = awaitItemWhere { it.error != null }
      assertEquals(Res.string.archived_parent_archived.asLemonadeCodeString("review-1"), stayed.error)
      assertEquals("checkout-flake", stayed.header?.name)
      assertEquals(listOf("r1"), stayed.sessions.archived?.rows?.map { it.id })
    }
  }

  @Test
  fun `a sub-session not created because its session was archived as it started says so`() = runTest {
    withFamily()
    sessions.startResult = LemonadeCodeResult.failure(SessionError.ParentArchived)
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.CreateSubSessionFrom("ar", LocationModel.SameFolder))
      val failed = awaitItemWhere { it.error != null }
      assertEquals(Res.string.error_parent_archived.asLemonadeCodeString(), failed.error)
      assertEquals("api-ratelimit", failed.header?.name)
    }
  }

  @Test
  fun `archiving a session while it's dragged ends the drag, and it doesn't come back lifted`() = runTest {
    withFamily()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.StartSessionDrag("cf"))
      awaitItemWhere { (it.sessions.navigation as SessionNavigationModel.Tree).drag != null }.eventSink(WorkspaceEvent.ArchiveSession("cf"))
      val archived = awaitItemWhere { it.sessions.archived != null }
      assertEquals(null, (archived.sessions.navigation as SessionNavigationModel.Tree).drag)
      // A release still on its way moves nothing.
      archived.eventSink(WorkspaceEvent.DropSession("cf"))
      assertEquals(emptyList(), sessions.moved)

      archived.eventSink(WorkspaceEvent.RestoreArchivedSession("cf"))
      val back = awaitItemWhere { it.sessions.tiles.any { tile -> tile.id == "cf" } }
      assertEquals(null, (back.sessions.navigation as SessionNavigationModel.Tree).drag, "not lifted again")
    }
  }

  @Test
  fun `deleting an archived session in no worktree lets it go at once`() = runTest {
    withFamily()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ArchiveSession("cf"))
      awaitItemWhere { it.sessions.archived != null }.eventSink(WorkspaceEvent.DeleteArchivedSession("cf"))
      val deleted = awaitItemWhere { it.sessions.archived == null }
      assertEquals(listOf(SessionId("cf")), sessions.closed)
      assertEquals(null, deleted.closeSession)
      // Only archived sessions are deleted this way.
      deleted.eventSink(WorkspaceEvent.DeleteArchivedSession("ar"))
      assertEquals(listOf(SessionId("cf")), sessions.closed)
    }
  }

  @Test
  fun `deleting an archived session asks about its worktree, like closing does`() = runTest {
    withWorktree()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ArchiveSession("s2"))
      assertEquals(emptyList(), sessions.removedWorktrees, "archiving deletes no worktree")
      awaitItemWhere { it.sessions.archived != null }.eventSink(WorkspaceEvent.DeleteArchivedSession("s2"))
      val asking = awaitItemWhere { it.closeSession != null }
      assertEquals(worktreeDialog, asking.closeSession)
      asking.eventSink(WorkspaceEvent.ConfirmCloseSession(deleteWorktrees = true))
      awaitItemWhere { it.toast == Res.string.worktree_deleted.asLemonadeCodeString("~/code/storefront.worktrees/sub-2") }
      assertEquals(listOf(SessionId("s2")), sessions.closed)
      assertEquals(listOf(FakeSessionService.RemoveWorktreeCall(worktree, force = true, stillOpen = emptyList())), sessions.removedWorktrees)
      assertEquals(emptyList(), sessions.archived.value)
    }
  }

  @Test
  fun `a worktree an archived session is still in isn't offered when another closes`() = runTest {
    withWorktree(inWorktree("s3", "sub-3"))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ArchiveSession("s3"))
      awaitItemWhere { it.sessions.archived != null }.eventSink(WorkspaceEvent.CloseSession("s2"))
      val closed = awaitItemWhere { it.sessions.tiles.none { tile -> tile.id == "s2" } }
      assertEquals(listOf(SessionId("s2")), sessions.closed)
      assertEquals(null, closed.closeSession)
      assertEquals(emptyList(), sessions.removedWorktrees)
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
        WorktreeBranchModel(
          name = "worktree/generating-bugs",
          path = "~/code/storefront.worktrees/generating-bugs",
          sessionName = "generating-bugs",
          problem = null,
          canCreate = true,
        ),
        naming.sessions.menu?.newWorktree,
      )
      assertTrue(sessions.started.isEmpty(), "nothing is created before the branch is named")

      naming.eventSink(WorkspaceEvent.SetWorktreeBranch("fix/flaky-login"))
      val typed = awaitItemWhere { it.sessions.menu?.newWorktree?.name == "fix/flaky-login" }
      assertEquals("flaky-login", typed.sessions.menu?.newWorktree?.sessionName)
      typed.eventSink(WorkspaceEvent.CreateSubSessionFrom("d2", LocationModel.NewWorktree))
      val created = awaitItemWhere { it.header?.name == "flaky-login" }
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

  private val WorkspaceModel.drag: SessionDragModel? get() = (sessions.navigation as SessionNavigationModel.Tree).drag

  private fun order(): List<String> = sessions.sessions.value.map { it.id.value }

  @Test
  fun `dragging a session shows where it would go among its siblings, and letting go moves it there`() = runTest {
    withFamily()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.OpenSessionMenu("cf"))
      awaitItemWhere { it.sessions.menu != null }.eventSink(WorkspaceEvent.StartSessionDrag("cf"))
      val picked = awaitItemWhere { it.drag != null }
      assertEquals(SessionDragModel("cf", drop = null), picked.drag, "no line while it's where it was")
      assertEquals(null, picked.sessions.menu, "picking a row up closes its menu")

      picked.eventSink(WorkspaceEvent.DragSession("cf", 0))
      val over = awaitItemWhere { it.drag?.drop != null }
      assertEquals(SessionDropModel(rowId = "ar", below = false), over.drag?.drop)
      assertTrue(sessions.moved.isEmpty(), "nothing moves before letting go")

      over.eventSink(WorkspaceEvent.DropSession("cf"))
      val dropped = awaitItemWhere { it.drag == null && it.sessions.tiles.first().id == "cf" }
      assertEquals(listOf(SessionId("cf") to 0), sessions.moved)
      assertEquals(listOf("cf", "ar", "r1", "d2"), order())
      assertEquals(listOf("cf", "ar", "r1", "d2"), dropped.sessions.tiles.map { it.id })
      assertEquals("api-ratelimit", dropped.header?.name, "the first session was showing, and still is")
    }
  }

  @Test
  fun `past the last sibling the line goes under the last row nested under it`() = runTest {
    sessions.setSessions(
      listOf(
        ShowcaseFixtures.session("cf", "checkout-flake"),
        ShowcaseFixtures.session("ar", "api-ratelimit"),
        ShowcaseFixtures.session("r1", "review-1", parent = "ar"),
        ShowcaseFixtures.session("d2", "atomic-refill", parent = "r1"),
      ),
    )
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.StartSessionDrag("cf"))
      awaitItemWhere { it.drag != null }.eventSink(WorkspaceEvent.DragSession("cf", 1))
      val past = awaitItemWhere { it.drag?.drop != null }
      assertEquals(SessionDropModel(rowId = "d2", below = true), past.drag?.drop)

      // Folded, what's nested under api-ratelimit isn't on show: the line goes under it.
      past.eventSink(WorkspaceEvent.ToggleSessionFold("ar"))
      val folded = awaitItemWhere { it.drag?.drop?.rowId == "ar" }
      assertEquals(SessionDropModel(rowId = "ar", below = true), folded.drag?.drop)

      folded.eventSink(WorkspaceEvent.DropSession("cf"))
      awaitItemWhere { it.drag == null }
      assertEquals(listOf("ar", "r1", "d2", "cf"), order(), "after api-ratelimit and everything under it")
    }
  }

  @Test
  fun `a session that changes as it's picked up starts where it is, with no line`() = runTest {
    withFamily()
    // Every read of the sessions while picking up finds checkout-flake a little different: its
    // agent reporting in between any two reads.
    val changing = ChangingBetweenReads(sessions)
    val presenter = WorkspacePresenter(
      changing,
      reviews,
      settings,
      object : HomeDirectory {
        override val path: String = ShowcaseFixtures.Home
      },
      updates,
      mobile,
      sounds,
      imports,
    ) { Now }
    presenter.test {
      val first = awaitItem()
      changing.session = SessionId("cf")
      first.eventSink(WorkspaceEvent.StartSessionDrag("cf"))
      changing.session = null
      assertEquals(SessionDragModel("cf", drop = null), awaitItemWhere { it.drag != null }.drag, "it's the second of two, where it was")
    }
  }

  /**
   * [fake], except that while [session] is set each read of the sessions' current value finds it
   * with a different agent name, as if it reported between any two reads. What's collected doesn't
   * change, so nothing recomposes because of it.
   */
  @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
  private class ChangingBetweenReads(private val fake: FakeSessionService) : SessionService by fake {
    var session: SessionId? = null
    private var reads = 0

    override val sessions: StateFlow<List<Session>> = object : StateFlow<List<Session>> by fake.sessions {
      override val value: List<Session>
        get() {
          val id = session ?: return fake.sessions.value
          reads++
          return fake.sessions.value.map { if (it.id == id) it.copy(agent = "claude $reads") else it }
        }
    }
  }

  @Test
  fun `letting go where it started, or calling the drag off, moves nothing`() = runTest {
    withFamily()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.StartSessionDrag("r1"))
      // The only sub-session of its parent: there's nowhere else for it to go.
      val alone = awaitItemWhere { it.drag != null }
      alone.eventSink(WorkspaceEvent.DragSession("r1", 3))
      alone.eventSink(WorkspaceEvent.DropSession("r1"))
      awaitItemWhere { it.drag == null }.eventSink(WorkspaceEvent.StartSessionDrag("cf"))

      awaitItemWhere { it.drag != null }.eventSink(WorkspaceEvent.DragSession("cf", 0))
      awaitItemWhere { it.drag?.drop != null }.eventSink(WorkspaceEvent.CancelSessionDrag)
      val cancelled = awaitItemWhere { it.drag == null }
      // A release still on its way after Esc.
      cancelled.eventSink(WorkspaceEvent.DropSession("cf"))
      cancelled.eventSink(WorkspaceEvent.DragSession("cf", 0))
      assertEquals(emptyList(), sessions.moved)
      assertEquals(listOf("ar", "r1", "d2", "cf"), order())
    }
  }

  @Test
  fun `the menu moves a session a place up or down among its siblings, and stays open on it`() = runTest {
    withFamily()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.OpenSessionMenu("cf"))
      val last = awaitItemWhere { it.sessions.menu != null }
      assertEquals(true, last.sessions.menu?.canMoveUp)
      assertEquals(false, last.sessions.menu?.canMoveDown)

      last.eventSink(WorkspaceEvent.MoveSessionUp("cf"))
      val first = awaitItemWhere { it.sessions.menu?.canMoveDown == true }
      assertEquals(listOf("cf", "ar", "r1", "d2"), order())
      assertEquals("cf", first.sessions.menu?.sessionId, "the menu stays open on it, for another step")
      assertEquals(false, first.sessions.menu?.canMoveUp)

      // Already the first: up goes nowhere.
      first.eventSink(WorkspaceEvent.MoveSessionUp("cf"))
      first.eventSink(WorkspaceEvent.MoveSessionDown("cf"))
      awaitItemWhere { it.sessions.menu?.canMoveUp == true }
      assertEquals(listOf(SessionId("cf") to 0, SessionId("cf") to 1), sessions.moved)
      assertEquals(listOf("ar", "r1", "d2", "cf"), order())

      // A sub-session alone under its parent has nowhere to go.
      first.eventSink(WorkspaceEvent.OpenSessionMenu("r1"))
      val alone = awaitItemWhere { it.sessions.menu?.sessionId == "r1" }.sessions.menu!!
      assertEquals(false to false, alone.canMoveUp to alone.canMoveDown)
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

  private val storefrontImport = ImportableSession("herdr:1", "storefront", null, ShowcaseFixtures.Repo, conversation = "chat-1")
  private val worktreeImport = ImportableSession("herdr:2", "rate-limit", null, "${ShowcaseFixtures.Repo}.worktrees/rate-limit", parentKey = "herdr:1", worktree = true)
  private val importedBefore = ImportableSession("herdr:3", "payments", null, "${ShowcaseFixtures.Home}/code/payments", blocked = ImportBlock.AlreadyImported)

  private companion object {
    const val Now: Long = 1_793_600_000_000
  }

  private fun WorkspaceModel.rowOf(deviceId: String): DeviceRowModel? = (devices?.content as? DevicesContentModel.Devices)?.groups?.flatMap { it.devices }?.firstOrNull { it.id == deviceId }

  private fun DeviceWindowModel.page(): DevicesContentModel.Device = panel.content as DevicesContentModel.Device
}
