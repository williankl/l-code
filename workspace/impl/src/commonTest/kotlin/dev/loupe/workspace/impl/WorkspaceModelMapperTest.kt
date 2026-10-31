package dev.loupe.workspace.impl

import dev.loupe.platform.localization.LoupeString
import dev.loupe.workspace.impl.showcase.WorkspaceShowcase
import dev.loupe.workspace.uimodel.DiffRowModel
import dev.loupe.workspace.uimodel.SendBarSummary
import dev.loupe.workspace.uimodel.SessionNavigationModel
import dev.loupe.workspace.uimodel.WorkspaceContentModel
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
    val title = assertIs<LoupeString.Resource>(lines.single { it.composer != null }.composer?.title)
    assertEquals(listOf("src/middleware/rateLimit.ts:28–32"), title.args)
    assertEquals(SendBarSummary.Unsent(commentCount = 3, fileCount = 2), review.sendBar.summary)
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
