package dev.lemonadecode.review.fake

import dev.lemonadecode.domain.review.CommentId
import dev.lemonadecode.domain.review.CommentStatus
import dev.lemonadecode.domain.review.DiffState
import dev.lemonadecode.domain.review.FileContent
import dev.lemonadecode.domain.review.FileVersion
import dev.lemonadecode.domain.review.NewComment
import dev.lemonadecode.domain.review.Review
import dev.lemonadecode.domain.review.ReviewComment
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.review.api.ReviewError
import dev.lemonadecode.review.api.ReviewService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** In-memory [ReviewService]: set a review per session, read what was asked of it. */
public class FakeReviewService : ReviewService {
  private val reviews = MutableStateFlow<Map<SessionId, Review>>(emptyMap())

  public val refreshed: MutableList<SessionId> = mutableListOf()
  public val comparedWith: MutableList<Pair<SessionId, String>> = mutableListOf()
  public val sentToSession: MutableList<SessionId> = mutableListOf()
  public val sentToSubSession: MutableList<SessionId> = mutableListOf()
  public var subSessionResult: LemonadeCodeResult<SessionId, ReviewError> = LemonadeCodeResult.success(SessionId("review-sub"))
  private var nextId = 0

  /** What [fileContent] returns per path and side; anything not listed is [FileContent.Missing]. */
  public var fileContents: Map<Pair<String, FileVersion>, LemonadeCodeResult<FileContent, ReviewError>> = emptyMap()
  public val fileContentCalls: MutableList<Pair<String, FileVersion>> = mutableListOf()

  public fun setReview(sessionId: SessionId, review: Review) {
    reviews.update { it + (sessionId to review) }
  }

  public fun reviewOf(sessionId: SessionId): Review = reviews.value[sessionId] ?: Review(DiffState.Loading, emptyList())

  override fun review(sessionId: SessionId): Flow<Review> = reviews.map { it[sessionId] ?: Review(DiffState.Loading, emptyList()) }

  override suspend fun refresh(sessionId: SessionId) {
    refreshed += sessionId
  }

  override suspend fun compareWith(sessionId: SessionId, branch: String) {
    comparedWith += sessionId to branch
  }

  override fun addComment(sessionId: SessionId, comment: NewComment): CommentId {
    val id = CommentId("fake-${nextId++}")
    val created = ReviewComment(id, comment.filePath, comment.start, comment.end, comment.body, CommentStatus.Unsent)
    setReview(sessionId, reviewOf(sessionId).let { it.copy(comments = it.comments + created) })
    return id
  }

  override fun deleteComment(sessionId: SessionId, commentId: CommentId) {
    setReview(sessionId, reviewOf(sessionId).let { review -> review.copy(comments = review.comments.filterNot { it.id == commentId }) })
  }

  override fun pendingMessage(sessionId: SessionId): String? = reviewOf(sessionId).unsent.takeIf { it.isNotEmpty() }?.joinToString("\n") { "${it.filePath}:${it.start.line} ${it.body}" }

  override suspend fun sendToSession(sessionId: SessionId): LemonadeCodeResult<Int, ReviewError> {
    sentToSession += sessionId
    val unsent = reviewOf(sessionId).unsent
    if (unsent.isEmpty()) return LemonadeCodeResult.failure(ReviewError.NothingToSend)
    setReview(sessionId, reviewOf(sessionId).let { r -> r.copy(comments = r.comments.map { it.copy(status = CommentStatus.Sent("terminal")) }) })
    return LemonadeCodeResult.success(unsent.size)
  }

  override suspend fun sendToNewSubSession(sessionId: SessionId): LemonadeCodeResult<SessionId, ReviewError> {
    sentToSubSession += sessionId
    return subSessionResult
  }

  override suspend fun fileContent(
    sessionId: SessionId,
    path: String,
    version: FileVersion,
  ): LemonadeCodeResult<FileContent, ReviewError> {
    fileContentCalls += path to version
    return fileContents[path to version] ?: LemonadeCodeResult.success(FileContent.Missing)
  }
}
