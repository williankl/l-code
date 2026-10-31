package dev.loupe.review.impl

import app.cash.turbine.test
import dev.loupe.data.git.GitCheckout
import dev.loupe.data.git.fake.FakeGitDataSource
import dev.loupe.domain.review.CommentStatus
import dev.loupe.domain.review.DiffState
import dev.loupe.domain.review.FileChange
import dev.loupe.domain.review.LineAnchor
import dev.loupe.domain.review.LineSide
import dev.loupe.domain.review.NewComment
import dev.loupe.domain.session.Session
import dev.loupe.domain.session.SessionId
import dev.loupe.domain.session.SessionOrigin
import dev.loupe.domain.session.SessionPlacement
import dev.loupe.domain.session.SessionStatus
import dev.loupe.platform.architecture.testing.FakeLoupeCoroutineDispatchers
import dev.loupe.platform.core.LoupeResult
import dev.loupe.platform.core.getOrNull
import dev.loupe.sessions.api.SessionService
import dev.loupe.sessions.fake.FakeSessionService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
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
  private val sessions = FakeSessionService(listOf(session))
  private val git = FakeGitDataSource().apply {
    checkoutResult = LoupeResult.success(GitCheckout("/code/storefront", "feat/rate-limit"))
    diffResult = LoupeResult.success(
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
    untrackedResult = LoupeResult.success(listOf("src/new.ts"))
    fileContents = mapOf("src/new.ts" to "export const fresh = true;\n")
    uncommittedResult = LoupeResult.success(true)
  }

  private fun service(scope: kotlinx.coroutines.CoroutineScope) = ReviewServiceImpl(sessions, LoadDiffUseCase(git, FakeLoupeCoroutineDispatchers()), scope)

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
    assertEquals(LoupeResult.failure(dev.loupe.review.api.ReviewError.NothingToSend), service.sendToSession(id))
  }
}
