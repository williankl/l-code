package dev.loupe.workspace.uimodel

import androidx.compose.runtime.Immutable
import dev.loupe.platform.localization.LoupeString

@Immutable
public data class DiffReviewModel(
  val sourceLabel: LoupeString,
  val targetLabel: String,
  /** e.g. "includes uncommitted changes · 6 files". */
  val summary: LoupeString,
  val files: List<DiffFileModel>,
  val sendBar: SendBarModel,
)

@Immutable
public data class DiffFileModel(
  val id: String,
  /** Directory part with trailing slash, e.g. `src/middleware/`. */
  val directory: String,
  val fileName: String,
  val path: String,
  val change: FileChangeKind,
  val language: String,
  val additions: Int,
  val deletions: Int,
  val commentCount: Int,
  val expanded: Boolean,
  val showingFullFile: Boolean,
  /** Only modified files can switch between hunks and the full file. */
  val canShowFullFile: Boolean,
  val rows: List<DiffRowModel>,
)

public enum class FileChangeKind { Added, Modified, Deleted, Renamed }

@Immutable
public sealed interface DiffRowModel {
  public val key: String

  @Immutable
  public data class Hunk(
    override val key: String,
    val header: String,
    val hiddenLineCount: Int,
  ) : DiffRowModel

  @Immutable
  public data class Line(
    override val key: String,
    /** Position of this line in the file's rows; used to address comment ranges. */
    val index: Int,
    val oldNumber: Int?,
    val newNumber: Int?,
    val kind: DiffLineKind,
    val tokens: List<CodeTokenModel>,
    /** Inside the range being commented on; drawn with a lime edge. */
    val selected: Boolean,
    /** First line of that range; its + button stays visible. */
    val isSelectionAnchor: Boolean,
    /** Comments whose range ends on this line. */
    val comments: List<ReviewCommentModel>,
    /** The open composer, shown under the last selected line. */
    val composer: CommentComposerModel?,
  ) : DiffRowModel

  /** "N unchanged lines below · show full file". */
  @Immutable
  public data class FileTail(
    override val key: String,
    val hiddenLineCount: Int,
  ) : DiffRowModel
}

public enum class DiffLineKind { Context, Added, Removed }

@Immutable
public data class CodeTokenModel(
  val text: String,
  val kind: CodeTokenKind,
)

public enum class CodeTokenKind { Plain, Keyword, String, Number, Function, Type, Comment, Punctuation, Heading }

@Immutable
public data class ReviewCommentModel(
  val id: String,
  val authorName: LoupeString,
  val authorInitials: String,
  /** e.g. `rateLimit.ts:28–32`. */
  val location: String,
  val text: String,
  val status: CommentStatusModel,
  val canDelete: Boolean,
)

@Immutable
public sealed interface CommentStatusModel {
  @Immutable
  public data object Unsent : CommentStatusModel

  @Immutable
  public data class Sent(val sessionName: String) : CommentStatusModel

  @Immutable
  public data class InSubSession(val sessionName: String) : CommentStatusModel
}

@Immutable
public data class CommentComposerModel(
  /** e.g. "Comment on src/middleware/rateLimit.ts:28–32". */
  val title: LoupeString,
  val draft: String,
  val canSubmit: Boolean,
)

@Immutable
public data class SendBarModel(
  val summary: SendBarSummary,
  val canSend: Boolean,
  /** The exact text that will be pasted; non-null while the preview is open. */
  val preview: String?,
)

@Immutable
public sealed interface SendBarSummary {
  @Immutable
  public data object NoComments : SendBarSummary

  @Immutable
  public data class Unsent(val commentCount: Int, val fileCount: Int) : SendBarSummary

  @Immutable
  public data object AllDelivered : SendBarSummary
}
