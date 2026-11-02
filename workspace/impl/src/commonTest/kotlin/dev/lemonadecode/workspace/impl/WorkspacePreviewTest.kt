package dev.lemonadecode.workspace.impl

import dev.lemonadecode.domain.review.CommentId
import dev.lemonadecode.domain.review.CommentStatus
import dev.lemonadecode.domain.review.DiffLine
import dev.lemonadecode.domain.review.DiffLineKind
import dev.lemonadecode.domain.review.DiffSnapshot
import dev.lemonadecode.domain.review.DiffState
import dev.lemonadecode.domain.review.FileChange
import dev.lemonadecode.domain.review.FileContent
import dev.lemonadecode.domain.review.FileDiff
import dev.lemonadecode.domain.review.FileVersion
import dev.lemonadecode.domain.review.LineAnchor
import dev.lemonadecode.domain.review.LineSide
import dev.lemonadecode.domain.review.Review
import dev.lemonadecode.domain.review.ReviewComment
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.mobile.fake.FakeMobileService
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.presenter.testing.awaitItemWhere
import dev.lemonadecode.platform.presenter.testing.test
import dev.lemonadecode.platform.presenter.testing.yieldUntil
import dev.lemonadecode.platform.process.HomeDirectory
import dev.lemonadecode.review.api.ReviewError
import dev.lemonadecode.review.fake.FakeReviewService
import dev.lemonadecode.sessions.fake.FakeSessionService
import dev.lemonadecode.settings.fake.FakeSettingsService
import dev.lemonadecode.updates.fake.FakeUpdateService
import dev.lemonadecode.workspace.impl.showcase.ShowcaseFixtures
import dev.lemonadecode.workspace.uimodel.DiffFileModel
import dev.lemonadecode.workspace.uimodel.FilePreviewContentModel
import dev.lemonadecode.workspace.uimodel.PreviewKindModel
import dev.lemonadecode.workspace.uimodel.PreviewLayout
import dev.lemonadecode.workspace.uimodel.PreviewSideModel
import dev.lemonadecode.workspace.uimodel.WorkspaceContentModel
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import dev.lemonadecode.workspace.uimodel.WorkspaceModel
import dev.lemonadecode.workspace.uimodel.WorkspaceView
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
    FakeUpdateService(),
    FakeMobileService(),
  ) { 0L }

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

  private fun bytes(vararg values: Int): LemonadeCodeResult<FileContent, ReviewError> = LemonadeCodeResult.success(FileContent.Bytes(ByteArray(values.size) { values[it].toByte() }))

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
    reviews.fileContents = mapOf((logo.path to FileVersion.Base) to LemonadeCodeResult.failure(ReviewError.ReadFailed("disk on fire")))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      awaitItemWhere { it.file(logo.path)?.preview?.content is FilePreviewContentModel.Failed }
    }
  }

  @Test
  fun `a video opens on its preview, before and after side by side`() = runTest {
    val demo = FileDiff("docs/demo.mp4", null, FileChange.Modified, "MP4", emptyList(), isBinary = true)
    sessions.setSessions(listOf(ShowcaseFixtures.session("ar", "api-ratelimit")))
    val snapshot = DiffSnapshot(baseRef = "main", files = listOf(demo), hasUncommittedChanges = true, mergeBase = "abc123")
    reviews.setReview(ar, Review(DiffState.Ready(snapshot), emptyList()))
    reviews.fileContents = mapOf(
      (demo.path to FileVersion.Base) to bytes(1),
      (demo.path to FileVersion.WorkingTree) to bytes(2),
    )
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      val preview = awaitItemWhere { it.file(demo.path)?.preview?.content is FilePreviewContentModel.Ready }.file(demo.path)!!.preview!!
      assertEquals(PreviewKindModel.Video, preview.kind)
      assertTrue(preview.showing)
      assertEquals(PreviewLayout.SideBySide, preview.layout)
      assertEquals("mp4", assertIs<PreviewSideModel.Content>(assertIs<FilePreviewContentModel.Ready>(preview.content).after).extension)
    }
  }

  private fun WorkspaceModel.file(path: String): DiffFileModel? = (content as? WorkspaceContentModel.Diff)?.review?.files?.firstOrNull { it.path == path }
}
