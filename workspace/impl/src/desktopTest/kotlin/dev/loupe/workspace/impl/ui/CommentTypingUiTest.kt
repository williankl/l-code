package dev.loupe.workspace.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.loupe.domain.review.DiffState
import dev.loupe.domain.review.Review
import dev.loupe.domain.session.SessionId
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

/** Typing into the comment composer key by key, through the real presenter. */
@OptIn(ExperimentalTestApi::class)
class CommentTypingUiTest {
  private val sessions = FakeSessionService().apply { homeFolder = ShowcaseFixtures.Home }
  private val reviews = FakeReviewService()
  private val presenter = WorkspacePresenter(
    sessionService = sessions,
    reviewService = reviews,
    settingsService = FakeSettingsService(),
    homeDirectory = object : HomeDirectory {
      override val path: String = ShowcaseFixtures.Home
    },
  )
  private val ar = SessionId("ar")

  private fun DesktopComposeUiTest.waitFor(matcher: SemanticsMatcher) = waitUntil(timeoutMillis = 5_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

  @Test
  fun `each typed character lands after the one before`() {
    sessions.setSessions(listOf(ShowcaseFixtures.session("ar", "api-ratelimit")))
    reviews.setReview(ar, Review(DiffState.Ready(ShowcaseFixtures.snapshot), emptyList()))
    runDesktopComposeUiTest(width = 1440, height = 900) {
      setContent {
        LoupeTheme {
          WorkspaceContent(
            model = presenter.present(),
            terminal = { id, modifier -> Box(modifier.testTag("terminal:$id")) },
          )
        }
      }
      onNodeWithText("Diff vs main").performClick()
      waitFor(hasText("rateLimit.ts", substring = true))
      onNodeWithText("Date.now", substring = true).performMouseInput { moveTo(center) }
      waitFor(hasContentDescription("Comment on rateLimit.ts:18"))
      onNodeWithContentDescription("Comment on rateLimit.ts:18").performMouseInput {
        moveTo(center)
        press()
        release()
      }
      waitFor(hasText("Comment on src/middleware/rateLimit.ts:18"))

      "Read".forEach { char ->
        onNode(hasSetTextAction()).performTextInput(char.toString())
        waitForIdle()
      }
      onNodeWithText("Add comment").performClick()
      waitUntil(timeoutMillis = 5_000) { reviews.reviewOf(ar).comments.isNotEmpty() }
      assertEquals("Read", reviews.reviewOf(ar).comments.single().body)
    }
  }
}
