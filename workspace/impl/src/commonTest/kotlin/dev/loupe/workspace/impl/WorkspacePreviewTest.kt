package dev.loupe.workspace.impl

import dev.loupe.domain.review.DiffLine
import dev.loupe.domain.review.DiffLineKind
import dev.loupe.domain.review.DiffSnapshot
import dev.loupe.domain.review.DiffState
import dev.loupe.domain.review.FileChange
import dev.loupe.domain.review.FileContent
import dev.loupe.domain.review.FileDiff
import dev.loupe.domain.review.FileVersion
import dev.loupe.domain.review.Review
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
  fun `opening a binary image shows its preview with both sides side by side`() = runTest {
    withSession()
    reviews.fileContents = mapOf(
      (logo.path to FileVersion.Base) to bytes(1, 2),
      (logo.path to FileVersion.WorkingTree) to bytes(3),
    )
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      val closed = awaitItemWhere { it.file(logo.path) != null }.file(logo.path)!!
      assertTrue(!closed.expanded && closed.preview?.showing == false)

      awaitItem().eventSink(WorkspaceEvent.ToggleFileExpanded(logo.path))
      val open = awaitItemWhere { it.file(logo.path)?.preview?.content is FilePreviewContentModel.Ready }.file(logo.path)!!
      val preview = open.preview!!
      assertTrue(open.expanded && preview.showing)
      assertEquals(PreviewKindModel.Image, preview.kind)
      assertEquals(PreviewLayout.SideBySide, preview.layout)
      val ready = assertIs<FilePreviewContentModel.Ready>(preview.content)
      assertContentEquals(byteArrayOf(1, 2), assertIs<PreviewSideModel.Content>(ready.before).bytes)
      assertEquals("png", assertIs<PreviewSideModel.Content>(ready.after).extension)
    }
  }

  @Test
  fun `a document previews alone, can switch sides, and toggles back to its diff`() = runTest {
    withSession()
    reviews.fileContents = mapOf((readme.path to FileVersion.WorkingTree) to bytes(35))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      val diff = awaitItemWhere { it.file(readme.path)?.rows?.isNotEmpty() == true }
      diff.eventSink(WorkspaceEvent.ToggleFilePreview(readme.path))

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
      val back = awaitItemWhere { it.file(readme.path)?.preview?.showing == false }
      assertTrue(back.file(readme.path)!!.rows.isNotEmpty())
    }
  }

  @Test
  fun `an added file has a single side and no layout choice`() = runTest {
    withSession()
    reviews.fileContents = mapOf((chart.path to FileVersion.WorkingTree) to bytes(97))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      awaitItemWhere { it.file(chart.path) != null }.eventSink(WorkspaceEvent.ToggleFilePreview(chart.path))
      val preview = awaitItemWhere { it.file(chart.path)?.preview?.content is FilePreviewContentModel.Ready }.file(chart.path)!!.preview!!
      assertNull(preview.layout)
      assertEquals(PreviewKindModel.Table, preview.kind)
    }
  }

  @Test
  fun `refreshing the diff reads open previews again`() = runTest {
    withSession()
    reviews.fileContents = mapOf((logo.path to FileVersion.WorkingTree) to bytes(3))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      awaitItemWhere { it.file(logo.path) != null }.eventSink(WorkspaceEvent.ToggleFilePreview(logo.path))
      val ready = awaitItemWhere { it.file(logo.path)?.preview?.content is FilePreviewContentModel.Ready }
      yieldUntil { reviews.fileContentCalls.size == 2 }

      ready.eventSink(WorkspaceEvent.RefreshDiff)
      yieldUntil { reviews.fileContentCalls.size == 4 }
      assertEquals(listOf(FileVersion.Base, FileVersion.WorkingTree), reviews.fileContentCalls.drop(2).map { it.second }.sorted())
    }
  }

  @Test
  fun `a file that can't be read shows why`() = runTest {
    withSession()
    reviews.fileContents = mapOf((logo.path to FileVersion.Base) to LoupeResult.failure(ReviewError.ReadFailed("disk on fire")))
    presenter.test {
      awaitItem().eventSink(WorkspaceEvent.SelectView(WorkspaceView.Diff))
      awaitItemWhere { it.file(logo.path) != null }.eventSink(WorkspaceEvent.ToggleFilePreview(logo.path))
      awaitItemWhere { it.file(logo.path)?.preview?.content is FilePreviewContentModel.Failed }
    }
  }

  private fun WorkspaceModel.file(path: String): DiffFileModel? = (content as? WorkspaceContentModel.Diff)?.review?.files?.firstOrNull { it.path == path }
}
