package dev.lemonadecode.domain.review

import dev.lemonadecode.domain.session.SessionId
import kotlin.jvm.JvmInline

@JvmInline
public value class CommentId(public val value: String)

public enum class LineSide { New, Old }

public data class LineAnchor(
  val side: LineSide,
  val line: Int,
)

/** A comment on one line or a range, addressed to whoever works on the change next. */
public data class ReviewComment(
  val id: CommentId,
  val filePath: String,
  val start: LineAnchor,
  val end: LineAnchor,
  val body: String,
  val status: CommentStatus,
) {
  val isRange: Boolean get() = start != end
}

public sealed interface CommentStatus {
  public data object Unsent : CommentStatus

  public data class Sent(val sessionName: String) : CommentStatus

  public data class InSubSession(val sessionId: SessionId, val sessionName: String) : CommentStatus
}

/** Everything under review in one working folder. */
public data class Review(
  val diff: DiffState,
  val comments: List<ReviewComment>,
  /** The branch the diff compares with; null until it is known. */
  val base: String? = null,
  /**
   * The branches the diff can compare with, in the order to offer them: the default branch, the
   * parent session's branch, then the rest as git lists them.
   */
  val bases: List<String> = emptyList(),
) {
  val unsent: List<ReviewComment> get() = comments.filter { it.status == CommentStatus.Unsent }
}

public data class NewComment(
  val filePath: String,
  val start: LineAnchor,
  val end: LineAnchor,
  val body: String,
)
