package dev.lemonadecode.workspace.impl.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.lemonadecode.domain.review.CodeToken
import dev.lemonadecode.domain.review.CodeTokenKind
import dev.lemonadecode.domain.review.DiffLine
import dev.lemonadecode.domain.review.DiffLineKind
import dev.lemonadecode.domain.review.DiffSnapshot
import dev.lemonadecode.domain.review.DiffState
import dev.lemonadecode.domain.review.FileChange
import dev.lemonadecode.domain.review.FileDiff
import dev.lemonadecode.domain.review.Review
import dev.lemonadecode.domain.settings.AppSettings
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.workspace.impl.Composer
import dev.lemonadecode.workspace.impl.LineRange
import dev.lemonadecode.workspace.impl.WorkspaceInput
import dev.lemonadecode.workspace.impl.WorkspaceModelMapper
import dev.lemonadecode.workspace.impl.WorkspaceViewState
import dev.lemonadecode.workspace.impl.showcase.ShowcaseFixtures
import dev.lemonadecode.workspace.ui.TerminalTranscript
import dev.lemonadecode.workspace.ui.WorkspaceContent
import dev.lemonadecode.workspace.uimodel.TerminalTranscriptModel
import dev.lemonadecode.workspace.uimodel.WorkspaceView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A diff far longer than the screen: only what is on screen is drawn, and it still reads as one. */
@OptIn(ExperimentalTestApi::class)
class LongDiffUiTest {
  private val path = "src/Long.kt"
  private val lineCount = 5_000

  /** Every line of [path] shown in full, the 3rd and 4th long enough to scroll sideways. */
  private fun show(
    composerAt: Int? = null,
    block: DesktopComposeUiTest.() -> Unit,
  ) = runDesktopComposeUiTest(width = 1440, height = 900) {
    val lines = (1..lineCount).map { number ->
      val text = if (number in 3..4) "val wide$number = \"${"wide ".repeat(80)}\"" else "val line$number = $number"
      DiffLine(DiffLineKind.Added, null, number, text, listOf(CodeToken(text, CodeTokenKind.Plain)))
    }
    val file = FileDiff(path, previousPath = null, change = FileChange.Added, language = "Kotlin", lines = lines, isBinary = false)
    val session = ShowcaseFixtures.session("ar", "api-ratelimit")
    val input = WorkspaceInput(
      sessions = listOf(session),
      review = Review(DiffState.Ready(DiffSnapshot(baseRef = "main", files = listOf(file), hasUncommittedChanges = true)), emptyList()),
      settings = AppSettings(sidebarExpanded = false),
      view = WorkspaceViewState(
        selectedId = session.id,
        view = WorkspaceView.Diff,
        fileExpansion = mapOf(path to true),
        composer = composerAt?.let { Composer(LineRange(path, it, it), "") },
      ),
      subSessionPlan = null,
      pendingMessage = null,
      homeDirectory = ShowcaseFixtures.Home,
    )
    setContent {
      LemonadeCodeTheme {
        WorkspaceContent(
          model = WorkspaceModelMapper.map(input) {},
          terminal = { _, modifier -> TerminalTranscript(TerminalTranscriptModel(emptyList()), modifier) },
        )
      }
    }
    waitForIdle()
    block()
  }

  private fun DesktopComposeUiTest.composedLines(): Int = onAllNodes(hasText("val line", substring = true)).fetchSemanticsNodes().size

  @Test
  fun `only the lines on screen are composed, and the last one can be scrolled to`() = show {
    assertTrue(composedLines() in 1..100, "composed ${composedLines()} of $lineCount lines")
    onNode(hasScrollToIndexAction()).performScrollToIndex(lineCount)
    onNodeWithText("val line$lineCount = $lineCount").assertIsDisplayed()
    assertTrue(composedLines() in 1..100, "composed ${composedLines()} of $lineCount lines")
  }

  @Test
  fun `the file's header stays pinned at the top while the diff scrolls`() = show {
    val header = onNodeWithContentDescription("Collapse file")
    val top = header.fetchSemanticsNode().boundsInRoot.top
    mainClock.autoAdvance = false
    onNode(hasScrollToIndexAction()).performScrollToIndex(40)
    mainClock.advanceTimeByFrame()
    onNodeWithText("val line40 = 40").assertIsDisplayed()
    assertEquals(top, header.fetchSemanticsNode().boundsInRoot.top, "the header moved as the diff scrolled")
    mainClock.advanceTimeBy(500)
    assertEquals(top, header.fetchSemanticsNode().boundsInRoot.top, "the header moved after the diff scrolled")
  }

  @Test
  fun `a file's lines scroll sideways together`() = show {
    val third = onNodeWithText("val wide3", substring = true)
    val fourth = onNodeWithText("val wide4", substring = true)
    val before = fourth.fetchSemanticsNode().boundsInRoot.left
    third.performMouseInput {
      // The text runs well past the window, so not at its center.
      moveTo(Offset(10f, centerY))
      scroll(200f, ScrollWheel.Horizontal)
    }
    waitForIdle()
    val moved = before - fourth.fetchSemanticsNode().boundsInRoot.left
    assertTrue(moved > 0f, "the 4th line didn't move")
    assertEquals(before - moved, third.fetchSemanticsNode().boundsInRoot.left)
  }

  @Test
  fun `the composer keeps its text and focus while scrolled away`() = show(composerAt = 5) {
    val field = onNode(hasSetTextAction())
    field.performClick()
    field.performTextInput("Halfway through a thought")
    onNode(hasScrollToIndexAction()).performScrollToIndex(lineCount)
    waitForIdle()
    field.assertIsFocused()
    onNode(hasScrollToIndexAction()).performScrollToIndex(0)
    waitForIdle()
    onNode(hasSetTextAction() and hasText("Halfway through a thought")).assertIsDisplayed().assertIsFocused()
  }

  @Test
  fun `a composer open far down is brought into view as the diff appears`() = show(composerAt = 3_000) {
    onNode(hasSetTextAction()).assertIsDisplayed()
    onNodeWithText("val line3001 = 3001").assertIsDisplayed()
  }
}
