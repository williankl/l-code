package dev.lemonadecode.workspace.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.platform.localization.asLemonadeCodeString
import dev.lemonadecode.workspace.impl.showcase.ShowcaseFixtures
import dev.lemonadecode.workspace.impl.showcase.WorkspaceShowcase
import dev.lemonadecode.workspace.impl.showcase.WorkspaceShowcaseContent
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.update_failed_download
import dev.lemonadecode.workspace.strings.worktree_branch_invalid
import dev.lemonadecode.workspace.ui.TerminalTranscript
import dev.lemonadecode.workspace.ui.WorkspaceContent
import dev.lemonadecode.workspace.uimodel.CloseSessionDialogModel
import dev.lemonadecode.workspace.uimodel.ClosingWorktreeModel
import dev.lemonadecode.workspace.uimodel.DeviceScreenModel
import dev.lemonadecode.workspace.uimodel.DeviceTab
import dev.lemonadecode.workspace.uimodel.DevicesContentModel
import dev.lemonadecode.workspace.uimodel.DevicesLayoutOption
import dev.lemonadecode.workspace.uimodel.DevicesPanelModel
import dev.lemonadecode.workspace.uimodel.DevicesWindow
import dev.lemonadecode.workspace.uimodel.LanguageOption
import dev.lemonadecode.workspace.uimodel.LogFilter
import dev.lemonadecode.workspace.uimodel.LogLevelModel
import dev.lemonadecode.workspace.uimodel.LogLineModel
import dev.lemonadecode.workspace.uimodel.MobilePlatformModel
import dev.lemonadecode.workspace.uimodel.PairingMethod
import dev.lemonadecode.workspace.uimodel.ScreenPictureModel
import dev.lemonadecode.workspace.uimodel.SessionMenuModel
import dev.lemonadecode.workspace.uimodel.SessionsPanelModel
import dev.lemonadecode.workspace.uimodel.SettingsPage
import dev.lemonadecode.workspace.uimodel.StatusSound
import dev.lemonadecode.workspace.uimodel.SubSessionLocation
import dev.lemonadecode.workspace.uimodel.ThemeOption
import dev.lemonadecode.workspace.uimodel.UpdatePhase
import dev.lemonadecode.workspace.uimodel.WorkspaceContentModel
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import dev.lemonadecode.workspace.uimodel.WorkspaceModel
import dev.lemonadecode.workspace.uimodel.WorkspaceView
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The workspace UI on every design screen: each control raises the event the presenter expects. */
@OptIn(ExperimentalTestApi::class)
class WorkspaceContentUiTest {
  private val events = mutableListOf<WorkspaceEvent>()
  private val rateLimit = ShowcaseFixtures.path("rl")
  private val app = ShowcaseFixtures.path("app")

  private fun show(showcase: WorkspaceShowcase, block: DesktopComposeUiTest.() -> Unit) = runDesktopComposeUiTest(width = showcase.widthDp, height = showcase.heightDp) {
    setContent {
      LemonadeCodeTheme { WorkspaceShowcaseContent(showcase, onEvent = { events += it }) }
    }
    waitForIdle()
    block()
  }

  /** [showcase] with its Model changed by [edit], e.g. to open a menu the design doesn't show. */
  private fun show(
    showcase: WorkspaceShowcase,
    edit: (WorkspaceModel) -> WorkspaceModel,
    clipboard: Clipboard = FakeClipboard(),
    block: DesktopComposeUiTest.() -> Unit,
  ) = runDesktopComposeUiTest(width = showcase.widthDp, height = showcase.heightDp) {
    setContent {
      LemonadeCodeTheme {
        CompositionLocalProvider(LocalClipboard provides clipboard) {
          WorkspaceContent(
            model = edit(showcase.model { events += it }),
            terminal = { id, modifier -> TerminalTranscript(showcase.transcript(id), modifier) },
          )
        }
      }
    }
    waitForIdle()
    block()
  }

  private fun DesktopComposeUiTest.expect(event: WorkspaceEvent, action: () -> Unit) {
    events.clear()
    action()
    waitForIdle()
    assertContains(events, event)
  }

  @Test
  fun `the top bar and session list raise their events`() = show(WorkspaceShowcase.AgentFinishes) {
    expect(WorkspaceEvent.NewSession) { onNodeWithContentDescription("New terminal").performClick() }
    expect(WorkspaceEvent.SelectSession("cf")) { onNodeWithText("checkout-flake").performClick() }
    expect(WorkspaceEvent.SelectView(WorkspaceView.Diff)) { onNodeWithText("Diff").performClick() }
    expect(WorkspaceEvent.ToggleSettings) { onNodeWithContentDescription("Settings").performClick() }
    expect(WorkspaceEvent.ToggleSessionsPanel(narrowLayout = false)) { onNodeWithContentDescription("Collapse sessions").performClick() }
  }

  @Test
  fun `dragging the sidebar's edge resizes it and reports the width once let go`() = show(
    WorkspaceShowcase.AgentFinishes,
    edit = { model -> model.copy(sessions = model.sessions.copy(sidebarWidth = 300)) },
  ) {
    val sidebar = onNodeWithContentDescription("Sessions")
    val handle = onNodeWithContentDescription("Resize sessions")
    sidebar.assertWidthIsEqualTo(300.dp)

    events.clear()
    handle.performMouseInput {
      moveTo(center)
      press()
      moveBy(Offset(40f, 0f))
      moveBy(Offset(40f, 0f))
    }
    waitForIdle()
    // The edge follows the pointer, but nothing is saved until the drag ends.
    assertTrue(sidebar.getUnclippedBoundsInRoot().width > 300.dp)
    assertTrue(events.none { it is WorkspaceEvent.ResizeSessionsSidebar })
    handle.performMouseInput { release() }
    waitForIdle()
    val widened = events.filterIsInstance<WorkspaceEvent.ResizeSessionsSidebar>().single()
    assertTrue(widened.width in 301..380, "width was ${widened.width}")

    // Past the limit, the narrowest width is kept.
    expect(WorkspaceEvent.ResizeSessionsSidebar(SessionsPanelModel.MinSidebarWidth)) {
      handle.performMouseInput {
        moveTo(center)
        press()
        moveBy(Offset(-1_000f, 0f))
        release()
      }
    }
    sidebar.assertWidthIsEqualTo(SessionsPanelModel.MinSidebarWidth.dp)
  }

