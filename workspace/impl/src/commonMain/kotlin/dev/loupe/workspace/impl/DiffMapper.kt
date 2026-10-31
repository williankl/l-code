package dev.loupe.workspace.impl

import dev.loupe.domain.review.CodeTokenKind
import dev.loupe.domain.review.CommentStatus
import dev.loupe.domain.review.DiffLine
import dev.loupe.domain.review.DiffLineKind
import dev.loupe.domain.review.DiffSnapshot
import dev.loupe.domain.review.DiffState
import dev.loupe.domain.review.FileChange
import dev.loupe.domain.review.FileDiff
import dev.loupe.domain.review.LineAnchor
import dev.loupe.domain.review.LineSide
import dev.loupe.domain.review.Review
import dev.loupe.domain.review.ReviewComment
import dev.loupe.platform.localization.asLoupeString
import dev.loupe.workspace.strings.Res
import dev.loupe.workspace.strings.comment_author_you
import dev.loupe.workspace.strings.comment_on_line
import dev.loupe.workspace.strings.diff_source_working_tree
import dev.loupe.workspace.strings.diff_summary
import dev.loupe.workspace.strings.diff_summary_uncommitted
import dev.loupe.workspace.strings.message_diff_failed
import dev.loupe.workspace.strings.message_loading_diff
import dev.loupe.workspace.strings.message_no_base
import dev.loupe.workspace.strings.message_no_base_detail
import dev.loupe.workspace.strings.message_no_changes
import dev.loupe.workspace.strings.message_not_repo
import dev.loupe.workspace.strings.message_not_repo_detail
import dev.loupe.workspace.uimodel.CodeTokenModel
import dev.loupe.workspace.uimodel.CommentComposerModel
import dev.loupe.workspace.uimodel.CommentStatusModel
import dev.loupe.workspace.uimodel.DiffFileModel
import dev.loupe.workspace.uimodel.DiffReviewModel
import dev.loupe.workspace.uimodel.DiffRowModel
import dev.loupe.workspace.uimodel.DiffStatModel
import dev.loupe.workspace.uimodel.FileChangeKind
import dev.loupe.workspace.uimodel.ReviewCommentModel
import dev.loupe.workspace.uimodel.SendBarModel
import dev.loupe.workspace.uimodel.SendBarSummary
import dev.loupe.workspace.uimodel.WorkspaceContentModel
import dev.loupe.workspace.uimodel.CodeTokenKind as TokenKindModel
import dev.loupe.workspace.uimodel.DiffLineKind as LineKindModel

/** Diff review: hunks, comments, the composer and the send bar. */
internal object DiffMapper {
  private const val Context = 3

  /** Files longer than this start collapsed, so a huge generated file doesn't swamp the review. */
  private const val AutoExpandLimit = 1_500

  private val hunkContext = Regex("^(export |function |class |interface |fun |def |func |public |private |internal |#)")

  fun content(review: Review?, view: WorkspaceViewState, pendingMessage: String?): WorkspaceContentModel = when (val diff = review?.diff ?: DiffState.Loading) {
    DiffState.Loading -> WorkspaceContentModel.Message(Res.string.message_loading_diff.asLoupeString(), loading = true)
    DiffState.NotARepository -> WorkspaceContentModel.Message(
      Res.string.message_not_repo.asLoupeString(),
      Res.string.message_not_repo_detail.asLoupeString(),
    )
    is DiffState.NoBaseBranch -> WorkspaceContentModel.Message(
      Res.string.message_no_base.asLoupeString(diff.baseRef),
      Res.string.message_no_base_detail.asLoupeString(),
    )
    is DiffState.Failed -> WorkspaceContentModel.Message(Res.string.message_diff_failed.asLoupeString(), diff.message.asLoupeString())
    is DiffState.Ready -> if (diff.snapshot.files.isEmpty()) {
      WorkspaceContentModel.Message(Res.string.message_no_changes.asLoupeString(diff.snapshot.baseRef))
    } else {
      WorkspaceContentModel.Diff(review(diff.snapshot, review?.comments.orEmpty(), view, pendingMessage))
    }
  }

