package dev.lemonadecode.workspace.impl

import dev.lemonadecode.domain.review.DiffState
import dev.lemonadecode.domain.review.Review
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.settings.ThemePreference
import dev.lemonadecode.domain.update.AppRelease
import dev.lemonadecode.domain.update.AppVersion
import dev.lemonadecode.domain.update.UpdateFailure
import dev.lemonadecode.domain.update.UpdateProgress
import dev.lemonadecode.domain.update.UpdateState
import dev.lemonadecode.platform.localization.asLemonadeCodeString
import dev.lemonadecode.platform.presenter.testing.awaitItemWhere
import dev.lemonadecode.platform.presenter.testing.test
import dev.lemonadecode.platform.presenter.testing.yieldUntil
import dev.lemonadecode.platform.process.HomeDirectory
import dev.lemonadecode.review.fake.FakeReviewService
import dev.lemonadecode.sessions.fake.FakeSessionService
import dev.lemonadecode.settings.fake.FakeSettingsService
import dev.lemonadecode.updates.fake.FakeUpdateService
import dev.lemonadecode.workspace.impl.showcase.ShowcaseFixtures
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.session_path_copied
import dev.lemonadecode.workspace.strings.update_failed_checksum
import dev.lemonadecode.workspace.strings.worktree_branch_invalid
import dev.lemonadecode.workspace.strings.worktree_branch_taken
import dev.lemonadecode.workspace.uimodel.DiffRowModel
import dev.lemonadecode.workspace.uimodel.SessionMenuModel
import dev.lemonadecode.workspace.uimodel.SessionRenameModel
import dev.lemonadecode.workspace.uimodel.SessionsPanelModel
import dev.lemonadecode.workspace.uimodel.ThemeOption
import dev.lemonadecode.workspace.uimodel.UpdateModel
import dev.lemonadecode.workspace.uimodel.UpdateStatusModel
import dev.lemonadecode.workspace.uimodel.WorkspaceContentModel
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import dev.lemonadecode.workspace.uimodel.WorkspaceView
import dev.lemonadecode.workspace.uimodel.WorktreeBranchModel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import dev.lemonadecode.workspace.uimodel.SubSessionLocation as LocationModel

class WorkspacePresenterTest {
  private val ar = SessionId("ar")
  private val sessions = FakeSessionService()
  private val reviews = FakeReviewService()
  private val settings = FakeSettingsService()
  private val updates = FakeUpdateService()
  private val presenter = WorkspacePresenter(
    sessions,
    reviews,
    settings,
    object : HomeDirectory {
      override val path: String = ShowcaseFixtures.Home
    },
    updates,
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
  fun `the diff tab compares with a branch picked from the list`() = runTest {
    withSession()
    reviews.setReview(ar, Review(DiffState.Ready(ShowcaseFixtures.snapshot), emptyList(), base = "main", bases = listOf("main", "release")))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      awaitItemWhere { it.content is WorkspaceContentModel.Diff }.eventSink(WorkspaceEvent.ToggleDiffBasePicker)
      val open = awaitItemWhere { (it.content as? WorkspaceContentModel.Diff)?.review?.toolbar?.base?.pickerOpen == true }
      val base = assertIs<WorkspaceContentModel.Diff>(open.content).review.toolbar.base
      assertEquals("main", base.selected)
      assertEquals(listOf("main", "release"), base.options)
      open.eventSink(WorkspaceEvent.CompareWith("release"))
      awaitItemWhere { (it.content as? WorkspaceContentModel.Diff)?.review?.toolbar?.base?.pickerOpen == false }
      yieldUntil { reviews.comparedWith.isNotEmpty() }
      assertEquals(listOf(ar to "release"), reviews.comparedWith)
    }
  }