  @Test
  fun `dragging the docked devices panel's edge towards the session widens it`() = show(WorkspaceShowcase.DisplayMain, edit = { model ->
    model.copy(devices = model.devices?.copy(window = DevicesWindow.Docked, width = 500))
  }) {
    val handle = onNodeWithContentDescription("Resize devices")
    events.clear()
    handle.performMouseInput {
      moveTo(center)
      press()
      moveBy(Offset(-40f, 0f))
      moveBy(Offset(-40f, 0f))
    }
    waitForIdle()
    assertTrue(events.none { it is WorkspaceEvent.ResizeDevicesPanel })
    handle.performMouseInput { release() }
    waitForIdle()
    val widened = events.filterIsInstance<WorkspaceEvent.ResizeDevicesPanel>().single()
    assertTrue(widened.width in 501..580, "width was ${widened.width}")

    // Past the limit, the narrowest width is kept.
    expect(WorkspaceEvent.ResizeDevicesPanel(DevicesPanelModel.MinWidth)) {
      handle.performMouseInput {
        moveTo(center)
        press()
        moveBy(Offset(1_000f, 0f))
        release()
      }
    }
  }

  @Test
  fun `the branch picker opens and picks a branch to compare with`() {
    show(WorkspaceShowcase.CommentOnDiff) {
      expect(WorkspaceEvent.ToggleDiffBasePicker) { onNodeWithText("main").performClick() }
    }
    show(WorkspaceShowcase.CommentOnDiff, edit = { model -> model.withBasePickerOpen() }) {
      onNodeWithText("Compare working tree with").assertExists()
      expect(WorkspaceEvent.CompareWith("release/2024.11")) { onNodeWithText("release/2024.11").performClick() }
    }
  }

  private fun WorkspaceModel.withBasePickerOpen(): WorkspaceModel {
    val diff = content as WorkspaceContentModel.Diff
    val toolbar = diff.review.toolbar
    return copy(content = diff.copy(review = diff.review.copy(toolbar = toolbar.copy(base = toolbar.base.copy(pickerOpen = true)))))
  }

  @Test
  fun `the comment composer edits, adds and cancels`() = show(WorkspaceShowcase.CommentOnDiff) {
    onNodeWithText("get → set is racy", substring = true).assertExists()
    expect(WorkspaceEvent.UpdateCommentDraft("Use a Lua script")) {
      onNode(hasSetTextAction()).performTextReplacement("Use a Lua script")
    }
    expect(WorkspaceEvent.AddComment) { onNodeWithText("Add comment").performClick() }
    expect(WorkspaceEvent.CancelComment) { onNodeWithText("Cancel").performClick() }
    expect(WorkspaceEvent.DeleteComment("rl17")) { onAllNodesWithContentDescription("Delete comment")[0].performClick() }
  }

  @Test
  fun `files fold, open in full and the send bar sends`() = show(WorkspaceShowcase.CommentOnDiff) {
    expect(WorkspaceEvent.ToggleFileExpanded(rateLimit)) { onAllNodesWithContentDescription("Collapse file")[0].performClick() }
    onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Full file"))
    expect(WorkspaceEvent.ToggleFullFile(app)) { onNodeWithText("Full file").performClick() }
    expect(WorkspaceEvent.RefreshDiff) { onNodeWithContentDescription("Refresh diff").performClick() }
    expect(WorkspaceEvent.TogglePreview) { onNodeWithText("Preview").performClick() }
    expect(WorkspaceEvent.SendToNewSubSession) { onNodeWithText("New sub-session").performClick() }
    expect(WorkspaceEvent.SendToSession) { onNodeWithText("Paste into terminal").performClick() }
  }

  @Test
  fun `a file's path in its header can be selected and copied without folding the file`() {
    val clipboard = FakeClipboard()
    show(WorkspaceShowcase.CommentOnDiff, edit = { it }, clipboard = clipboard) {
      events.clear()
      selectAcross(rateLimit)
      assertEquals(emptyList(), events, "a drag over the path selects it and does nothing else")
      onAllNodes(isRoot())[0].performKeyInput { withKeyDown(CopyKey) { pressKey(Key.C) } }
      waitForIdle()
      assertEquals(rateLimit, clipboard.text)
    }
  }

  @Test
  fun `with its path selected, a file's header still folds and opens in full on a click`() = show(WorkspaceShowcase.CommentOnDiff, edit = { it }) {
    selectAcross(rateLimit)
    expect(WorkspaceEvent.ToggleFileExpanded(rateLimit)) { onAllNodesWithContentDescription("Collapse file")[0].performClick() }
    // A click on the path itself, without a drag, still does nothing.
    events.clear()
    onNodeWithText(rateLimit).performMouseInput { click(center) }
    waitForIdle()
    assertEquals(emptyList(), events)
    onNode(hasScrollToIndexAction()).performScrollToNode(hasText(app))
    selectAcross(app)
    expect(WorkspaceEvent.ToggleFullFile(app)) { onNodeWithText("Full file").performClick() }
  }

  @Test
  fun `a file's copy button names itself and puts the path on the clipboard`() {
    val clipboard = FakeClipboard()
    show(WorkspaceShowcase.CommentOnDiff, edit = { it }, clipboard = clipboard) {
      val copy = onAllNodesWithContentDescription("Copy path")[0]
      copy.performMouseInput { moveTo(center) }
      waitForIdle()
      onNodeWithText("Copy path").assertExists()
      expect(WorkspaceEvent.CopiedFilePath(rateLimit)) { copy.performClick() }
      assertEquals(rateLimit, clipboard.text)
    }
  }

  /** Drags the mouse from the first letter of [text] to past its last, as a person selecting it. */
  private fun DesktopComposeUiTest.selectAcross(text: String) {
    onNodeWithText(text).performMouseInput {
      moveTo(Offset(1f, centerY))
      press()
      moveTo(centerRight + Offset(20f, 0f), delayMillis = 50)
      release()
    }
    waitForIdle()
  }

