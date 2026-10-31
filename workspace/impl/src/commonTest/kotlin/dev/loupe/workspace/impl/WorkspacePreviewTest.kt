package dev.loupe.workspace.impl

import dev.loupe.domain.review.CommentId
import dev.loupe.domain.review.CommentStatus
import dev.loupe.domain.review.DiffLine
import dev.loupe.domain.review.DiffLineKind
import dev.loupe.domain.review.DiffSnapshot
import dev.loupe.domain.review.DiffState
import dev.loupe.domain.review.FileChange
import dev.loupe.domain.review.FileContent
import dev.loupe.domain.review.FileDiff
import dev.loupe.domain.review.FileVersion
import dev.loupe.domain.review.LineAnchor
import dev.loupe.domain.review.LineSide
import dev.loupe.domain.review.Review
import dev.loupe.domain.review.ReviewComment
import dev.loupe.domain.session.SessionId
import dev.loupe.platform.core.LoupeResult
import dev.loupe.platform.presenter.testing.awaitItemWhere
import dev.loupe.platform.presenter.testing.test
import dev.loupe.platform.presenter.testing.yieldUntil
import dev.loupe.platform.process.HomeDirectory
import dev.loupe.review.api.ReviewError
import dev.loupe.review.fake.FakeReviewService
import dev.loupe.sessions.fake.FakeSessionService
import dev.loupe.settings.fake.FakeSettingsService
import dev.loupe.workspace.impl.showcase.ShowcaseFixtures
import dev.loupe.workspace.uimodel.DiffFileModel
import dev.loupe.workspace.uimodel.FilePreviewContentModel
import dev.loupe.workspace.uimodel.PreviewKindModel
import dev.loupe.workspace.uimodel.PreviewLayout
import dev.loupe.workspace.uimodel.PreviewSideModel
import dev.loupe.workspace.uimodel.WorkspaceContentModel
import dev.loupe.workspace.uimodel.WorkspaceEvent
import dev.loupe.workspace.uimodel.WorkspaceModel
import dev.loupe.workspace.uimodel.WorkspaceView
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspacePreviewTest {
  private val ar = SessionId("ar")
  private val sessions = FakeSessionService()
  private val reviews = FakeReviewService()
  private val presenter = WorkspacePresenter(
    sessions,
    reviews,
    FakeSettingsService(),
    object : HomeDirectory {
      override val path: String = ShowcaseFixtures.Home
    },
  )

  private val logo = FileDiff("art/logo.png", null, FileChange.Modified, "PNG", emptyList(), isBinary = true)
  private val readme = FileDiff(
    path = "README.md",
    previousPath = null,
    change = FileChange.Modified,
    language = "Markdown",
    lines = listOf(
      DiffLine(DiffLineKind.Removed, 1, null, "# Old", emptyList()),
      DiffLine(DiffLineKind.Added, null, 1, "# New", emptyList()),
    ),
    isBinary = false,
  )
  private val chart = FileDiff("data/chart.csv", null, FileChange.Added, "CSV", emptyList(), isBinary = false)

  private fun withSession() {
    sessions.setSessions(listOf(ShowcaseFixtures.session("ar", "api-ratelimit")))
    val snapshot = DiffSnapshot(baseRef = "main", files = listOf(logo, readme, chart), hasUncommittedChanges = true, mergeBase = "abc123")
    reviews.setReview(ar, Review(DiffState.Ready(snapshot), emptyList()))
  }

  private fun bytes(vararg values: Int): LoupeResult<FileContent, ReviewError> = LoupeResult.success(FileContent.Bytes(ByteArray(values.size) { values[it].toByte() }))

  @Test
  fun `a binary image opens on its preview with both sides side by side, and folds away`() = runTest {
    withSession()
    reviews.fileContents = mapOf(
      (logo.path to FileVersion.Base) to bytes(1, 2),
      (logo.path to FileVersion.WorkingTree) to bytes(3),
    )
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      val model = awaitItemWhere { it.file(logo.path)?.preview?.content is FilePreviewContentModel.Ready }
      val open = model.file(logo.path)!!
      val preview = open.preview!!
      assertTrue(open.expanded && preview.showing)
      assertEquals(PreviewKindModel.Image, preview.kind)
      assertEquals(PreviewLayout.SideBySide, preview.layout)
      val ready = assertIs<FilePreviewContentModel.Ready>(preview.content)
      assertContentEquals(byteArrayOf(1, 2), assertIs<PreviewSideModel.Content>(ready.before).bytes)
      assertEquals("png", assertIs<PreviewSideModel.Content>(ready.after).extension)

      model.eventSink(WorkspaceEvent.ToggleFileExpanded(logo.path))
      awaitItemWhere { it.file(logo.path)?.expanded == false }.eventSink(WorkspaceEvent.ToggleFileExpanded(logo.path))
      awaitItemWhere { it.file(logo.path)?.let { file -> file.expanded && file.preview?.showing == true } == true }
    }
  }

  @Test
  fun `a document opens on its preview, can switch sides, and toggles to its diff and back`() = runTest {
    withSession()
    reviews.fileContents = mapOf((readme.path to FileVersion.WorkingTree) to bytes(35))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      val previewing = awaitItemWhere { it.file(readme.path)?.preview?.content is FilePreviewContentModel.Ready }
      val file = previewing.file(readme.path)!!
      assertEquals(PreviewLayout.After, file.preview?.layout)
      assertTrue(file.rows.isEmpty())
      assertTrue(!file.canShowFullFile)

      previewing.eventSink(WorkspaceEvent.SetPreviewLayout(readme.path, PreviewLayout.SideBySide))
      val switched = awaitItemWhere { it.file(readme.path)?.preview?.layout == PreviewLayout.SideBySide }
      // The base side wasn't listed, so it reads as missing.
      assertNull(assertIs<FilePreviewContentModel.Ready>(switched.file(readme.path)!!.preview!!.content).before)

      switched.eventSink(WorkspaceEvent.ToggleFilePreview(readme.path))
      val text = awaitItemWhere { it.file(readme.path)?.preview?.showing == false }
      assertTrue(text.file(readme.path)!!.rows.isNotEmpty())
      text.eventSink(WorkspaceEvent.ToggleFilePreview(readme.path))
      awaitItemWhere { it.file(readme.path)?.preview?.content is FilePreviewContentModel.Ready }
    }
  }

  @Test
  fun `a file with comments shows its diff, where the comments are, until its preview is asked for`() = runTest {
    withSession()
    val comment = ReviewComment(CommentId("c1"), readme.path, LineAnchor(LineSide.New, 1), LineAnchor(LineSide.New, 1), "Title case?", CommentStatus.Unsent)
    reviews.setReview(ar, reviews.reviewOf(ar).copy(comments = listOf(comment)))
    reviews.fileContents = mapOf((readme.path to FileVersion.WorkingTree) to bytes(35))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      val diff = awaitItemWhere { it.file(readme.path)?.rows?.isNotEmpty() == true }
      assertEquals(false, diff.file(readme.path)!!.preview?.showing)
      diff.eventSink(WorkspaceEvent.ToggleFilePreview(readme.path))
      awaitItemWhere { it.file(readme.path)?.preview?.content is FilePreviewContentModel.Ready }
    }
  }

  @Test
  fun `an added file has a single side and no layout choice`() = runTest {
    withSession()
    reviews.fileContents = mapOf((chart.path to FileVersion.WorkingTree) to bytes(97))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      val preview = awaitItemWhere { it.file(chart.path)?.preview?.content is FilePreviewContentModel.Ready }.file(chart.path)!!.preview!!
      assertNull(preview.layout)
      assertEquals(PreviewKindModel.Table, preview.kind)
    }
  }

  @Test
  fun `previews are read only in the diff view, and again when it refreshes`() = runTest {
    withSession()
    reviews.fileContents = mapOf((logo.path to FileVersion.WorkingTree) to bytes(3))
    fun logoReads() = reviews.fileContentCalls.count { it.first == logo.path }
    presenter.test {
      val terminal = awaitItem()
      assertEquals(0, reviews.fileContentCalls.size, "nothing is read while the terminal shows")
      terminal.eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      val ready = awaitItemWhere { it.file(logo.path)?.preview?.content is FilePreviewContentModel.Ready }
      yieldUntil { logoReads() == 2 }

      ready.eventSink(WorkspaceEvent.RefreshDiff)
      yieldUntil { logoReads() == 4 }
      assertEquals(listOf(FileVersion.Base, FileVersion.WorkingTree), reviews.fileContentCalls.filter { it.first == logo.path }.drop(2).map { it.second }.sorted())
    }
  }

  @Test
  fun `a file that can't be read shows why`() = runTest {
    withSession()
    reviews.fileContents = mapOf((logo.path to FileVersion.Base) to LoupeResult.failure(ReviewError.ReadFailed("disk on fire")))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      awaitItemWhere { it.file(logo.path)?.preview?.content is FilePreviewContentModel.Failed }
    }
  }

  private fun WorkspaceModel.file(path: String): DiffFileModel? = (content as? WorkspaceContentModel.Diff)?.review?.files?.firstOrNull { it.path == path }
}