  fun stat(review: Review?): DiffStatModel? {
    val snapshot = (review?.diff as? DiffState.Ready)?.snapshot ?: return null
    if (snapshot.files.isEmpty()) return null
    return DiffStatModel(additions = snapshot.additions, deletions = snapshot.deletions)
  }

  fun baseRef(review: Review?): String = (review?.diff as? DiffState.Ready)?.snapshot?.baseRef ?: "main"

  fun isExpanded(file: FileDiff, view: WorkspaceViewState): Boolean = view.fileExpansion[file.path] ?: (file.change != FileChange.Deleted && !file.isBinary && file.lines.size <= AutoExpandLimit)

  fun rangeText(start: LineAnchor, end: LineAnchor): String = if (start.line == end.line) "${start.line}" else "${start.line}–${end.line}"

  fun anchorOf(line: DiffLine): LineAnchor = line.newNumber?.let { LineAnchor(LineSide.New, it) } ?: LineAnchor(LineSide.Old, line.oldNumber ?: 0)

  private fun review(
    snapshot: DiffSnapshot,
    comments: List<ReviewComment>,
    view: WorkspaceViewState,
    pendingMessage: String?,
  ): DiffReviewModel {
    val unsent = comments.filter { it.status == CommentStatus.Unsent }
    val fileCount = snapshot.files.size
    return DiffReviewModel(
      sourceLabel = Res.string.diff_source_working_tree.asLoupeString(),
      targetLabel = snapshot.baseRef,
      summary = if (snapshot.hasUncommittedChanges) {
        Res.plurals.diff_summary_uncommitted.asLoupeString(fileCount)
      } else {
        Res.plurals.diff_summary.asLoupeString(fileCount)
      },
      files = snapshot.files.map { file(it, comments.filter { comment -> comment.filePath == it.path }, view) },
      sendBar = SendBarModel(
        summary = when {
          unsent.isNotEmpty() -> SendBarSummary.Unsent(unsent.size, unsent.map { it.filePath }.distinct().size)
          comments.isNotEmpty() -> SendBarSummary.AllDelivered
          else -> SendBarSummary.NoComments
        },
        canSend = unsent.isNotEmpty(),
        preview = pendingMessage?.takeIf { view.previewOpen && unsent.isNotEmpty() },
      ),
    )
  }

  private fun file(file: FileDiff, comments: List<ReviewComment>, view: WorkspaceViewState): DiffFileModel {
    val expanded = isExpanded(file, view)
    val full = file.path in view.fullFiles
    val fileName = file.path.substringAfterLast('/')
    val selection = (view.dragging ?: view.composer?.range)?.takeIf { it.filePath == file.path }
    val composer = view.composer?.takeIf { it.range.filePath == file.path && view.dragging == null }
    fun line(index: Int): DiffRowModel.Line {
      val line = file.lines[index]
      val anchor = anchorOf(line)
      return DiffRowModel.Line(
        key = "${file.path}:${if (anchor.side == LineSide.New) "n" else "o"}${anchor.line}",
        index = index,
        oldNumber = line.oldNumber,
        newNumber = line.newNumber,
        kind = line.kind.toModel(),
        tokens = line.tokens.map { CodeTokenModel(it.text, it.kind.toModel()) },
        selected = selection != null && index in selection.first..selection.last,
        isSelectionAnchor = selection?.first == index,
        comments = comments.filter { it.end == anchor }.map { comment(it, fileName) },
        composer = composer?.takeIf { it.range.last == index }?.let {
          val range = rangeText(anchorOf(file.lines[it.range.first]), anchorOf(file.lines[it.range.last]))
          CommentComposerModel(
            title = Res.string.comment_on_line.asLoupeString("${file.path}:$range"),
            draft = it.draft,
            canSubmit = it.draft.isNotBlank(),
          )
        },
      )
    }
    val rows = when {
      !expanded || file.isBinary -> emptyList()
      full -> file.lines.indices.map(::line)
      else -> hunkedRows(file, ::line)
    }
    return DiffFileModel(
      id = file.path,
      directory = file.path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" },
      fileName = fileName,
      path = file.path,
      change = when (file.change) {
        FileChange.Added -> FileChangeKind.Added
        FileChange.Modified -> FileChangeKind.Modified
        FileChange.Deleted -> FileChangeKind.Deleted
        FileChange.Renamed -> FileChangeKind.Renamed
      },
      language = file.language,
      additions = file.additions,
      deletions = file.deletions,
      commentCount = comments.size,
      expanded = expanded && !file.isBinary,
      showingFullFile = full,
      canShowFullFile = file.change == FileChange.Modified || file.change == FileChange.Renamed,
      rows = rows,
    )
  }

