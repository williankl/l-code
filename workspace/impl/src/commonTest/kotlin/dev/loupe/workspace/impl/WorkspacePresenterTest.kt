package dev.loupe.workspace.impl

import dev.loupe.domain.review.DiffState
import dev.loupe.domain.review.Review
import dev.loupe.domain.session.SessionId
import dev.loupe.domain.settings.ThemePreference
import dev.loupe.platform.localization.asLoupeString
import dev.loupe.platform.presenter.testing.awaitItemWhere
import dev.loupe.platform.presenter.testing.test
import dev.loupe.platform.presenter.testing.yieldUntil
import dev.loupe.platform.process.HomeDirectory
import dev.loupe.review.fake.FakeReviewService
import dev.loupe.sessions.fake.FakeSessionService
import dev.loupe.settings.fake.FakeSettingsService
import dev.loupe.workspace.impl.showcase.ShowcaseFixtures
import dev.loupe.workspace.strings.Res
import dev.loupe.workspace.strings.session_path_copied
import dev.loupe.workspace.uimodel.DiffRowModel
import dev.loupe.workspace.uimodel.SessionMenuModel
import dev.loupe.workspace.uimodel.SessionRenameModel
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
          worktreeBranch = "${ShowcaseFixtures.Branch}-sub-1",
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
  fun `a sub-session from the menu starts under that session and gets selected`() = runTest {
    withFamily()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.OpenSessionMenu("d2"))
      awaitItemWhere { it.sessions.menu != null }
        .eventSink(WorkspaceEvent.CreateSubSessionFrom("d2", dev.loupe.workspace.uimodel.SubSessionLocation.NewWorktree))
      val created = awaitItemWhere { it.header?.name == "sub-1" }
      assertEquals(null, created.sessions.menu)
      val call = sessions.started.single()
      assertEquals(SessionId("d2") to dev.loupe.domain.session.SubSessionLocation.NewWorktree, call.parentId to call.location)
      assertEquals(dev.loupe.domain.session.SubSessionLocation.NewWorktree, settings.settings.value.subSessionLocation)
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

  @Test
  fun `copying a folder path closes the menu and confirms with a toast for a moment`() = runTest {
    withFamily()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.OpenSessionMenu("cf"))
      awaitItemWhere { it.sessions.menu != null }.eventSink(WorkspaceEvent.CopiedSessionPath(ShowcaseFixtures.Repo))
      val copied = awaitItemWhere { it.toast != null }
      assertEquals(null, copied.sessions.menu)
      assertEquals(Res.string.session_path_copied.asLoupeString(ShowcaseFixtures.Repo), copied.toast)
      awaitItemWhere { it.toast == null }
    }
  }

  private fun dev.loupe.workspace.uimodel.WorkspaceModel.lines(path: String): List<DiffRowModel.Line> = (content as? WorkspaceContentModel.Diff)?.review?.files?.first { it.path == path }?.rows?.filterIsInstance<DiffRowModel.Line>().orEmpty()
}