  @Test
  fun `hovering a line shows its comment button and pressing it starts a range`() = show(WorkspaceShowcase.CommentOnDiff) {
    onNodeWithText("Date.now", substring = true).performMouseInput { moveTo(center) }
    waitForIdle()
    events.clear()
    onNodeWithContentDescription("Comment on rateLimit.ts:18").performMouseInput {
      moveTo(center)
      press()
      release()
    }
    waitForIdle()
    assertEquals(listOf(WorkspaceEvent.StartCommentRange(rateLimit, 17), WorkspaceEvent.EndCommentRange), events)
  }

  @Test
  fun `dragging from the comment button selects a range of lines`() = show(WorkspaceShowcase.CommentOnDiff) {
    val plus = onNodeWithContentDescription("Comment on rateLimit.ts:19")
    val from = plus.fetchSemanticsNode().boundsInRoot
    val to = onNodeWithText("apiKey) return", substring = true).fetchSemanticsNode().boundsInRoot.center
    events.clear()
    plus.performMouseInput {
      moveTo(center)
      press()
      moveTo(center + Offset(0f, to.y - from.center.y), delayMillis = 50)
      release()
    }
    waitForIdle()
    assertEquals(
      listOf(WorkspaceEvent.StartCommentRange(rateLimit, 18), WorkspaceEvent.DragCommentRange(rateLimit, 14), WorkspaceEvent.EndCommentRange),
      events,
    )
  }

  @Test
  fun `the settings list opens each item on its own page`() = show(WorkspaceShowcase.SettingsList) {
    onNodeWithText("1.9.0 available").assertExists()
    expect(WorkspaceEvent.OpenSettingsPage(SettingsPage.Appearance)) { onNodeWithText("Appearance").performClick() }
    expect(WorkspaceEvent.OpenSettingsPage(SettingsPage.Language)) { onNodeWithText("Language").performClick() }
    expect(WorkspaceEvent.OpenSettingsPage(SettingsPage.KeepAwake)) { onNodeWithText("Keep awake while sessions work").performClick() }
    expect(WorkspaceEvent.OpenSettingsPage(SettingsPage.Sounds)) { onNodeWithText("Sounds").performClick() }
    expect(WorkspaceEvent.OpenSettingsPage(SettingsPage.Plugins)) { onNodeWithText("Plugins").performClick() }
    expect(WorkspaceEvent.OpenSettingsPage(SettingsPage.Updates)) { onNodeWithText("Updates").performClick() }
  }

  @Test
  fun `a settings page goes back to the list`() = show(WorkspaceShowcase.SettingsPlugins) {
    expect(WorkspaceEvent.SettingsBack) { onNodeWithContentDescription("Back").performClick() }
  }

  @Test
  fun `the appearance page changes the theme`() = show(WorkspaceShowcase.SettingsList, edit = { it.copy(settings = it.settings.copy(page = SettingsPage.Appearance)) }) {
    expect(WorkspaceEvent.SetTheme(ThemeOption.Light)) { onNodeWithText("Light").performClick() }
  }

  @Test
  fun `the keep awake page turns it off`() = show(WorkspaceShowcase.SettingsOpen, edit = { it.copy(settings = it.settings.copy(page = SettingsPage.KeepAwake)) }) {
    expect(WorkspaceEvent.SetKeepAwake(false)) { onNode(isToggleable()).performClick() }
  }

  @Test
  fun `the sounds row says whether they're on`() {
    show(WorkspaceShowcase.SettingsList) { onNode(hasText("Sounds") and hasText("On")).assertExists() }
    show(WorkspaceShowcase.SettingsList, edit = { it.copy(settings = it.settings.copy(statusSounds = false)) }) {
      onNode(hasText("Sounds") and hasText("Off")).assertExists()
    }
  }

  @Test
  fun `the sounds page turns them off and plays each one`() = show(WorkspaceShowcase.SettingsSounds) {
    onNodeWithText("Plays a ding when an agent in any session finishes", substring = true).assertExists()
    expect(WorkspaceEvent.SetStatusSounds(false)) { onNode(isToggleable()).performClick() }
    val play = onAllNodesWithText("Play")
    play.assertCountEquals(2)
    expect(WorkspaceEvent.PlayStatusSound(StatusSound.Finished)) { play[0].performClick() }
    expect(WorkspaceEvent.PlayStatusSound(StatusSound.NeedsInput)) { play[1].performClick() }
  }

  @Test
  fun `the language page lists each language in its own name`() = show(
    WorkspaceShowcase.SettingsLanguage,
    edit = { it.copy(settings = it.settings.copy(language = LanguageOption.System)) },
  ) {
    onNodeWithText("Match system").assertExists()
    onNodeWithText("Deutsch").assertExists()
    expect(WorkspaceEvent.SetLanguage(LanguageOption.French)) { onNodeWithText("Français").performClick() }
  }

  @Test
  fun `the plugins page turns Mobile off and opens the devices`() = show(WorkspaceShowcase.SettingsPlugins) {
    onNodeWithText("2 Android · 2 iOS connected").assertExists()
    expect(WorkspaceEvent.SetMobilePlugin(false)) { onNode(isToggleable()).performClick() }
    expect(WorkspaceEvent.OpenDevices) { onNodeWithText("Open devices").performClick() }
  }

  @Test
  fun `the updates page installs the new version`() = show(WorkspaceShowcase.SettingsUpdates) {
    onNodeWithText("Version 1.9.0 is available").assertExists()
    onNodeWithText("What’s new · 1.9.0").assertExists()
    expect(WorkspaceEvent.InstallUpdate) { onNodeWithText("Update now").performClick() }
  }

  @Test
  fun `once downloaded, the updates page restarts into it`() = show(
    WorkspaceShowcase.SettingsUpdates,
    edit = { it.copy(settings = it.settings.copy(updates = it.settings.updates.copy(phase = UpdatePhase.Downloaded))) },
  ) {
    expect(WorkspaceEvent.RestartToUpdate) { onNodeWithText("Restart now").performClick() }
  }

