package dev.loupe.workspace.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.loupe.domain.review.CommentStatus
import dev.loupe.domain.review.DiffState
import dev.loupe.domain.review.Review
import dev.loupe.domain.session.SessionId
import dev.loupe.domain.session.SessionStatus
import dev.loupe.domain.session.SubSessionLocation
import dev.loupe.domain.settings.ThemePreference
import dev.loupe.platform.designsystem.LoupeTheme
import dev.loupe.platform.process.HomeDirectory
import dev.loupe.review.fake.FakeReviewService
import dev.loupe.sessions.fake.FakeSessionService
import dev.loupe.settings.fake.FakeSettingsService
import dev.loupe.workspace.impl.WorkspacePresenter
import dev.loupe.workspace.impl.showcase.ShowcaseFixtures
import dev.loupe.workspace.ui.WorkspaceContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real presenter driving the real UI, with fakes behind the services: what a person does,
 * click by click, and what Loupe asks of its services in return.
 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceFlowUiTest {
  private val sessions = FakeSessionService().apply { homeFolder = ShowcaseFixtures.Home }
  private val reviews = FakeReviewService()
  private val settings = FakeSettingsService()
  private val presenter = WorkspacePresenter(
    sessionService = sessions,
    reviewService = reviews,
    settingsService = settings,
    homeDirectory = object : HomeDirectory {
      override val path: String = ShowcaseFixtures.Home
    },
  )
  private val ar = SessionId("ar")

  private fun withRepository() {
    sessions.setSessions(listOf(ShowcaseFixtures.session("ar", "api-ratelimit")))
    reviews.setReview(ar, Review(DiffState.Ready(ShowcaseFixtures.snapshot), emptyList()))
  }

  private fun flow(block: DesktopComposeUiTest.() -> Unit) = runDesktopComposeUiTest(width = 1440, height = 900) {
    setContent {
      LoupeTheme {
        WorkspaceContent(
          model = presenter.present(),
          terminal = { id, modifier -> Box(modifier.testTag("terminal:$id")) },
        )
      }
    }
    waitForIdle()
    block()
  }

  private fun DesktopComposeUiTest.waitFor(matcher: androidx.compose.ui.test.SemanticsMatcher) = waitUntil(timeoutMillis = 5_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

  @Test
  fun `the empty workspace opens a terminal at home`() = flow {
    onNodeWithText("No sessions yet").assertExists()
    onNodeWithText("New terminal").performClick()
    waitFor(hasTestTag("terminal:terminal-1"))
    assertEquals(listOf<String?>(null), sessions.opened)
    onNodeWithText("~/").assertDoesNotExist()
    onNodeWithText("zsh · ~").assertExists()
    // A plain terminal shows no status; one appears only when an agent in it reports.
    listOf("idle", "working", "needs input").forEach { onNodeWithText(it).assertDoesNotExist() }
    sessions.setStatus(SessionId("terminal-1"), SessionStatus.Working)
    waitFor(hasText("working"))
  }

  @Test
  fun `a line comment goes from the diff into the terminal`() {
    withRepository()
    flow {
      onNodeWithText("Diff vs main").performClick()
      waitFor(hasText("rateLimit.ts", substring = true))
      assertTrue(reviews.refreshed.contains(ar))

      onNodeWithText("Date.now", substring = true).performMouseInput { moveTo(center) }
      waitFor(androidx.compose.ui.test.hasContentDescription("Comment on rateLimit.ts:18"))
      onNodeWithContentDescription("Comment on rateLimit.ts:18").performMouseInput {
        moveTo(center)
        press()
        release()
      }
      waitFor(hasText("Comment on src/middleware/rateLimit.ts:18"))
      onNode(hasSetTextAction()).performTextInput("Read the clock once per request.")
      onNodeWithText("Add comment").performClick()

      waitFor(hasText("Read the clock once per request."))
      onNodeWithText("1 unsent comment").assertExists()
      val comment = reviews.reviewOf(ar).comments.single()
      assertEquals(18 to 18, comment.start.line to comment.end.line)

      onNodeWithText("Paste into terminal").performClick()
      waitFor(hasTestTag("terminal:ar"))
      assertEquals(listOf(ar), reviews.sentToSession)
      assertTrue(reviews.reviewOf(ar).comments.all { it.status is CommentStatus.Sent })
    }
  }

  @Test
  fun `settings change the theme and keep awake`() {
    withRepository()
    flow {
      onNodeWithContentDescription("Settings").performClick()
      waitFor(hasText("Appearance"))
      onNodeWithText("Light").performClick()
      onNode(isToggleable()).performClick()
      waitUntil(timeoutMillis = 5_000) { settings.settings.value.keepAwake }
      assertEquals(ThemePreference.Light, settings.settings.value.theme)
    }
  }

  @Test
  fun `a sub-session opens in a new worktree and gets selected`() {
    withRepository()
    flow {
      onNodeWithContentDescription("New sub-session").performClick()
      waitFor(hasText("New worktree"))
      onNodeWithText("New worktree").performClick()
      waitFor(hasText("feat/rate-limit-sub-1"))
      onNodeWithText("Create sub-session").performClick()
      waitFor(hasText("sub-session · worktree"))
      assertEquals(SubSessionLocation.NewWorktree, sessions.started.single().location)
      assertEquals(SubSessionLocation.NewWorktree, settings.settings.value.subSessionLocation)
    }
  }

  @Test
  fun `command W closes the selected session`() {
    withRepository()
    flow {
      onAllNodes(isRoot())[0].performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.W) } }
      waitFor(hasText("No sessions yet"))
      assertEquals(listOf(ar), sessions.closed)
    }
  }

  @Test
  fun `right click renames a session in place, middle click closes one that isn't selected`() {
    sessions.setSessions(listOf(ShowcaseFixtures.session("ar", "api-ratelimit"), ShowcaseFixtures.session("cf", "checkout-flake")))
    flow {
      onNodeWithText("checkout-flake").performMouseInput { rightClick(center) }
      waitFor(hasText("Rename"))
      onNodeWithText("Rename").performClick()
      waitFor(hasSetTextAction())
      // The whole name starts selected, so typing replaces it.
      onNode(hasSetTextAction()).performTextInput("flaky checkout")
      onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
      waitFor(hasText("flaky checkout"))
      onNode(hasSetTextAction()).assertDoesNotExist()
      assertEquals(listOf<Pair<SessionId, String?>>(SessionId("cf") to "flaky checkout"), sessions.renamed)

      onNodeWithText("flaky checkout").performMouseInput {
        moveTo(center)
        press(MouseButton.Tertiary)
        release(MouseButton.Tertiary)
      }
      waitFor(hasText("api-ratelimit"))
      waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("flaky checkout")).fetchSemanticsNodes().isEmpty() }
      assertEquals(listOf(SessionId("cf")), sessions.closed)
    }
  }
}
