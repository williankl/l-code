package dev.lemonadecode.review.impl

import app.cash.turbine.test
import dev.lemonadecode.data.git.GitCheckout
import dev.lemonadecode.data.git.fake.FakeGitDataSource
import dev.lemonadecode.domain.review.CommentStatus
import dev.lemonadecode.domain.review.DiffState
import dev.lemonadecode.domain.review.FileChange
import dev.lemonadecode.domain.review.FileContent
import dev.lemonadecode.domain.review.FileVersion
import dev.lemonadecode.domain.review.LineAnchor
import dev.lemonadecode.domain.review.LineSide
import dev.lemonadecode.domain.review.NewComment
import dev.lemonadecode.domain.review.Review
import dev.lemonadecode.domain.session.Session
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SessionOrigin
import dev.lemonadecode.domain.session.SessionPlacement
import dev.lemonadecode.domain.session.SessionStatus
import dev.lemonadecode.platform.architecture.testing.FakeLemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.core.getOrNull
import dev.lemonadecode.review.api.ReviewError
import dev.lemonadecode.sessions.api.SessionService
import dev.lemonadecode.sessions.fake.FakeSessionService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReviewServiceImplTest {
  private val id = SessionId("ar")
  private val session = Session(
    id = id,
    name = "api-ratelimit",
    shell = "zsh",
    workingDirectory = "/code/storefront",
    repositoryRoot = "/code/storefront",
    branch = "feat/rate-limit",
    parentId = null,
    placement = SessionPlacement.Root,
    origin = SessionOrigin.Opened,
    status = SessionStatus.Idle,
  )
  private val subSession = session.copy(
    id = SessionId("sub"),
    name = "sub-1",
    branch = "feat/rate-limit-sub",
    parentId = id,
    placement = SessionPlacement.SameFolder,
    origin = SessionOrigin.Split,
  )
  private val sessions = FakeSessionService(listOf(session))
  private val git = FakeGitDataSource().apply {
    checkoutResult = LemonadeCodeResult.success(GitCheckout("/code/storefront", "feat/rate-limit"))
    diffResult = LemonadeCodeResult.success(
      """
      diff --git a/src/app.ts b/src/app.ts
      --- a/src/app.ts
      +++ b/src/app.ts
      @@ -1,2 +1,2 @@
       const a = 1;
      -const b = 2;
      +const b = 3;
      """.trimIndent() + "\n",
    )
    untrackedResult = LemonadeCodeResult.success(listOf("src/new.ts"))
    fileContents = mapOf("src/new.ts" to "export const fresh = true;\n")
    uncommittedResult = LemonadeCodeResult.success(true)
  }

  private val web = FakeWebImageSource()

  private fun service(scope: kotlinx.coroutines.CoroutineScope) = ReviewServiceImpl(sessions, LoadDiffUseCase(git, FakeLemonadeCodeCoroutineDispatchers()), git, scope, web)

  @Test
  fun `loads the diff with untracked files when first observed`() = runTest {
    service(backgroundScope).review(id).test {
      assertIs<DiffState.Loading>(awaitItem().diff)
      val snapshot = assertIs<DiffState.Ready>(awaitItem().diff).snapshot
      assertEquals(listOf("src/app.ts", "src/new.ts"), snapshot.files.map { it.path })
      assertEquals(FileChange.Added, snapshot.files[1].change)
      assertEquals(2 to 1, snapshot.additions to snapshot.deletions)
      assertTrue(snapshot.hasUncommittedChanges)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun `a session compares with the default branch`() = runTest {
    service(backgroundScope).review(id).test {
      val review = awaitReady()
      assertEquals("main", review.base)
      assertEquals(listOf("/code/storefront" to "main"), git.diffCalls)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun `a sub-session compares with its parent's branch`() = runTest {
    sessions.setSessions(listOf(session, subSession))
    service(backgroundScope).review(subSession.id).test {
      val review = awaitReady()
      assertEquals("feat/rate-limit", review.base)
      assertEquals(listOf("/code/storefront" to "feat/rate-limit"), git.diffCalls)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun `offers the default branch, then the parent's, then the rest as git lists them`() = runTest {
    git.branchesResult = LemonadeCodeResult.success(listOf("alpha", "feat/rate-limit", "feat/rate-limit-sub", "main"))
    sessions.setSessions(listOf(session, subSession))
    service(backgroundScope).review(subSession.id).test {
      assertEquals(listOf("main", "feat/rate-limit", "alpha", "feat/rate-limit-sub"), awaitReady().bases)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun `picking a branch reads the diff against it`() = runTest {
    val service = service(backgroundScope)
    service.review(id).test {
      awaitReady()
      service.compareWith(id, "release")
      var review = awaitItem()
      while (review.base != "release" || review.diff !is DiffState.Ready) review = awaitItem()
      assertEquals("/code/storefront" to "release", git.diffCalls.last())
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun `sending pastes the comments into the session in diff order`() = runTest {
    val service = service(backgroundScope)
    service.refresh(id)
    val _ = service.addComment(id, NewComment("src/new.ts", LineAnchor(LineSide.New, 1), LineAnchor(LineSide.New, 1), "Rename this"))
    val _ = service.addComment(id, NewComment("src/app.ts", LineAnchor(LineSide.New, 1), LineAnchor(LineSide.New, 2), "Why 3?"))

    assertEquals(2, service.sendToSession(id).getOrNull())
    val (target, message) = sessions.pasted.single()
    assertEquals(id, target)
    assertEquals(
      "Review comments on feat/rate-limit (diff against main). Please address each one:\n\n" +
        "1. src/app.ts:1–2\n   Why 3?\n\n2. src/new.ts:1\n   Rename this",
      message,
    )
    service.review(id).test {
      val comments = awaitItem().comments
      assertTrue(comments.all { it.status == CommentStatus.Sent("api-ratelimit") })
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun `handing off opens a review sub-session with the comments pasted in`() = runTest {
    val service = service(backgroundScope)
    service.refresh(id)
    val _ = service.addComment(id, NewComment("src/app.ts", LineAnchor(LineSide.New, 2), LineAnchor(LineSide.New, 2), "Use a constant"))

    val subId = service.sendToNewSubSession(id).getOrNull()
    val call = sessions.started.single()
    assertEquals(SessionService.ReviewPrefix, call.namePrefix)
    assertEquals(SessionOrigin.Review(1), call.origin)
    assertTrue(call.initialInput.orEmpty().startsWith("Address these review comments on branch feat/rate-limit. Run git diff main first"))
    service.review(id).test {
      assertEquals(CommentStatus.InSubSession(subId!!, "review-1"), awaitItem().comments.single().status)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun `nothing to send without unsent comments`() = runTest {
    val service = service(backgroundScope)
    service.refresh(id)
    assertEquals(LemonadeCodeResult.failure(dev.lemonadecode.review.api.ReviewError.NothingToSend), service.sendToSession(id))
  }

  @Test
  fun `reads both sides of a file for its preview, the base from the merge base`() = runTest {
    git.diffResult = LemonadeCodeResult.success(
      """
      diff --git a/art/old-logo.png b/art/logo.png
      similarity index 80%
      rename from art/old-logo.png
      rename to art/logo.png
      Binary files a/art/old-logo.png and b/art/logo.png differ
      """.trimIndent() + "\n",
    )
    git.untrackedResult = LemonadeCodeResult.success(emptyList())
    git.mergeBaseResult = LemonadeCodeResult.success("abc123")
    git.committedFiles = mapOf("abc123:art/old-logo.png" to byteArrayOf(1, 2, 3))
    git.workingFiles = mapOf("art/logo.png" to byteArrayOf(4, 5))
    val service = service(backgroundScope)
    service.refresh(id)

    val before = assertIs<FileContent.Bytes>(service.fileContent(id, "art/logo.png", FileVersion.Base).getOrNull())
    assertContentEquals(byteArrayOf(1, 2, 3), before.bytes)
    val after = assertIs<FileContent.Bytes>(service.fileContent(id, "art/logo.png", FileVersion.WorkingTree).getOrNull())
    assertContentEquals(byteArrayOf(4, 5), after.bytes)
  }

  @Test
  fun `an added file has no base side to preview`() = runTest {
    git.committedFiles = mapOf("base0000:src/new.ts" to byteArrayOf(9))
    val service = service(backgroundScope)
    service.refresh(id)
    assertEquals(FileContent.Missing, service.fileContent(id, "src/new.ts", FileVersion.Base).getOrNull())
    assertIs<dev.lemonadecode.review.api.ReviewError.ReadFailed>((service.fileContent(id, "nope.ts", FileVersion.Base) as LemonadeCodeResult.Failure).error)
  }

  @Test
  fun `reads any checkout file a preview refers to, on either side`() = runTest {
    git.mergeBaseResult = LemonadeCodeResult.success("abc123")
    git.committedFiles = mapOf("abc123:docs/images/hero.png" to byteArrayOf(1))
    git.workingFiles = mapOf("docs/images/hero.png" to byteArrayOf(2))
    val service = service(backgroundScope)
    service.refresh(id)

    // Not in the diff, unlike what fileContent reads.
    val before = assertIs<FileContent.Bytes>(service.checkoutFile(id, "docs/images/hero.png", FileVersion.Base).getOrNull())
    assertContentEquals(byteArrayOf(1), before.bytes)
    val after = assertIs<FileContent.Bytes>(service.checkoutFile(id, "docs/images/hero.png", FileVersion.WorkingTree).getOrNull())
    assertContentEquals(byteArrayOf(2), after.bytes)
    assertEquals(FileContent.Missing, service.checkoutFile(id, "docs/nope.png", FileVersion.WorkingTree).getOrNull())
  }

  @Test
  fun `checkout files can't be read from outside the checkout`() = runTest {
    git.workingFiles = mapOf("../secret" to byteArrayOf(1), "/etc/passwd" to byteArrayOf(1), "abc:x" to byteArrayOf(1))
    val service = service(backgroundScope)
    service.refresh(id)
    listOf("../secret", "/etc/passwd", "a/../../secret", "abc:x", "a\\b", "", "a//b").forEach { path ->
      assertIs<ReviewError.ReadFailed>((service.checkoutFile(id, path, FileVersion.WorkingTree) as LemonadeCodeResult.Failure).error, path)
    }
    assertEquals(ReviewError.UnknownSession, (service.checkoutFile(SessionId("nobody"), "a.png", FileVersion.WorkingTree) as LemonadeCodeResult.Failure).error)
  }

  @Test
  fun `web pictures are read once and kept`() = runTest {
    val url = "https://img.shields.io/badge/free"
    web.images = mapOf(url to FileContent.Bytes(byteArrayOf(7)))
    val service = service(backgroundScope)

    assertContentEquals(byteArrayOf(7), assertIs<FileContent.Bytes>(service.webImage(url).getOrNull()).bytes)
    assertContentEquals(byteArrayOf(7), assertIs<FileContent.Bytes>(service.webImage(url).getOrNull()).bytes)
    assertEquals(listOf(url), web.reads)
    assertIs<ReviewError.ReadFailed>((service.webImage("https://gone.dev/x.png") as LemonadeCodeResult.Failure).error)
    assertIs<ReviewError.ReadFailed>((service.webImage("file:///etc/passwd") as LemonadeCodeResult.Failure).error)
    assertEquals(listOf(url, "https://gone.dev/x.png"), web.reads)
  }

  /** Skips reviews still loading. */
  private suspend fun app.cash.turbine.ReceiveTurbine<Review>.awaitReady(): Review {
    var review = awaitItem()
    while (review.diff !is DiffState.Ready) review = awaitItem()
    return review
  }
}