  /** Changed lines with three lines of context, merged where they touch, like `git diff`. */
  private fun hunkedRows(file: FileDiff, line: (Int) -> DiffRowModel.Line): List<DiffRowModel> {
    val lines = file.lines
    val ranges = mutableListOf<IntArray>()
    lines.forEachIndexed { index, diffLine ->
      if (diffLine.kind == DiffLineKind.Context) return@forEachIndexed
      val start = maxOf(0, index - Context)
      val end = minOf(lines.lastIndex, index + Context)
      val last = ranges.lastOrNull()
      if (last != null && start <= last[1] + 1) last[1] = maxOf(last[1], end) else ranges += intArrayOf(start, end)
    }
    if (ranges.isEmpty()) return lines.indices.map(line)
    var previousEnd = -1
    return buildList {
      ranges.forEach { (start, end) ->
        val segment = lines.subList(start, end + 1)
        val olds = segment.filter { it.oldNumber != null }
        val news = segment.filter { it.newNumber != null }
        val context = (start - 1 downTo 0).map { lines[it].text }.firstOrNull { hunkContext.containsMatchIn(it) }
        val header = "@@ -${olds.firstOrNull()?.oldNumber ?: 0},${olds.size} +${news.firstOrNull()?.newNumber ?: 0},${news.size} @@" +
          (context?.let { "  $it" } ?: "")
        add(DiffRowModel.Hunk(key = "h$start", header = header, hiddenLineCount = start - (previousEnd + 1)))
        (start..end).forEach { add(line(it)) }
        previousEnd = end
      }
      val below = lines.lastIndex - previousEnd
      if (below > 0) add(DiffRowModel.FileTail(key = "below", hiddenLineCount = below))
    }
  }

  private fun comment(comment: ReviewComment, fileName: String) = ReviewCommentModel(
    id = comment.id.value,
    authorName = Res.string.comment_author_you.asLoupeString(),
    authorInitials = "YO",
    location = "$fileName:${rangeText(comment.start, comment.end)}",
    text = comment.body,
    status = when (val status = comment.status) {
      CommentStatus.Unsent -> CommentStatusModel.Unsent
      is CommentStatus.Sent -> CommentStatusModel.Sent(status.sessionName)
      is CommentStatus.InSubSession -> CommentStatusModel.InSubSession(status.sessionName)
    },
    canDelete = comment.status == CommentStatus.Unsent,
  )

  private fun DiffLineKind.toModel(): LineKindModel = when (this) {
    DiffLineKind.Context -> LineKindModel.Context
    DiffLineKind.Added -> LineKindModel.Added
    DiffLineKind.Removed -> LineKindModel.Removed
  }

  private fun CodeTokenKind.toModel(): TokenKindModel = when (this) {
    CodeTokenKind.Plain -> TokenKindModel.Plain
    CodeTokenKind.Keyword -> TokenKindModel.Keyword
    CodeTokenKind.String -> TokenKindModel.String
    CodeTokenKind.Number -> TokenKindModel.Number
    CodeTokenKind.Function -> TokenKindModel.Function
    CodeTokenKind.Type -> TokenKindModel.Type
    CodeTokenKind.Comment -> TokenKindModel.Comment
    CodeTokenKind.Punctuation -> TokenKindModel.Punctuation
    CodeTokenKind.Heading -> TokenKindModel.Heading
  }
}