  @Test
  fun `with no changes the diff still offers the branch picker`() = runTest {
    withSession()
    reviews.setReview(ar, Review(DiffState.Ready(ShowcaseFixtures.snapshot.copy(files = emptyList())), emptyList(), base = "main", bases = listOf("main", "release")))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      val message = awaitItemWhere { it.content is WorkspaceContentModel.Message && it.viewSwitcher?.selected == WorkspaceView.Diff }
      assertEquals(listOf("main", "release"), assertIs<WorkspaceContentModel.Message>(message.content).diffToolbar?.base?.options)
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
  fun `an update shows a button whose panel installs it and follows the download`() = runTest {
    val available = UpdateState.Available(
      current = AppVersion(1, 2, 0),
      release = AppRelease(AppVersion(1, 3, 0), "https://github.com/williankl/loupe/releases/tag/v1.3.0"),
    )
    presenter.test {
      assertEquals(null, awaitItem().topBar.update)
      updates.setState(available)
      val offered = awaitItemWhere { it.topBar.update != null }
      assertEquals(UpdateModel("1.3.0", "1.2.0", available.release.pageUrl, open = false, status = UpdateStatusModel.Ready), offered.topBar.update)

      offered.eventSink(WorkspaceEvent.ToggleUpdate)
      awaitItemWhere { it.topBar.update?.open == true }.eventSink(WorkspaceEvent.InstallUpdate)
      assertEquals(1, updates.installCount)

      updates.setState(available.copy(progress = UpdateProgress.Downloading(0.5f)))
      awaitItemWhere { it.topBar.update?.status == UpdateStatusModel.Downloading(50) }
      updates.setState(available.copy(progress = UpdateProgress.Failed(UpdateFailure.Checksum)))
      val failed = awaitItemWhere { it.topBar.update?.status is UpdateStatusModel.Failed }
      assertEquals(
        UpdateStatusModel.Failed(Res.string.update_failed_checksum.asLemonadeCodeString()),
        failed.topBar.update?.status,
      )
    }
  }

  @Test
  fun `opening settings closes the update panel`() = runTest {
    updates.setState(
      UpdateState.Available(AppVersion(1, 2, 0), AppRelease(AppVersion(1, 3, 0), "https://github.com/williankl/loupe/releases/tag/v1.3.0")),
    )
    presenter.test {
      awaitItemWhere { it.topBar.update != null }.eventSink(WorkspaceEvent.ToggleUpdate)
      awaitItemWhere { it.topBar.update?.open == true }.eventSink(WorkspaceEvent.ToggleSettings)
      val settingsOpen = awaitItemWhere { it.settings.open }
      assertEquals(false, settingsOpen.topBar.update?.open)
    }
  }

  @Test
  fun `the sidebar width is saved, kept within its limits`() = runTest {
    withSession()
    presenter.test {
      val model = awaitItem()
      assertEquals(232, model.sessions.sidebarWidth)
      model.eventSink(WorkspaceEvent.ResizeSessionsSidebar(300))
      assertEquals(300, awaitItemWhere { it.sessions.sidebarWidth != 232 }.sessions.sidebarWidth)
      assertEquals(300, settings.settings.value.sidebarWidth)

      model.eventSink(WorkspaceEvent.ResizeSessionsSidebar(5_000))
      awaitItemWhere { it.sessions.sidebarWidth == SessionsPanelModel.MaxSidebarWidth }
      assertEquals(SessionsPanelModel.MaxSidebarWidth, settings.settings.value.sidebarWidth)
    }
  }

  @Test
  fun `a new sub-session opens on the last location and gets selected`() = runTest {
    withSession()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleNewSubSessionMenu)
      val menu = awaitItemWhere { it.newSubSession.open }
      menu.eventSink(WorkspaceEvent.SetSubSessionLocation(dev.lemonadecode.workspace.uimodel.SubSessionLocation.NewWorktree))
      awaitItemWhere { it.newSubSession.location == dev.lemonadecode.workspace.uimodel.SubSessionLocation.NewWorktree }
        .eventSink(WorkspaceEvent.CreateSubSession)
      val created = awaitItemWhere { it.header?.name == "sub-1" }
      assertEquals(false, created.newSubSession.open)
      assertEquals(dev.lemonadecode.domain.session.SubSessionLocation.NewWorktree, settings.settings.value.subSessionLocation)
    }
  }

  @Test
  fun `a new worktree from the panel suggests a branch, follows what is typed and is created on it`() = runTest {
    withSession()
    sessions.worktreeBranchSuggestion = "worktree/generating-bugs"
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleNewSubSessionMenu)
      awaitItemWhere { it.newSubSession.open }.eventSink(WorkspaceEvent.SetSubSessionLocation(LocationModel.NewWorktree))
      val suggested = awaitItemWhere { it.newSubSession.worktreeBranch != null }
      assertEquals(
        WorktreeBranchModel(name = "worktree/generating-bugs", path = "~/code/storefront.worktrees/generating-bugs", problem = null, canCreate = true),
        suggested.newSubSession.worktreeBranch,
      )

      suggested.eventSink(WorkspaceEvent.SetWorktreeBranch("feat/login"))
      val typed = awaitItemWhere { it.newSubSession.worktreeBranch?.name == "feat/login" }
      assertEquals("~/code/storefront.worktrees/login", typed.newSubSession.worktreeBranch?.path)

      typed.eventSink(WorkspaceEvent.CreateSubSession)
      // Enter held down, or pressed again while git works: still one worktree.
      typed.eventSink(WorkspaceEvent.CreateSubSession)
      val created = awaitItemWhere { it.header?.name == "sub-1" }
      assertEquals(false, created.newSubSession.open)
      assertEquals(listOf<String?>("feat/login"), sessions.started.map { it.branch })
    }
  }

  @Test
  fun `the panel suggests a fresh branch each time it opens`() = runTest {
    withSession()
    settings.update { it.copy(subSessionLocation = dev.lemonadecode.domain.session.SubSessionLocation.NewWorktree) }
    sessions.worktreeBranchSuggestion = "worktree/herding-yaks"
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleNewSubSessionMenu)
      awaitItemWhere { it.newSubSession.worktreeBranch?.name == "worktree/herding-yaks" }.eventSink(WorkspaceEvent.SetWorktreeBranch("typed"))
      awaitItemWhere { it.newSubSession.worktreeBranch?.name == "typed" }.eventSink(WorkspaceEvent.DismissNewSubSessionMenu)
      sessions.worktreeBranchSuggestion = "worktree/taming-gremlins"
      awaitItemWhere { !it.newSubSession.open }.eventSink(WorkspaceEvent.ToggleNewSubSessionMenu)
      awaitItemWhere { it.newSubSession.worktreeBranch?.name == "worktree/taming-gremlins" }
    }
  }

  @Test
  fun `a branch git won't take, or one it already has, isn't created`() = runTest {
    withSession()
    sessions.existingBranches = setOf("feat/taken")
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.ToggleNewSubSessionMenu)
      awaitItemWhere { it.newSubSession.open }.eventSink(WorkspaceEvent.SetSubSessionLocation(LocationModel.NewWorktree))

      awaitItemWhere { it.newSubSession.worktreeBranch != null }.eventSink(WorkspaceEvent.SetWorktreeBranch("my branch"))
      val invalid = awaitItemWhere { it.newSubSession.worktreeBranch?.name == "my branch" }
      assertEquals(Res.string.worktree_branch_invalid.asLemonadeCodeString(), invalid.newSubSession.worktreeBranch?.problem)
      assertEquals(false, invalid.newSubSession.worktreeBranch?.canCreate)
      invalid.eventSink(WorkspaceEvent.CreateSubSession)

      invalid.eventSink(WorkspaceEvent.SetWorktreeBranch(""))
      val blank = awaitItemWhere { it.newSubSession.worktreeBranch?.name == "" }
      assertEquals(null, blank.newSubSession.worktreeBranch?.problem, "nothing to complain about yet")
      assertEquals(false, blank.newSubSession.worktreeBranch?.canCreate)
      blank.eventSink(WorkspaceEvent.CreateSubSession)

      blank.eventSink(WorkspaceEvent.SetWorktreeBranch("feat/taken"))
      awaitItemWhere { it.newSubSession.worktreeBranch?.name == "feat/taken" }.eventSink(WorkspaceEvent.CreateSubSession)
      val taken = awaitItemWhere { it.newSubSession.worktreeBranch?.problem != null }
      assertEquals(Res.string.worktree_branch_taken.asLemonadeCodeString("feat/taken"), taken.newSubSession.worktreeBranch?.problem)
      assertEquals(false, taken.newSubSession.worktreeBranch?.canCreate)
      assertEquals(true, taken.newSubSession.open, "the panel stays open to pick another name")
      assertEquals(null, taken.error, "said under the field, not in a banner")
      assertEquals(listOf<String?>("feat/taken"), sessions.started.map { it.branch }, "only a name git takes reaches it")

      taken.eventSink(WorkspaceEvent.SetWorktreeBranch("feat/taken-2"))
      val renamed = awaitItemWhere { it.newSubSession.worktreeBranch?.name == "feat/taken-2" }
      assertEquals(null, renamed.newSubSession.worktreeBranch?.problem)
      assertEquals(true, renamed.newSubSession.worktreeBranch?.canCreate)
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
  fun `a new worktree from the menu asks for its branch, then starts under that session and gets selected`() = runTest {
    withFamily()
    sessions.worktreeBranchSuggestion = "worktree/generating-bugs"
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.OpenSessionMenu("d2"))
      awaitItemWhere { it.sessions.menu != null }.eventSink(WorkspaceEvent.StartNamingWorktree("d2"))
      val naming = awaitItemWhere { it.sessions.menu?.newWorktree != null }
      assertEquals("d2", naming.sessions.menu?.sessionId, "the field takes the menu's place")
      assertEquals(
        WorktreeBranchModel(name = "worktree/generating-bugs", path = "~/code/storefront.worktrees/generating-bugs", problem = null, canCreate = true),
        naming.sessions.menu?.newWorktree,
      )
      assertTrue(sessions.started.isEmpty(), "nothing is created before the branch is named")

      naming.eventSink(WorkspaceEvent.SetWorktreeBranch("fix/flaky-login"))
      awaitItemWhere { it.sessions.menu?.newWorktree?.name == "fix/flaky-login" }
        .eventSink(WorkspaceEvent.CreateSubSessionFrom("d2", LocationModel.NewWorktree))
      val created = awaitItemWhere { it.header?.name == "sub-1" }
      assertEquals(null, created.sessions.menu)
      val call = sessions.started.single()
      assertEquals(Triple(SessionId("d2"), dev.lemonadecode.domain.session.SubSessionLocation.NewWorktree, "fix/flaky-login"), Triple(call.parentId, call.location, call.branch))
      assertEquals(dev.lemonadecode.domain.session.SubSessionLocation.NewWorktree, settings.settings.value.subSessionLocation)
    }
  }

  @Test
  fun `closing the branch field closes the menu, and the menu opens on its items again`() = runTest {
    withFamily()
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.OpenSessionMenu("d2"))
      awaitItemWhere { it.sessions.menu != null }.eventSink(WorkspaceEvent.StartNamingWorktree("d2"))
      awaitItemWhere { it.sessions.menu?.newWorktree != null }.eventSink(WorkspaceEvent.DismissSessionMenu)
      awaitItemWhere { it.sessions.menu == null }.eventSink(WorkspaceEvent.OpenSessionMenu("d2"))
      assertEquals(null, awaitItemWhere { it.sessions.menu != null }.sessions.menu?.newWorktree)
      assertTrue(sessions.started.isEmpty())
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
      assertEquals(Res.string.session_path_copied.asLemonadeCodeString(ShowcaseFixtures.Repo), copied.toast)
      awaitItemWhere { it.toast == null }
    }
  }

  private fun dev.lemonadecode.workspace.uimodel.WorkspaceModel.lines(path: String): List<DiffRowModel.Line> = (content as? WorkspaceContentModel.Diff)?.review?.files?.first { it.path == path }?.rows?.filterIsInstance<DiffRowModel.Line>().orEmpty()
}
