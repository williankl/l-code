package dev.loupe.workspace.impl.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.loupe.platform.designsystem.LoupeTheme
import dev.loupe.workspace.impl.showcase.ShowcaseFixtures
import dev.loupe.workspace.impl.showcase.WorkspaceShowcase
import dev.loupe.workspace.impl.showcase.WorkspaceShowcaseContent
import dev.loupe.workspace.uimodel.SubSessionLocation
import dev.loupe.workspace.uimodel.ThemeOption
import dev.loupe.workspace.uimodel.WorkspaceEvent
import dev.loupe.workspace.uimodel.WorkspaceView
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** The workspace UI on every design screen: each control raises the event the presenter expects. */
@OptIn(ExperimentalTestApi::class)
class WorkspaceContentUiTest {
  private val events = mutableListOf<WorkspaceEvent>()
  private val rateLimit = ShowcaseFixtures.path("rl")
  private val app = ShowcaseFixtures.path("app")

  private fun show(showcase: WorkspaceShowcase, block: DesktopComposeUiTest.() -> Unit) = runDesktopComposeUiTest(width = showcase.widthDp, height = 900) {
    setContent {
      LoupeTheme { WorkspaceShowcaseContent(showcase, onEvent = { events += it }) }
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
    expect(WorkspaceEvent.SelectView(WorkspaceView.Diff)) { onNodeWithText("Diff vs main").performClick() }
    expect(WorkspaceEvent.ToggleSettings) { onNodeWithContentDescription("Settings").performClick() }
    expect(WorkspaceEvent.ToggleSessionsPanel(narrowLayout = false)) { onNodeWithContentDescription("Collapse sessions").performClick() }
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
}
