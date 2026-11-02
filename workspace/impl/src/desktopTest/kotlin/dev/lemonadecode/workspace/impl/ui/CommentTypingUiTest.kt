package dev.lemonadecode.workspace.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import dev.lemonadecode.domain.review.DiffState
import dev.lemonadecode.domain.review.Review
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.mobile.fake.FakeMobileService
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.platform.process.HomeDirectory
import dev.lemonadecode.review.fake.FakeReviewService
import dev.lemonadecode.sessions.fake.FakeSessionService
import dev.lemonadecode.settings.fake.FakeSettingsService
import dev.lemonadecode.updates.fake.FakeUpdateService
import dev.lemonadecode.workspace.impl.WorkspacePresenter
import dev.lemonadecode.workspace.impl.showcase.ShowcaseFixtures
import dev.lemonadecode.workspace.ui.WorkspaceContent
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
    updateService = FakeUpdateService(),
    mobileService = FakeMobileService(),
    clock = { 0L },
  )
  private val ar = SessionId("ar")

  private fun DesktopComposeUiTest.waitFor(matcher: SemanticsMatcher) = waitUntil(timeoutMillis = 5_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

  @Test
  fun `each typed character lands after the one before`() {
    sessions.setSessions(listOf(ShowcaseFixtures.session("ar", "api-ratelimit")))
    reviews.setReview(ar, Review(DiffState.Ready(ShowcaseFixtures.snapshot), emptyList()))
    runDesktopComposeUiTest(width = 1440, height = 900) {
      setContent {
        LemonadeCodeTheme {
          // In the app the Model reaches the screen a frame or more after the event that changed
          // it (PresenterHost hands it over through a StateFlow), so it arrives a frame late here too.
          val live = presenter.present()
          var shown by remember { mutableStateOf(live) }
          LaunchedEffect(live) {
            withFrameNanos { }
            shown = live
          }
          WorkspaceContent(
            model = shown,
            terminal = { id, modifier -> Box(modifier.testTag("terminal:$id")) },
          )
        }
      }
      onNodeWithText("Diff").performClick()
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
