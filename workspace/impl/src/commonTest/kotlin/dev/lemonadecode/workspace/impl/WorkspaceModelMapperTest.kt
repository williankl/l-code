package dev.lemonadecode.workspace.impl

import dev.lemonadecode.domain.review.CodeToken
import dev.lemonadecode.domain.review.CodeTokenKind
import dev.lemonadecode.domain.review.DiffSnapshot
import dev.lemonadecode.domain.review.DiffState
import dev.lemonadecode.domain.review.Review
import dev.lemonadecode.platform.localization.LemonadeCodeString
import dev.lemonadecode.workspace.impl.showcase.ShowcaseFixtures
import dev.lemonadecode.workspace.impl.showcase.WorkspaceShowcase
import dev.lemonadecode.workspace.uimodel.DiffRowModel
import dev.lemonadecode.workspace.uimodel.SendBarSummary
import dev.lemonadecode.workspace.uimodel.SessionNavigationModel
import dev.lemonadecode.workspace.uimodel.WorkspaceContentModel
import dev.lemonadecode.workspace.uimodel.WorkspaceView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspaceModelMapperTest {
  private fun diffOf(showcase: WorkspaceShowcase) = assertIs<WorkspaceContentModel.Diff>(showcase.model().content).review

  @Test
  fun `every design screen maps`() {
    WorkspaceShowcase.entries.forEach { showcase -> val _ = showcase.model() }
  }

  @Test
  fun `a session's subtitle is its agent, else its shell, then its branch, else its folder`() {
    val session = ShowcaseFixtures.session("ar", "api-ratelimit").copy(agent = null)
    assertEquals(listOf(session.shell, ShowcaseFixtures.Branch), (SessionsMapper.meta(session, ShowcaseFixtures.Home) as LemonadeCodeString.Resource).args)
    assertEquals(listOf("claude", ShowcaseFixtures.Branch), (SessionsMapper.meta(session.copy(agent = "claude"), ShowcaseFixtures.Home) as LemonadeCodeString.Resource).args)
    val outsideGit = session.copy(branch = null, workingDirectory = "${ShowcaseFixtures.Home}/notes")
    assertEquals(listOf(session.shell, "~/notes"), (SessionsMapper.meta(outsideGit, ShowcaseFixtures.Home) as LemonadeCodeString.Resource).args)
  }

  @Test
  fun `diff totals match the design`() {
    val stat = WorkspaceShowcase.CommentOnDiff.model().viewSwitcher?.diffStat
    assertEquals(90, stat?.additions)
    assertEquals(22, stat?.deletions)
  }

  @Test
  fun `hunks carry three lines of context and the enclosing declaration`() {
    val app = diffOf(WorkspaceShowcase.CommentOnDiff).files.first { it.fileName == "app.ts" }
    val headers = app.rows.filterIsInstance<DiffRowModel.Hunk>().map { it.header }
    assertEquals(listOf("@@ -2,7 +2,7 @@", "@@ -17,7 +17,10 @@  export function createApp(options: AppOptions = {}) {"), headers)
  }

  @Test
  fun `a range comment sits under its last line and the composer covers the selection`() {
    val review = diffOf(WorkspaceShowcase.MultiLineComment)
    val lines = review.files.first { it.fileName == "rateLimit.ts" }.rows.filterIsInstance<DiffRowModel.Line>()
    assertEquals(26, lines.first { line -> line.comments.any { it.location == "rateLimit.ts:24–26" } }.newNumber)
    assertEquals((28..32).toList(), lines.filter { it.selected }.map { it.newNumber })
    val title = assertIs<LemonadeCodeString.Resource>(lines.single { it.composer != null }.composer?.title)
    assertEquals(listOf("src/middleware/rateLimit.ts:28–32"), title.args)
    assertEquals(SendBarSummary.Unsent(commentCount = 3, fileCount = 2), review.sendBar.summary)
  }

  @Test
  fun `typing in the composer maps again only the line it sits under`() {
    val path = ShowcaseFixtures.path("rl")
    val row = ShowcaseFixtures.rowOf("rl", 19)
    val review = Review(DiffState.Ready(ShowcaseFixtures.snapshot), listOf(ShowcaseFixtures.comment("app", 20)))
    val cache = DiffRowCache()
    fun files(draft: String) = assertIs<WorkspaceContentModel.Diff>(
      DiffMapper.content(review, WorkspaceViewState(view = WorkspaceView.Diff, composer = Composer(LineRange(path, row, row), draft)), null, rowCache = cache),
    ).review.files

    val before = files("Rea")
    val after = files("Read")
    val rows = after.first { it.path == path }.rows
    val changed = rows.indices.filter { rows[it] !== before.first { file -> file.path == path }.rows[it] }
    assertEquals("Read", (rows[changed.single()] as DiffRowModel.Line).composer?.draft)
    assertTrue(after.filter { it.path != path }.all { file -> file.rows === before.first { it.path == file.path }.rows })
  }

  @Test
  fun `a diff read again maps its lines again`() {
    val cache = DiffRowCache()
    val view = WorkspaceViewState(view = WorkspaceView.Diff)
    fun firstLine(snapshot: DiffSnapshot) = assertIs<WorkspaceContentModel.Diff>(
      DiffMapper.content(Review(DiffState.Ready(snapshot), emptyList()), view, null, rowCache = cache),
    ).review.files.first().rows.filterIsInstance<DiffRowModel.Line>().first()

    val snapshot = ShowcaseFixtures.snapshot
    val first = firstLine(snapshot)
    val file = snapshot.files.first()
    val edited = file.copy(lines = file.lines.map { it.copy(tokens = listOf(CodeToken("edited", CodeTokenKind.Plain))) })
    assertEquals("edited", firstLine(snapshot.copy(files = listOf(edited) + snapshot.files.drop(1))).tokens.single().text)
    assertEquals(first.tokens, firstLine(snapshot).tokens)
  }

  @Test
  fun `the tree lights the path to the selected session`() {
    val rows = assertIs<SessionNavigationModel.Tree>(WorkspaceShowcase.SessionTree.model().sessions.navigation).rows
    assertEquals(listOf("review-1", "atomic-refill", "lua-script", "bench-redis"), rows.filter { it.connector?.branchHighlighted == true }.map { it.name })
    assertTrue(rows.single { it.name == "bench-redis" }.selected)
  }

  @Test
  fun `paths are shown relative to home`() {
    assertEquals("~/code/storefront.worktrees/sub-2", WorkspaceShowcase.AfterCreatingBoth.model().topBar.path)
  }

  @Test
  fun `keep awake shows in the top bar only while something works`() {
    assertTrue(WorkspaceShowcase.SettingsOpen.model().topBar.keepAwakeLabel != null)
    assertEquals(null, WorkspaceShowcase.AgentFinishes.model().topBar.keepAwakeLabel)
  }
}