  @Test
  fun `while the update downloads, it can't be started twice`() = show(
    WorkspaceShowcase.SettingsUpdates,
    edit = { it.copy(settings = it.settings.copy(updates = it.settings.updates.copy(phase = UpdatePhase.Downloading, progress = 0.42f))) },
  ) {
    onNodeWithText("Downloading…").assertIsNotEnabled()
  }

  @Test
  fun `a failed update says why and offers to try again`() = show(
    WorkspaceShowcase.SettingsUpdates,
    edit = { model ->
      val failed = model.settings.updates.copy(phase = UpdatePhase.Failed, status = Res.string.update_failed_download.asLemonadeCodeString())
      model.copy(settings = model.settings.copy(updates = failed))
    },
  ) {
    onNodeWithText("Couldn’t download the update. Check your connection and try again.").assertExists()
    expect(WorkspaceEvent.InstallUpdate) { onNodeWithText("Try again").performClick() }
  }

  @Test
  fun `the gear carries a dot while an update waits, and the phone button opens the devices`() = show(WorkspaceShowcase.DisplayMain) {
    expect(WorkspaceEvent.ToggleDevices) { onNodeWithContentDescription("Devices · open in a separate window").performClick() }
  }

  @Test
  fun `the device list opens a device and starts pairing`() = show(WorkspaceShowcase.DeviceList) {
    onNodeWithText("4 connected").assertExists()
    expect(WorkspaceEvent.SelectDevice(ShowcaseDevicesIds.Pixel)) { onNodeWithText("Pixel 8").performClick() }
    expect(WorkspaceEvent.StartPairing) { onNodeWithText("Pair a device").performClick() }
    expect(WorkspaceEvent.MoveDevices(DevicesWindow.Separate)) { onNodeWithContentDescription("Open in a separate window").performClick() }
    expect(WorkspaceEvent.CloseDevices) { onNodeWithContentDescription("Close devices").performClick() }
  }

  @Test
  fun `a device's screen and logs raise their events`() = show(WorkspaceShowcase.DeviceSideBySide) {
    expect(WorkspaceEvent.PressDeviceHome) { onNodeWithContentDescription("Home").performClick() }
    expect(WorkspaceEvent.ScreenshotToSession) { onNodeWithContentDescription("Screenshot to session").performClick() }
    expect(WorkspaceEvent.ToggleDeviceKeyboard) { onNodeWithContentDescription("Type from this keyboard").performClick() }
    expect(WorkspaceEvent.SetLogFilter(LogFilter.Errors)) { onNodeWithText("Errors").performClick() }
    expect(WorkspaceEvent.ToggleLogSearch) { onNodeWithContentDescription("Filter lines").performClick() }
    expect(WorkspaceEvent.ToggleLogsPaused) { onNodeWithText("Live").performClick() }
    expect(WorkspaceEvent.SendLogErrors) { onNodeWithText("Send 2 errors to claude").performClick() }
    expect(WorkspaceEvent.SetDevicesLayout(DevicesLayoutOption.Tabs)) { onNodeWithContentDescription("Show screen and logs as tabs").performClick() }
    // The header's back arrow comes before the phone's own Back button.
    expect(WorkspaceEvent.DevicesBack) { onAllNodesWithContentDescription("Back")[0].performClick() }
  }

  @Test
  fun `dragging across log lines selects them, and the copy keys copy them as text, a line each`() {
    val clipboard = FakeClipboard()
    show(WorkspaceShowcase.DeviceSideBySide, edit = { it }, clipboard = clipboard) {
      selectLogLines(from = "10:18:31.737", to = "18 of 20 requests")
      onRoot().performKeyInput { withKeyDown(CopyKey) { pressKey(Key.C) } }
      waitForIdle()
      assertEquals(
        """
        10:18:31.737 D  OkHttp --> POST /api/cart/checkout
        10:18:32.474 D  OkHttp <-- 200 OK /api/cart/checkout (184 ms)
        10:18:33.948 W  RateLimiter 18 of 20 requests used in the 60 s window
        """.trimIndent(),
        clipboard.text,
      )
    }
  }

  @Test
  fun `the right-click menu copies the selected log lines too`() {
    val clipboard = FakeClipboard()
    show(WorkspaceShowcase.DeviceSideBySide, edit = { it }, clipboard = clipboard) {
      selectLogLines(from = "429 Too Many Requests", to = "Checkout failed")
      onNodeWithText("429 Too Many Requests", substring = true).performMouseInput { rightClick(Offset(60f, 6f)) }
      waitForIdle()
      onNodeWithText("Copy").performClick()
      waitForIdle()
      assertEquals(
        """
        10:18:35.422 E  RateLimiter 429 Too Many Requests · retry-after 30 s
        10:18:36.159 E  CheckoutViewModel Checkout failed: RateLimitException at RateLimitInterceptor.kt:42
        """.trimIndent(),
        clipboard.text,
      )
    }
  }

  @Test
  fun `the copy button copies every line shown, and says it did`() {
    val clipboard = FakeClipboard()
    show(WorkspaceShowcase.DeviceSideBySide, edit = { it }, clipboard = clipboard) {
      onNodeWithContentDescription("Copy the lines shown").performClick()
      waitForIdle()
      val copied = clipboard.text.orEmpty().lines()
      assertEquals(9, copied.size)
      assertEquals("10:18:31.737 D  OkHttp --> POST /api/cart/checkout", copied.first())
      assertEquals("10:18:39.107 D  OkHttp <-- 200 OK /api/cart/checkout (171 ms)", copied.last())
      onNodeWithContentDescription("Copied").assertExists()
    }
  }

  @Test
  fun `live logs follow the newest line, but hold still while lines are selected`() {
    var count by mutableStateOf(60)
    show(WorkspaceShowcase.DeviceSideBySide, edit = { it.withLogLines(manyLogLines(count)) }) {
      onNodeWithText("line 60.", substring = true).assertIsDisplayed()
      count = 70
      waitForIdle()
      onNodeWithText("line 70.", substring = true).assertIsDisplayed()

      selectLogLines(from = "line 66.", to = "line 68.")
      count = 120
      waitForIdle()
      onNodeWithText("line 66.", substring = true).assertIsDisplayed()
      onNodeWithText("line 120.", substring = true).assertDoesNotExist()

      // A plain click clears the selection, and the list catches up.
      onNodeWithText("line 67.", substring = true).performMouseInput { click(Offset(20f, 4f)) }
      waitForIdle()
      onNodeWithText("line 120.", substring = true).assertIsDisplayed()
    }
  }

