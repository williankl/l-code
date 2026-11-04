package dev.lemonadecode.workspace.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.lemonadecode.domain.mobile.LogPackages
import dev.lemonadecode.domain.review.CommentStatus
import dev.lemonadecode.domain.review.DiffState
import dev.lemonadecode.domain.review.Review
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SessionStatus
import dev.lemonadecode.domain.session.SubSessionLocation
import dev.lemonadecode.domain.settings.ThemePreference
import dev.lemonadecode.mobile.fake.FakeMobileService
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.platform.process.HomeDirectory
import dev.lemonadecode.platform.sound.Chime
import dev.lemonadecode.platform.sound.testing.FakeSoundPlayer
import dev.lemonadecode.review.fake.FakeReviewService
import dev.lemonadecode.sessions.fake.FakeSessionService
import dev.lemonadecode.settings.fake.FakeSettingsService
import dev.lemonadecode.updates.fake.FakeUpdateService
import dev.lemonadecode.workspace.impl.WorkspacePresenter
import dev.lemonadecode.workspace.impl.showcase.ShowcaseDevices
import dev.lemonadecode.workspace.impl.showcase.ShowcaseFixtures
import dev.lemonadecode.workspace.ui.WorkspaceContent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real presenter driving the real UI, with fakes behind the services: what a person does,
 * click by click, and what Lemonade Code asks of its services in return.
 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceFlowUiTest {
  private val sessions = FakeSessionService().apply { homeFolder = ShowcaseFixtures.Home }
  private val reviews = FakeReviewService()
  private val settings = FakeSettingsService()
  private val updates = FakeUpdateService()
  private val mobile = FakeMobileService()
  private val sounds = FakeSoundPlayer()
  private val presenter = WorkspacePresenter(
    sessionService = sessions,
    reviewService = reviews,
    settingsService = settings,
    homeDirectory = object : HomeDirectory {
      override val path: String = ShowcaseFixtures.Home
    },
    updateService = updates,
    mobileService = mobile,
    soundPlayer = sounds,
    clock = { 0L },
  )
  private val ar = SessionId("ar")

  private fun withRepository() {
    sessions.setSessions(listOf(ShowcaseFixtures.session("ar", "api-ratelimit")))
    reviews.setReview(ar, Review(DiffState.Ready(ShowcaseFixtures.snapshot), emptyList()))
  }

  private fun flow(block: DesktopComposeUiTest.() -> Unit) = runDesktopComposeUiTest(width = 1440, height = 900) {
    setContent {
      LemonadeCodeTheme {
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
      onNodeWithText("Diff").performClick()
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
  fun `settings change the theme and keep awake, one page at a time`() {
    withRepository()
    flow {
      onNodeWithContentDescription("Settings").performClick()
      waitFor(hasText("Appearance"))
      onNodeWithText("Appearance").performClick()
      waitFor(hasText("Light"))
      onNodeWithText("Light").performClick()
      onNodeWithContentDescription("Back").performClick()
      waitFor(hasText("Keep awake while sessions work"))
      onNodeWithText("Keep awake while sessions work").performClick()
      waitFor(isToggleable())
      onNode(isToggleable()).performClick()
      waitUntil(timeoutMillis = 5_000) { settings.settings.value.keepAwake }
      assertEquals(ThemePreference.Light, settings.settings.value.theme)
    }
  }

  @Test
  fun `settings play each status sound, and turn them off`() {
    withRepository()
    flow {
      onNodeWithContentDescription("Settings").performClick()
      waitFor(hasText("Sounds") and hasText("On"))
      onNodeWithText("Sounds").performClick()
      waitFor(hasText("Agent needs input"))
      onAllNodesWithText("Play")[1].performClick()
      waitUntil(timeoutMillis = 5_000) { sounds.played == listOf(Chime.DoubleDing) }

      onNode(isToggleable()).performClick()
      waitUntil(timeoutMillis = 5_000) { !settings.settings.value.statusSounds }
      onNodeWithContentDescription("Back").performClick()
      waitFor(hasText("Sounds") and hasText("Off"))
    }
  }

  @Test
  fun `a sub-session opens in a new worktree and gets selected`() {
    withRepository()
    flow {
      onNodeWithContentDescription("New sub-session").performClick()
      waitFor(hasText("New worktree"))
      onNodeWithText("New worktree").performClick()
      waitFor(hasSetTextAction() and hasText("worktree/testing-things"))
      onNode(hasSetTextAction()).performTextReplacement("feat/login")
      waitFor(hasText("~/code/storefront.worktrees/login"))
      onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
      waitFor(hasText("sub-session · worktree"))
      val call = sessions.started.single()
      assertEquals(SubSessionLocation.NewWorktree to "feat/login", call.location to call.branch)
      assertEquals(SubSessionLocation.NewWorktree, settings.settings.value.subSessionLocation)
    }
  }

  @Test
  fun `right click starts a worktree on the suggested branch with just Enter`() {
    sessions.setSessions(listOf(ShowcaseFixtures.session("ar", "api-ratelimit"), ShowcaseFixtures.session("cf", "checkout-flake")))
    sessions.worktreeBranchSuggestion = "worktree/generating-bugs"
    flow {
      onNodeWithText("checkout-flake").performMouseInput { rightClick(center) }
      waitFor(hasText("New sub-session in new worktree"))
      onNodeWithText("New sub-session in new worktree").performClick()
      waitFor(hasSetTextAction() and hasText("worktree/generating-bugs"))
      onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
      waitFor(hasText("sub-session · worktree"))
      val call = sessions.started.single()
      assertEquals(SessionId("cf") to "worktree/generating-bugs", call.parentId to call.branch)
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

  @Test
  fun `a session's right-click menu closes it too, and goes with it`() {
    sessions.setSessions(listOf(ShowcaseFixtures.session("ar", "api-ratelimit"), ShowcaseFixtures.session("cf", "checkout-flake")))
    flow {
      onNodeWithText("checkout-flake").performMouseInput { rightClick(center) }
      waitFor(hasText("Close session"))
      onNodeWithText("Close session").performClick()
      // The row and the menu's header, which names it, both go.
      waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("checkout-flake")).fetchSemanticsNodes().isEmpty() }
      onNodeWithText("Close session").assertDoesNotExist()
      waitFor(hasText("api-ratelimit"))
      assertEquals(listOf(SessionId("cf")), sessions.closed)
    }
  }

  @Test
  fun `a device's log narrows to the apps ticked, and goes back to the app in front`() {
    withRepository()
    runBlocking { settings.update { it.copy(mobilePlugin = true) } }
    mobile.setDevices(ShowcaseDevices.connected)
    mobile.logs.value = mapOf(ShowcaseDevices.pixel.id to ShowcaseDevices.androidLogs)
    mobile.apps.value = mapOf(ShowcaseDevices.pixel.id to ShowcaseDevices.apps)
    flow {
      waitFor(hasContentDescription("Devices"))
      onNodeWithContentDescription("Devices").performClick()
      waitFor(hasText("Pixel 8"))
      onNodeWithText("Pixel 8").performClick()
      waitFor(hasContentDescription("Choose whose logs to show"))
      assertEquals(LogPackages.AppInFront, mobile.logPackages.value[ShowcaseDevices.pixel.id])

      onNodeWithContentDescription("Choose whose logs to show").performClick()
      waitFor(hasText("com.teya.pos"))
      onNodeWithText("com.teya.pos").performClick()
      waitUntil(timeoutMillis = 5_000) { mobile.logPackages.value[ShowcaseDevices.pixel.id] == LogPackages.Only(setOf("com.teya.pos")) }
      onNodeWithText("Show logs from").assertExists()

      // Its button closes it again, rather than closing and opening it at once.
      onNodeWithContentDescription("Choose whose logs to show").performClick()
      waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Show logs from")).fetchSemanticsNodes().isEmpty() }

      onNodeWithContentDescription("Choose whose logs to show").performClick()
      waitFor(hasText("App in front"))
      onNodeWithText("App in front").performClick()
      waitUntil(timeoutMillis = 5_000) { mobile.logPackages.value[ShowcaseDevices.pixel.id] == LogPackages.AppInFront }
      waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Show logs from")).fetchSemanticsNodes().isEmpty() }
    }
  }
}
