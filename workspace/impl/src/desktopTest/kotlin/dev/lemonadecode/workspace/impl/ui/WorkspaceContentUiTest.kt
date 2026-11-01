package dev.lemonadecode.workspace.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.platform.localization.asLemonadeCodeString
import dev.lemonadecode.workspace.impl.showcase.ShowcaseFixtures
import dev.lemonadecode.workspace.impl.showcase.WorkspaceShowcase
import dev.lemonadecode.workspace.impl.showcase.WorkspaceShowcaseContent
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.update_failed_download
import dev.lemonadecode.workspace.ui.TerminalTranscript
import dev.lemonadecode.workspace.ui.WorkspaceContent
import dev.lemonadecode.workspace.uimodel.SessionMenuModel
import dev.lemonadecode.workspace.uimodel.SessionsPanelModel
import dev.lemonadecode.workspace.uimodel.SubSessionLocation
import dev.lemonadecode.workspace.uimodel.ThemeOption
import dev.lemonadecode.workspace.uimodel.UpdateStatusModel
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

  private fun show(showcase: WorkspaceShowcase, block: DesktopComposeUiTest.() -> Unit) = runDesktopComposeUiTest(width = showcase.widthDp, height = 900) {
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
  ) = runDesktopComposeUiTest(width = showcase.widthDp, height = 900) {
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
  fun `the branch picker opens and picks a branch to compare with`() {
    show(WorkspaceShowcase.CommentOnDiff) {
      expect(WorkspaceEvent.ToggleDiffBasePicker) { onNodeWithText("main").performClick() }
    }
    show(WorkspaceShowcase.CommentOnDiff, edit = { model -> model.withBasePickerOpen() }) {
      onNodeWithText("COMPARE WITH").assertExists()
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
  fun `settings change the theme and keep awake`() = show(WorkspaceShowcase.SettingsOpen) {
    onNodeWithText("Appearance").assertExists()
    expect(WorkspaceEvent.SetTheme(ThemeOption.Light)) { onNodeWithText("Light").performClick() }
    expect(WorkspaceEvent.SetKeepAwake(false)) { onNode(isToggleable()).performClick() }
  }

  @Test
  fun `the Update button opens its panel`() = show(
    WorkspaceShowcase.UpdateAvailable,
    edit = { model -> model.copy(topBar = model.topBar.copy(update = model.topBar.update?.copy(open = false, status = UpdateStatusModel.Ready))) },
  ) {
    expect(WorkspaceEvent.ToggleUpdate) { onNodeWithText("Update").performClick() }
  }

  @Test
  fun `the update panel installs the new version`() = show(
    WorkspaceShowcase.UpdateAvailable,
    edit = { model -> model.copy(topBar = model.topBar.copy(update = model.topBar.update?.copy(status = UpdateStatusModel.Ready))) },
  ) {
    onNodeWithText("Lemonade Code 1.3.0 is ready to install. You have 1.2.0.").assertExists()
    expect(WorkspaceEvent.InstallUpdate) { onNodeWithText("Update and restart").performClick() }
  }

  @Test
  fun `while the update downloads, it can't be started twice`() = show(WorkspaceShowcase.UpdateAvailable) {
    onNodeWithText("Downloading… 42%").assertExists()
    onNodeWithText("Update and restart").assertIsNotEnabled()
  }

  @Test
  fun `a failed update says why and offers to try again`() = show(
    WorkspaceShowcase.UpdateAvailable,
    edit = { model ->
      val failed = UpdateStatusModel.Failed(Res.string.update_failed_download.asLemonadeCodeString())
      model.copy(topBar = model.topBar.copy(update = model.topBar.update?.copy(status = failed)))
    },
  ) {
    onNodeWithText("Couldn’t download the update. Check your connection and try again.").assertExists()
    expect(WorkspaceEvent.InstallUpdate) { onNodeWithText("Try again").performClick() }
  }

  @Test
  fun `the sub-session panel switches location, creates and cancels`() = show(WorkspaceShowcase.ChoosingWorktree) {
    onNodeWithText("~/code/storefront.worktrees/sub-1").assertExists()
    expect(WorkspaceEvent.SetSubSessionLocation(SubSessionLocation.SameFolder)) { onNodeWithText("Same folder").performClick() }
    expect(WorkspaceEvent.CreateSubSession) { onNodeWithText("Create sub-session").performClick() }
    expect(WorkspaceEvent.DismissNewSubSessionMenu) { onNodeWithText("Cancel").performClick() }
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
      onNodeWithText("New branch feat/rate-limit-sub-1").assertExists()
      expect(WorkspaceEvent.CreateSubSessionFrom("d3", SubSessionLocation.SameFolder)) { onNodeWithText("New sub-session in current worktree").performClick() }
      expect(WorkspaceEvent.CreateSubSessionFrom("d3", SubSessionLocation.NewWorktree)) { onNodeWithText("New sub-session in new worktree").performClick() }
      expect(WorkspaceEvent.StartRenamingSession("d3")) { onNodeWithText("Rename").performClick() }
      expect(WorkspaceEvent.CopiedSessionPath(ShowcaseFixtures.Repo)) { onNodeWithText("Copy folder path").performClick() }
      assertEquals(ShowcaseFixtures.Repo, clipboard.text)
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
      worktreeBranch = null,
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