  @Test
  fun `scrolling up stops following the live lines, and scrolling back down follows again`() {
    var count by mutableStateOf(60)
    show(WorkspaceShowcase.DeviceSideBySide, edit = { it.withLogLines(manyLogLines(count)) }) {
      onNodeWithText("line 60.", substring = true).performMouseInput {
        moveTo(center)
        scroll(-200f)
      }
      waitForIdle()
      onNodeWithText("line 1.", substring = true).assertIsDisplayed()
      count = 80
      waitForIdle()
      onNodeWithText("line 1.", substring = true).assertIsDisplayed()
      onNodeWithText("line 80.", substring = true).assertDoesNotExist()

      onNodeWithText("line 1.", substring = true).performMouseInput {
        moveTo(center)
        scroll(400f)
      }
      waitForIdle()
      count = 90
      waitForIdle()
      onNodeWithText("line 90.", substring = true).assertIsDisplayed()
    }
  }

  @Test
  fun `a selection still copies the lines it reaches once they scroll out of sight`() {
    val clipboard = FakeClipboard()
    show(WorkspaceShowcase.DeviceSideBySide, edit = { it.withLogLines(manyLogLines(60)) }, clipboard = clipboard) {
      selectLogLines(from = "line 40.", to = "line 42.")
      onNodeWithText("line 42.", substring = true).performMouseInput {
        moveTo(center)
        scroll(-200f)
      }
      waitForIdle()
      onNodeWithText("line 1.", substring = true).assertIsDisplayed()
      onRoot().performKeyInput { withKeyDown(CopyKey) { pressKey(Key.C) } }
      waitForIdle()
      assertEquals(listOf("line 40.", "line 41.", "line 42."), clipboard.text.orEmpty().lines().map { it.substringAfter("Tag ") })
    }
  }

  @Test
  fun `the log's source opens the choice of whose lines it shows`() = show(WorkspaceShowcase.DeviceSideBySide) {
    onNodeWithText("logcat · com.teya.storefront").assertExists()
    expect(WorkspaceEvent.ToggleLogApps) { onNodeWithContentDescription("Choose whose logs to show").performClick() }
  }

  @Test
  fun `the choice of apps follows the app in front, shows every app, or ticks apps found by name`() = show(WorkspaceShowcase.DeviceLogApps) {
    onNodeWithText("Show logs from").assertExists()
    onNodeWithText("In front").assertExists()
    expect(WorkspaceEvent.FollowAppInFront) { onNodeWithText("App in front").performClick() }
    expect(WorkspaceEvent.ShowAllApps) { onNodeWithText("All apps").performClick() }
    expect(WorkspaceEvent.ToggleLogApp("com.teya.kitchen")) { onNodeWithText("com.teya.kitchen").performClick() }
    expect(WorkspaceEvent.ToggleLogApp("com.teya.pos")) { onNodeWithText("com.teya.pos").performClick() }

    onNode(hasSetTextAction() and hasText("Find an app")).assertExists()
    val find = onNode(hasSetTextAction())
    find.performTextReplacement("loyal")
    waitForIdle()
    onNodeWithText("com.teya.kitchen").assertDoesNotExist()
    onNodeWithText("com.teya.loyalty").assertExists()
    find.performTextReplacement("nothing like it")
    waitForIdle()
    onNodeWithText("No app matches.").assertExists()
    expect(WorkspaceEvent.DismissLogApps) { find.performKeyInput { pressKey(Key.Escape) } }
  }

  @Test
  fun `an iPhone's log has no choice of apps`() = show(WorkspaceShowcase.DeviceTabs) {
    onNodeWithText("idevicesyslog · all processes").assertExists()
    onNodeWithContentDescription("Choose whose logs to show").assertDoesNotExist()
  }

  @Test
  fun `in tabs, the device shows one pane and switches between them`() = show(WorkspaceShowcase.DeviceTabs) {
    onNodeWithText("idevicesyslog · all processes").assertExists()
    expect(WorkspaceEvent.SetDeviceTab(DeviceTab.Screen)) { onNodeWithText("Screen").performClick() }
  }

  @Test
  fun `a video frame's pixels draw in their own colours`() {
    // Two pixels, blue then green then red then alpha each: pure red.
    val red = ScreenPictureModel.Bgra(byteArrayOf(0, 0, -1, -1, 0, 0, -1, -1), 2, 1, 8)
    show(WorkspaceShowcase.DisplayMain, edit = { model ->
      val devices = model.devices
      val device = devices?.content as DevicesContentModel.Device
      model.copy(devices = devices.copy(window = DevicesWindow.Docked, content = device.copy(screen = DeviceScreenModel.Frame(red, 1080, 2400))))
    }) {
      val home = onNodeWithContentDescription("Home").getUnclippedBoundsInRoot()
      val pixels = onRoot().captureToImage().toPixelMap()
      // Well inside the picture, which ends just above its buttons.
      val x = ((home.left + home.right) / 2).value.toInt()
      val y = (home.top - 120.dp).value.toInt()
      val colour = pixels[x, y]
      assertTrue(colour.red > 0.9f && colour.green < 0.1f && colour.blue < 0.1f, "the picture drew $colour")
    }
  }

  @Test
  fun `a narrow panel in tabs still offers the screen and logs together`() = show(WorkspaceShowcase.DeviceTabs) {
    expect(WorkspaceEvent.SetDevicesLayout(DevicesLayoutOption.SideBySide)) {
      onNodeWithContentDescription("Show screen and logs together").performClick()
    }
  }

  @Test
  fun `a portrait screen sits beside its logs when there's room`() = show(WorkspaceShowcase.DeviceSideBySide) {
    val screen = onNodeWithContentDescription("Home").getUnclippedBoundsInRoot()
    val logs = onNodeWithText("Live").getUnclippedBoundsInRoot()
    assertTrue(screen.right < logs.left, "the screen's controls are left of the logs")
  }

