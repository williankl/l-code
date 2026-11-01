package dev.lemonadecode.review.api

import dev.lemonadecode.domain.review.CommentId
import dev.lemonadecode.domain.review.FileContent
import dev.lemonadecode.domain.review.FileVersion
import dev.lemonadecode.domain.review.NewComment
import dev.lemonadecode.domain.review.Review
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.platform.core.LemonadeCodeResult
import kotlinx.coroutines.flow.Flow

/**
 * The diff and review comments of each session's working folder. Sessions that share a folder
 * share their comments, so a comment made in one shows in the other.
 *
 * Each session's diff compares with a branch of its own: its parent session's branch for a
 * sub-session, else the default branch, until [compareWith] picks another.
 */
public interface ReviewService {
  /** Live review of [sessionId]'s folder; the diff loads when first collected. */
  public fun review(sessionId: SessionId): Flow<Review>

  /** Re-reads the diff from git. */
  public suspend fun refresh(sessionId: SessionId)

  /** Compares [sessionId]'s diff with [branch] from now on, and reads it. */
  public suspend fun compareWith(sessionId: SessionId, branch: String)

  public fun addComment(sessionId: SessionId, comment: NewComment): CommentId?

  public fun deleteComment(sessionId: SessionId, commentId: CommentId)

  /** The text pasted for the unsent comments, or null when there are none. */
  public fun pendingMessage(sessionId: SessionId): String?

  /**
   * Pastes the unsent comments into the session's terminal, for whatever runs there, without
   * pressing Enter. Returns how many were sent.
   */
  public suspend fun sendToSession(sessionId: SessionId): LemonadeCodeResult<Int, ReviewError>

  /** Opens a sub-session in the same folder with the unsent comments pasted in. */
  public suspend fun sendToNewSubSession(sessionId: SessionId): LemonadeCodeResult<SessionId, ReviewError>

  /**
   * One side of a changed file in the loaded diff, for previews. The base side of a renamed file
   * is read from its old path; added files have no base and deleted files no working copy.
   */
  public suspend fun fileContent(
    sessionId: SessionId,
    path: String,
    version: FileVersion,
  ): LemonadeCodeResult<FileContent, ReviewError>

  public companion object {
    /** Files bigger than this aren't read for previews. */
    public const val MaxPreviewBytes: Long = 64L * 1024 * 1024
  }
}

public sealed interface ReviewError {
  public data object NothingToSend : ReviewError

  public data object UnknownSession : ReviewError

  public data class DeliveryFailed(val message: String) : ReviewError

  /** A file couldn't be read for a preview. */
  public data class ReadFailed(val message: String) : ReviewError
}
