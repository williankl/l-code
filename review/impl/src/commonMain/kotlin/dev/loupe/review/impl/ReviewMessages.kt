package dev.loupe.review.impl

import dev.loupe.domain.review.LineSide
import dev.loupe.domain.review.ReviewComment

/** The review text pasted into terminals. Ranges read `path:28–32` everywhere. */
internal object ReviewMessages {
  fun forSession(comments: List<ReviewComment>, branch: String?, baseRef: String): String = "Review comments on ${branch ?: "this branch"} (diff against $baseRef). Please address each one:\n\n" + items(comments)

  fun forSubSession(comments: List<ReviewComment>, branch: String?, baseRef: String): String = "Address these review comments on branch ${branch ?: "HEAD"}. Run git diff $baseRef first to see the full change " +
    "under review.\n\n" + items(comments)

  fun location(comment: ReviewComment): String {
    val range = if (comment.isRange && comment.end.line != comment.start.line) {
      "${comment.start.line}–${comment.end.line}"
    } else {
      "${comment.start.line}"
    }
    val side = if (comment.start.side == LineSide.Old) " (removed lines)" else ""
    return "${comment.filePath}:$range$side"
  }

  private fun items(comments: List<ReviewComment>): String = comments.mapIndexed { index, comment ->
    "${index + 1}. ${location(comment)}\n   ${comment.body.replace("\n", "\n   ")}"
  }.joinToString("\n\n")
}