  @Test
  fun `a landscape screen goes above its logs`() = show(WorkspaceShowcase.DeviceLandscape) {
    val screen = onNodeWithContentDescription("Home").getUnclippedBoundsInRoot()
    val logs = onNodeWithText("Live").getUnclippedBoundsInRoot()
    assertTrue(screen.bottom < logs.top, "the screen's controls are above the logs")
  }

  @Test
  fun `without room beside its logs, a portrait screen goes above them`() = show(WorkspaceShowcase.DeviceStacked) {
    val screen = onNodeWithContentDescription("Home").getUnclippedBoundsInRoot()
    val logs = onNodeWithText("Live").getUnclippedBoundsInRoot()
    assertTrue(screen.bottom < logs.top, "the screen's controls are above the logs")
  }

  @Test
  fun `pairing asks one thing at a time`() {
    show(WorkspaceShowcase.PairPlatform) {
      onNodeWithText("Step 1 of 3").assertExists()
      expect(WorkspaceEvent.ChoosePairingPlatform(MobilePlatformModel.Android)) { onNodeWithText("Android").performClick() }
    }
    show(WorkspaceShowcase.PairMethod) {
      onNodeWithText("1 found with wireless debugging on").assertExists()
      expect(WorkspaceEvent.ChoosePairingMethod(PairingMethod.PairingCode)) { onNodeWithText("Enter a pairing code").performClick() }
    }
    show(WorkspaceShowcase.PairCode) {
      expect(WorkspaceEvent.PairWithCode) { onNodeWithText("Pair").performClick() }
    }
    show(WorkspaceShowcase.Paired) {
      onNodeWithText("Pixel 7a is connected").assertExists()
      expect(WorkspaceEvent.OpenPairedDevice) { onNodeWithText("Open Pixel 7a").performClick() }
      expect(WorkspaceEvent.StartPairing) { onNodeWithText("Pair another device").performClick() }
    }
  }

  @Test
  fun `the base picker finds a branch by name`() = show(WorkspaceShowcase.DiffBasePicker) {
    onNodeWithText("Default branch · updated 2 h ago").assertExists()
    onNodeWithText("HEAD").assertExists()
    onNode(hasSetTextAction()).performTextReplacement("auth")
    waitForIdle()
    onNodeWithText("main").assertDoesNotExist()
    expect(WorkspaceEvent.CompareWith("feat/auth-tokens")) { onNodeWithText("feat/auth-tokens").performClick() }
    onNode(hasSetTextAction()).performTextReplacement("nothing-like-it")
    waitForIdle()
    onNodeWithText("No branch matches that name.").assertExists()
  }

  @Test
  fun `the changed files jump to a file and can be hidden`() = show(WorkspaceShowcase.DiffFileList) {
    expect(WorkspaceEvent.RevealFile(ShowcaseFixtures.path("cfg"))) { onNodeWithText("config.ts").performClick() }
    expect(WorkspaceEvent.ShowFileList(false)) { onNodeWithContentDescription("Hide changed files").performClick() }
  }

  @Test
  fun `the sub-session panel switches location, creates and cancels`() = show(WorkspaceShowcase.ChoosingWorktree) {
    onNodeWithText("~/code/storefront.worktrees/generating-bugs").assertExists()
    expect(WorkspaceEvent.SetSubSessionLocation(SubSessionLocation.SameFolder)) { onNodeWithText("Same folder").performClick() }
    expect(WorkspaceEvent.CreateSubSession) { onNodeWithText("Create sub-session").performClick() }
    expect(WorkspaceEvent.DismissNewSubSessionMenu) { onNodeWithText("Cancel").performClick() }
  }

