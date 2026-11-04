package dev.lemonadecode.workspace.uimodel

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.lemonadecode.platform.localization.LemonadeCodeString

@Immutable
public data class DiffReviewModel(
  val toolbar: DiffToolbarModel,
  val files: List<DiffFileModel>,
  val sendBar: SendBarModel,
  /** Reads what previews refer to, like the pictures in a README. */
  val assets: PreviewAssetLoader = PreviewAssetLoader.None,
  /** The changed files beside the diff: shown, hidden, or (null) shown when the window is wide enough. */
  val fileList: Boolean? = null,
)

/**
 * Reads the files a preview refers to, like the pictures in a README, for the session whose diff
 * is shown. [None] reads nothing, so pictures show their alt text instead.
 */
@Stable
public interface PreviewAssetLoader {
  /** [path], from the checkout's root, as it is on the [version] side of the change; null when it can't be read. */
  public suspend fun checkoutFile(path: String, version: PreviewVersion): ByteArray?

  /** A picture on the web, like a README's badges; null when it can't be fetched. */
  public suspend fun webImage(url: String): ByteArray?

  public companion object {
    public val None: PreviewAssetLoader = object : PreviewAssetLoader {
      override suspend fun checkoutFile(path: String, version: PreviewVersion): ByteArray? = null

      override suspend fun webImage(url: String): ByteArray? = null
    }
  }
}

/** The side of a change a preview draws. */
public enum class PreviewVersion { Before, After }

/** The bar over the diff: what is compared with which branch, and a summary. */
@Immutable
public data class DiffToolbarModel(
  val sourceLabel: LemonadeCodeString,
  val base: DiffBaseModel,
  /** e.g. "includes uncommitted changes · 6 files"; null while there is no diff to sum up. */
  val summary: LemonadeCodeString? = null,
)

/**
 * The branch the diff compares with, and the picker to change it. [selected] is null until the
 * branch is known; [options] are in the order to offer them.
 */
@Immutable
public data class DiffBaseModel(
  val selected: String?,
  val options: List<BaseBranchModel>,
  val pickerOpen: Boolean = false,
)

/** A branch the diff can compare with, e.g. `main` · "Default branch · updated 2 h ago". */
@Immutable
public data class BaseBranchModel(
  val name: String,
  val detail: LemonadeCodeString? = null,
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
  /** The rendered view of the file; null when Lemonade Code can't draw this kind of file. */
  val preview: FilePreviewModel? = null,
)

public enum class FileChangeKind { Added, Modified, Deleted, Renamed }

/** A file drawn as what it is (picture, model, document, table, video, sound) instead of its text diff. */
@Immutable
public data class FilePreviewModel(
  val kind: PreviewKindModel,
  /** The preview replaces the diff rows while this is on. */
  val showing: Boolean,
  /** Which sides are shown; null when the file only has one, like an added or deleted file. */
  val layout: PreviewLayout?,
  val content: FilePreviewContentModel,
)

public enum class PreviewKindModel { Image, Vector, Model, Markdown, Table, Video, Audio }

public enum class PreviewLayout { Before, After, SideBySide }

@Immutable
public sealed interface FilePreviewContentModel {
  @Immutable
  public data object Loading : FilePreviewContentModel

  @Immutable
  public data class Failed(val message: LemonadeCodeString) : FilePreviewContentModel

  /** Each side is null when the file doesn't exist there. */
  @Immutable
  public data class Ready(
    val before: PreviewSideModel?,
    val after: PreviewSideModel?,
  ) : FilePreviewContentModel
}

@Immutable
public sealed interface PreviewSideModel {
  public val sizeBytes: Long

  /** The file's bytes; [extension] (lowercase, no dot) tells the format apart, e.g. `stl`. */
  @Immutable
  public class Content(
    public val bytes: ByteArray,
    public val extension: String,
  ) : PreviewSideModel {
    override val sizeBytes: Long get() = bytes.size.toLong()

    override fun equals(other: Any?): Boolean = other is Content && other.bytes === bytes && other.extension == extension

    override fun hashCode(): Int = 31 * bytes.hashCode() + extension.hashCode()
  }

  @Immutable
  public data class TooLarge(override val sizeBytes: Long) : PreviewSideModel
}

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
  val authorName: LemonadeCodeString,
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
  val title: LemonadeCodeString,
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
