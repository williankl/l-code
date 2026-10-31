package dev.loupe.workspace.impl

import dev.loupe.domain.review.DiffState
import dev.loupe.domain.review.Review
import dev.loupe.domain.session.SessionId
import dev.loupe.domain.settings.ThemePreference
import dev.loupe.platform.presenter.testing.awaitItemWhere
import dev.loupe.platform.presenter.testing.test
import dev.loupe.platform.presenter.testing.yieldUntil
import dev.loupe.platform.process.HomeDirectory
import dev.loupe.review.fake.FakeReviewService
import dev.loupe.sessions.fake.FakeSessionService
import dev.loupe.settings.fake.FakeSettingsService
import dev.loupe.workspace.impl.showcase.ShowcaseFixtures
import dev.loupe.workspace.uimodel.DiffRowModel
import dev.loupe.workspace.uimodel.ThemeOption
import dev.loupe.workspace.uimodel.WorkspaceContentModel
import dev.loupe.workspace.uimodel.WorkspaceEvent
import dev.loupe.workspace.uimodel.WorkspaceView
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspacePresenterTest {
  private val ar = SessionId("ar")
  private val sessions = FakeSessionService()
  private val reviews = FakeReviewService()
  private val settings = FakeSettingsService()
  private val presenter = WorkspacePresenter(
    sessions,
    reviews,
    settings,
    object : HomeDirectory {
      override val path: String = ShowcaseFixtures.Home
    },
  )

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
  fun `a new sub-session opens on the last location and gets selected`() = runTest {
    withSession()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleNewSubSessionMenu)
      val menu = awaitItemWhere { it.newSubSession.open }
      menu.eventSink(WorkspaceEvent.SetSubSessionLocation(dev.loupe.workspace.uimodel.SubSessionLocation.NewWorktree))
      awaitItemWhere { it.newSubSession.location == dev.loupe.workspace.uimodel.SubSessionLocation.NewWorktree }
        .eventSink(WorkspaceEvent.CreateSubSession)
      val created = awaitItemWhere { it.header?.name == "sub-1" }
      assertEquals(false, created.newSubSession.open)
      assertEquals(dev.loupe.domain.session.SubSessionLocation.NewWorktree, settings.settings.value.subSessionLocation)
    }
  }

  private fun dev.loupe.workspace.uimodel.WorkspaceModel.lines(path: String): List<DiffRowModel.Line> = (content as? WorkspaceContentModel.Diff)?.review?.files?.first { it.path == path }?.rows?.filterIsInstance<DiffRowModel.Line>().orEmpty()
}