  @Test
  fun `the panel's branch field starts focused on the suggestion, creates on Enter and closes on Escape`() = show(WorkspaceShowcase.ChoosingWorktree) {
    val field = onNode(hasSetTextAction() and hasText("Branch"))
    field.assertIsFocused()
    // Typing replaces the playful part and keeps `worktree/`.
    field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange("worktree/".length, "worktree/generating-bugs".length)))
    expect(WorkspaceEvent.SetWorktreeBranch("feat/login")) { field.performTextReplacement("feat/login") }
    expect(WorkspaceEvent.CreateSubSession) { field.performKeyInput { pressKey(Key.Enter) } }
    expect(WorkspaceEvent.DismissNewSubSessionMenu) { field.performKeyInput { pressKey(Key.Escape) } }
  }

  @Test
  fun `a branch that can't be used says why, and neither Enter nor Create starts it`() = show(
    WorkspaceShowcase.ChoosingWorktree,
    edit = { model ->
      val branch = model.newSubSession.worktreeBranch!!.copy(name = "my branch", problem = Res.string.worktree_branch_invalid.asLemonadeCodeString(), canCreate = false)
      model.copy(newSubSession = model.newSubSession.copy(worktreeBranch = branch))
    },
  ) {
    onNodeWithText("Git won’t take this as a branch name.").assertExists()
    onNodeWithText("Create sub-session").assertIsNotEnabled()
    events.clear()
    onNode(hasSetTextAction() and hasText("Branch")).performKeyInput { pressKey(Key.Enter) }
    waitForIdle()
    assertEquals(emptyList(), events)
  }

  @Test
  fun `the menu's branch field creates on Enter or Create and closes on Escape or Cancel`() = show(WorkspaceShowcase.NamingWorktree, edit = { it }) {
    onNodeWithText("Copy folder path").assertDoesNotExist()
    onNodeWithText("~/code/storefront.worktrees/generating-bugs").assertExists()
    val field = onNode(hasSetTextAction() and hasText("Branch"))
    field.assertIsFocused()
    expect(WorkspaceEvent.SetWorktreeBranch("fix/flaky")) { field.performTextReplacement("fix/flaky") }
    expect(WorkspaceEvent.CreateSubSessionFrom("d3", SubSessionLocation.NewWorktree)) { field.performKeyInput { pressKey(Key.Enter) } }
    expect(WorkspaceEvent.DismissSessionMenu) { field.performKeyInput { pressKey(Key.Escape) } }
    expect(WorkspaceEvent.CreateSubSessionFrom("d3", SubSessionLocation.NewWorktree)) { onNodeWithText("Create sub-session").performClick() }
    expect(WorkspaceEvent.DismissSessionMenu) { onNodeWithText("Cancel").performClick() }
  }

  @Test
  fun `tree rows fold their sub-sessions`() = show(WorkspaceShowcase.SessionTree) {
    expect(WorkspaceEvent.ToggleSessionFold("ar")) { onNodeWithContentDescription("Hide 6 sub-sessions").performClick() }
    expect(WorkspaceEvent.ToggleNewSubSessionMenu) { onNodeWithContentDescription("New sub-session").performClick() }
  }

  @Test
  fun `narrow windows toggle the drawer`() = show(WorkspaceShowcase.NarrowWrap) {
    expect(WorkspaceEvent.ToggleSessionsPanel(narrowLayout = true)) { onNodeWithContentDescription("Expand sessions").performClick() }
  }

  @Test
  fun `keyboard shortcuts open, send and close`() = show(WorkspaceShowcase.CommentOnDiff) {
    fun command(key: Key) = onAllNodes(isRoot())[0].performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(key) } }
    expect(WorkspaceEvent.NewSession) { command(Key.T) }
    expect(WorkspaceEvent.SendToSession) { command(Key.Enter) }
    expect(WorkspaceEvent.CloseSelectedSession) { command(Key.W) }
  }

  @Test
  fun `middle click closes a session row without selecting it, right click opens its menu`() = show(WorkspaceShowcase.AgentFinishes) {
    events.clear()
    onNodeWithText("checkout-flake").performMouseInput {
      moveTo(center)
      press(MouseButton.Tertiary)
      release(MouseButton.Tertiary)
    }
    waitForIdle()
    assertEquals(listOf<WorkspaceEvent>(WorkspaceEvent.CloseSession("cf")), events)
    expect(WorkspaceEvent.OpenSessionMenu("cf")) { onNodeWithText("checkout-flake").performMouseInput { rightClick(center) } }
    assertEquals(listOf<WorkspaceEvent>(WorkspaceEvent.OpenSessionMenu("cf")), events)
  }

  @Test
  fun `tiles close on middle click and open their menu on right click`() = show(WorkspaceShowcase.CommentOnDiff) {
    val tile = onNodeWithContentDescription("checkout-flake")
    expect(WorkspaceEvent.CloseSession("cf")) {
      tile.performMouseInput {
        moveTo(center)
        press(MouseButton.Tertiary)
        release(MouseButton.Tertiary)
      }
    }
    expect(WorkspaceEvent.OpenSessionMenu("cf")) { tile.performMouseInput { rightClick(center) } }
  }

  @Test
  fun `the session menu names the session and raises its actions`() {
    val clipboard = FakeClipboard()
    show(WorkspaceShowcase.SessionMenu, edit = { it }, clipboard = clipboard) {
      onNodeWithText("~/code/storefront · feat/rate-limit").assertExists()
      onNodeWithText("Same folder · feat/rate-limit").assertExists()
      onNodeWithText("New branch from feat/rate-limit").assertExists()
      expect(WorkspaceEvent.CreateSubSessionFrom("d3", SubSessionLocation.SameFolder)) { onNodeWithText("New sub-session in current worktree").performClick() }
      expect(WorkspaceEvent.StartNamingWorktree("d3")) { onNodeWithText("New sub-session in new worktree").performClick() }
      expect(WorkspaceEvent.StartRenamingSession("d3")) { onNodeWithText("Rename").performClick() }
      expect(WorkspaceEvent.CopiedSessionPath(ShowcaseFixtures.Repo)) { onNodeWithText("Copy folder path").performClick() }
      assertEquals(ShowcaseFixtures.Repo, clipboard.text)
      expect(WorkspaceEvent.CloseSession("d3")) { onNodeWithText("Close session").performClick() }
    }
  }

  @Test
  fun `the session menu closes the session, like a middle click, even with no sub-session to offer`() {
    val menu = SessionMenuModel(
      sessionId = "cf",
      title = "checkout-flake",
      location = "~/scratch",
      folderPath = "/tmp",
      canStartSubSession = false,
      branch = null,
      worktreeAvailable = false,
    )
    show(WorkspaceShowcase.SessionTree, edit = { it.copy(sessions = it.sessions.copy(menu = menu)) }) {
      onNodeWithText("New sub-session in current worktree").assertDoesNotExist()
      events.clear()
      onNodeWithText("Close session").performClick()
      waitForIdle()
      assertEquals(listOf<WorkspaceEvent>(WorkspaceEvent.CloseSession("cf")), events)
    }
  }

  @Test
  fun `the close dialog names the session and its worktree, warns of lost changes and raises each answer`() = show(WorkspaceShowcase.ClosingWorktree) {
    val inDialog = hasAnyAncestor(isDialog())
    // It takes the keys from whatever had them behind it, like the terminal.
    onNode(isDialog()).assertIsFocused()
    onNode(hasText("Close sub-2?") and inDialog).assertExists()
    onNode(hasText("No other session uses this worktree. Delete it too?") and inDialog).assertExists()
    onNode(hasText("~/code/storefront.worktrees/sub-2") and inDialog).assertExists()
    onNode(hasText("feat/rate-limit-sub-2") and inDialog).assertExists()
    onNode(hasText("Has uncommitted changes that will be lost") and inDialog).assertExists()
    onNode(hasText("Only the folder is deleted. The branch feat/rate-limit-sub-2 stays.") and inDialog).assertExists()

    expect(WorkspaceEvent.ConfirmCloseSession(deleteWorktrees = true)) { onNodeWithText("Close and delete worktree").performClick() }
    expect(WorkspaceEvent.ConfirmCloseSession(deleteWorktrees = false)) { onNodeWithText("Close, keep worktree").performClick() }
    expect(WorkspaceEvent.CancelCloseSession) { onNodeWithText("Cancel").performClick() }
    expect(WorkspaceEvent.CancelCloseSession) { onNode(isDialog()).performKeyInput { pressKey(Key.Escape) } }
  }

  @Test
  fun `closing a session with sub-sessions in clean worktrees asks about them all`() {
    val dialog = CloseSessionDialogModel(
      sessionName = "api-ratelimit",
      subSessionCount = 2,
      worktrees = listOf(
        ClosingWorktreeModel(path = "~/code/storefront.worktrees/sub-2", branch = "feat/rate-limit-sub-2", hasChanges = false),
        ClosingWorktreeModel(path = "~/code/storefront/.claude/worktrees/agent-7", branch = null, hasChanges = false),
      ),
    )
    show(WorkspaceShowcase.AfterCreatingBoth, edit = { it.copy(closeSession = dialog) }) {
      onNodeWithText("Close api-ratelimit?").assertExists()
      onNodeWithText("Its sub-sessions close with it. No other session uses these worktrees. Delete them too?").assertExists()
      onNodeWithText("~/code/storefront/.claude/worktrees/agent-7").assertExists()
      onNodeWithText("detached HEAD").assertExists()
      onNodeWithText("Has uncommitted changes that will be lost").assertDoesNotExist()
      onNodeWithText("Only the folders are deleted. Their branches stay.").assertExists()
      expect(WorkspaceEvent.ConfirmCloseSession(deleteWorktrees = true)) { onNodeWithText("Close and delete worktrees").performClick() }
      expect(WorkspaceEvent.ConfirmCloseSession(deleteWorktrees = false)) { onNodeWithText("Close, keep worktrees").performClick() }
    }
  }

  @Test
  fun `outside a git checkout a sub-session can only share the folder`() {
    val menu = SessionMenuModel(
      sessionId = "ar",
      title = "api-ratelimit",
      location = "~/scratch",
      folderPath = "/tmp",
      canStartSubSession = true,
      branch = null,
      worktreeAvailable = false,
    )
    show(WorkspaceShowcase.SessionTree, edit = { it.copy(sessions = it.sessions.copy(menu = menu)) }) {
      onNodeWithText("Same folder").assertExists()
      onNodeWithText("Needs a git checkout").assertExists()
      events.clear()
      onNodeWithText("New sub-session in new worktree").performClick()
      waitForIdle()
      assertEquals(emptyList(), events)
    }
  }

  @Test
  fun `the rename field saves on Enter or Rename and closes on Escape or Cancel`() = show(WorkspaceShowcase.RenamingSession, edit = { it }) {
    onNodeWithText("Copy folder path").assertDoesNotExist()
    val field = onNode(hasSetTextAction())
    field.performTextReplacement("lua-atomic-refill")
    expect(WorkspaceEvent.RenameSession("d3", "lua-atomic-refill")) { field.performKeyInput { pressKey(Key.Enter) } }
    expect(WorkspaceEvent.CancelRenamingSession) { field.performKeyInput { pressKey(Key.Escape) } }
    expect(WorkspaceEvent.RenameSession("d3", "lua-atomic-refill")) { onNodeWithText("Rename").performClick() }
    expect(WorkspaceEvent.CancelRenamingSession) { onNodeWithText("Cancel").performClick() }

    // The buttons took the focus; the keys below go to the field again.
    field.performClick()
    field.performTextReplacement("  ")
    onNodeWithText("Rename").assertIsNotEnabled()
    events.clear()
    field.performKeyInput { pressKey(Key.Enter) }
    waitForIdle()
    assertEquals(emptyList(), events, "a blank name can't be saved")
  }

  @Test
  fun `a tile's menu renames in place too`() {
    val menu = WorkspaceShowcase.RenamingSession.model().sessions.menu!!.copy(sessionId = "cf", title = "checkout-flake")
    show(WorkspaceShowcase.CommentOnDiff, edit = { it.copy(sessions = it.sessions.copy(menu = menu)) }) {
      val field = onNode(hasSetTextAction() and hasText("Session name"))
      field.performTextReplacement("flaky checkout")
      expect(WorkspaceEvent.RenameSession("cf", "flaky checkout")) { field.performKeyInput { pressKey(Key.Enter) } }
    }
  }

  /** Keeps what is copied, as plain text. */
  @OptIn(ExperimentalComposeUiApi::class)
  private class FakeClipboard : Clipboard {
    var text: String? = null

    override suspend fun getClipEntry(): ClipEntry? = null

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
      text = (clipEntry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as? String
    }

    override val nativeClipboard: java.awt.datatransfer.Clipboard = java.awt.datatransfer.Clipboard("test")
  }
}

/** The key Compose copies a selection with: ⌘ on macOS, Ctrl elsewhere. */
private val CopyKey = if (System.getProperty("os.name").orEmpty().startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft

/**
 * Selects from the start of the log line showing [from] to the end of the one showing [to], by
 * dragging from that end back to the row's left edge, which selects from its first character.
 */
@OptIn(ExperimentalTestApi::class)
private fun DesktopComposeUiTest.selectLogLines(from: String, to: String) {
  val start = onNodeWithText(from, substring = true).fetchSemanticsNode().boundsInRoot
  val end = onNodeWithText(to, substring = true).fetchSemanticsNode().boundsInRoot
  onRoot().performMouseInput {
    moveTo(Offset(end.right - 12f, end.bottom - 4f))
    press()
    moveTo(Offset(end.right - 40f, end.center.y))
    moveTo(Offset(start.left + 1f, start.top + 4f))
    release()
  }
  waitForIdle()
}

/** [count] Info lines, `line 1.` to `line <count>.`, oldest first. */
private fun manyLogLines(count: Int): List<LogLineModel> = (1..count).map { LogLineModel(key = it.toLong(), time = "10:20:00.000", level = LogLevelModel.Info, tag = "Tag", message = "line $it.") }

/** The open device's logs showing [lines]. */
private fun WorkspaceModel.withLogLines(lines: List<LogLineModel>): WorkspaceModel {
  val panel = devices ?: return this
  val device = panel.content as? DevicesContentModel.Device ?: return this
  return copy(devices = panel.copy(content = device.copy(logs = device.logs.copy(lines = lines, emptyText = null))))
}

/** The serials of the showcase's devices, as the panel raises them. */
private object ShowcaseDevicesIds {
  const val Pixel = "38021FDH2000AB"
}
