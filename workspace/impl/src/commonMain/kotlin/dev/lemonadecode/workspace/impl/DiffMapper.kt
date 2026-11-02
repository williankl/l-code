package dev.lemonadecode.workspace.impl

import dev.lemonadecode.domain.review.CodeTokenKind
import dev.lemonadecode.domain.review.CommentStatus
import dev.lemonadecode.domain.review.DiffLine
import dev.lemonadecode.domain.review.DiffLineKind
import dev.lemonadecode.domain.review.DiffSnapshot
import dev.lemonadecode.domain.review.DiffState
import dev.lemonadecode.domain.review.FileChange
import dev.lemonadecode.domain.review.FileContent
import dev.lemonadecode.domain.review.FileDiff
import dev.lemonadecode.domain.review.LineAnchor
import dev.lemonadecode.domain.review.LineSide
import dev.lemonadecode.domain.review.PreviewKind
import dev.lemonadecode.domain.review.Review
import dev.lemonadecode.domain.review.ReviewComment
import dev.lemonadecode.platform.localization.asLemonadeCodeString
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.comment_author_you
import dev.lemonadecode.workspace.strings.comment_on_line
import dev.lemonadecode.workspace.strings.diff_source_working_tree
import dev.lemonadecode.workspace.strings.diff_summary
import dev.lemonadecode.workspace.strings.diff_summary_uncommitted
import dev.lemonadecode.workspace.strings.message_diff_failed
import dev.lemonadecode.workspace.strings.message_loading_diff
import dev.lemonadecode.workspace.strings.message_no_base
import dev.lemonadecode.workspace.strings.message_no_base_detail
import dev.lemonadecode.workspace.strings.message_no_changes
import dev.lemonadecode.workspace.strings.message_not_repo
import dev.lemonadecode.workspace.strings.message_not_repo_detail
import dev.lemonadecode.workspace.uimodel.CodeTokenModel
import dev.lemonadecode.workspace.uimodel.CommentComposerModel
import dev.lemonadecode.workspace.uimodel.CommentStatusModel
import dev.lemonadecode.workspace.uimodel.DiffBaseModel
import dev.lemonadecode.workspace.uimodel.DiffFileModel
import dev.lemonadecode.workspace.uimodel.DiffReviewModel
import dev.lemonadecode.workspace.uimodel.DiffRowModel
import dev.lemonadecode.workspace.uimodel.DiffStatModel
import dev.lemonadecode.workspace.uimodel.DiffToolbarModel
import dev.lemonadecode.workspace.uimodel.FileChangeKind
import dev.lemonadecode.workspace.uimodel.FilePreviewContentModel
import dev.lemonadecode.workspace.uimodel.FilePreviewModel
import dev.lemonadecode.workspace.uimodel.PreviewAssetLoader
import dev.lemonadecode.workspace.uimodel.PreviewKindModel
import dev.lemonadecode.workspace.uimodel.PreviewLayout
import dev.lemonadecode.workspace.uimodel.PreviewSideModel
import dev.lemonadecode.workspace.uimodel.ReviewCommentModel
import dev.lemonadecode.workspace.uimodel.SendBarModel
import dev.lemonadecode.workspace.uimodel.SendBarSummary
import dev.lemonadecode.workspace.uimodel.WorkspaceContentModel
import dev.lemonadecode.workspace.uimodel.WorkspaceView
import dev.lemonadecode.workspace.uimodel.CodeTokenKind as TokenKindModel
import dev.lemonadecode.workspace.uimodel.DiffLineKind as LineKindModel

/** Diff review: hunks, comments, the composer and the send bar. */
internal object DiffMapper {
  private const val Context = 3

  /** Files longer than this start collapsed, so a huge generated file doesn't swamp the review. */
  private const val AutoExpandLimit = 1_500

  private val hunkContext = Regex("^(export |function |class |interface |fun |def |func |public |private |internal |#)")

  fun content(
    review: Review?,
    view: WorkspaceViewState,
    pendingMessage: String?,
    previews: Map<String, PreviewLoad> = emptyMap(),
    assets: PreviewAssetLoader = PreviewAssetLoader.None,
  ): WorkspaceContentModel {
    val toolbar = toolbar(review, view)
    return when (val diff = review?.diff ?: DiffState.Loading) {
      DiffState.Loading -> WorkspaceContentModel.Message(Res.string.message_loading_diff.asLemonadeCodeString(), loading = true, diffToolbar = toolbar)
      DiffState.NotARepository -> WorkspaceContentModel.Message(
        Res.string.message_not_repo.asLemonadeCodeString(),
        Res.string.message_not_repo_detail.asLemonadeCodeString(),
      )
      is DiffState.NoBaseBranch -> WorkspaceContentModel.Message(
        Res.string.message_no_base.asLemonadeCodeString(diff.baseRef),
        Res.string.message_no_base_detail.asLemonadeCodeString(),
        diffToolbar = toolbar,
      )
      is DiffState.Failed -> WorkspaceContentModel.Message(Res.string.message_diff_failed.asLemonadeCodeString(), diff.message.asLemonadeCodeString(), diffToolbar = toolbar)
      is DiffState.Ready -> if (diff.snapshot.files.isEmpty()) {
        WorkspaceContentModel.Message(Res.string.message_no_changes.asLemonadeCodeString(diff.snapshot.baseRef), diffToolbar = toolbar)
      } else {
        WorkspaceContentModel.Diff(review(diff.snapshot, review?.comments.orEmpty(), toolbar, view, pendingMessage, previews, assets))
      }
    }
  }

  /** The bar over the diff, without a summary; [review] adds it once there are files. */
  private fun toolbar(review: Review?, view: WorkspaceViewState): DiffToolbarModel = DiffToolbarModel(
    sourceLabel = Res.string.diff_source_working_tree.asLemonadeCodeString(),
    base = DiffBaseModel(
      selected = review?.base,
      options = review?.bases.orEmpty(),
      pickerOpen = view.basePickerOpen,
    ),
  )

  fun stat(review: Review?): DiffStatModel? {
    val snapshot = (review?.diff as? DiffState.Ready)?.snapshot ?: return null
    if (snapshot.files.isEmpty()) return null
    return DiffStatModel(additions = snapshot.additions, deletions = snapshot.deletions)
  }

  /**
   * Files Lemonade Code can draw show their preview unless the user picked the text diff, or they have
   * [commented] lines: comments sit on lines, which only the text diff shows.
   */
  fun isShowingPreview(file: FileDiff, view: WorkspaceViewState, commented: Boolean): Boolean = file.previewKind != null && (view.previewChoices[file.path] ?: !commented)

  /** Open by default: a preview, or a text diff that isn't too long to read. */
  fun isExpanded(file: FileDiff, view: WorkspaceViewState, commented: Boolean): Boolean = view.fileExpansion[file.path]
    ?: (isShowingPreview(file, view, commented) || (file.change != FileChange.Deleted && !file.isBinary && file.lines.size <= AutoExpandLimit))

  /** Whether the file's body shows: a binary file only has one when its preview is on. */
  fun isBodyVisible(file: FileDiff, view: WorkspaceViewState, commented: Boolean): Boolean = isExpanded(file, view, commented) && (!file.isBinary || isShowingPreview(file, view, commented))

  /** Files whose preview is on screen in the diff, so their contents are needed. */
  fun openPreviews(review: Review?, view: WorkspaceViewState): Set<String> {
    if (view.view != WorkspaceView.Diff) return emptySet()
    val snapshot = (review?.diff as? DiffState.Ready)?.snapshot ?: return emptySet()
    val commented = review.comments.mapTo(HashSet()) { it.filePath }
    return snapshot.files
      .filter { file -> (file.path in commented).let { isShowingPreview(file, view, it) && isBodyVisible(file, view, it) } }
      .mapTo(HashSet()) { it.path }
  }

  /** Images and models compare best next to each other; documents and tables read best alone. */
  fun defaultLayout(kind: PreviewKind): PreviewLayout = when (kind) {
    PreviewKind.Image, PreviewKind.Vector, PreviewKind.Model -> PreviewLayout.SideBySide
    PreviewKind.Markdown, PreviewKind.Table -> PreviewLayout.After
  }

  fun rangeText(start: LineAnchor, end: LineAnchor): String = if (start.line == end.line) "${start.line}" else "${start.line}–${end.line}"

  fun anchorOf(line: DiffLine): LineAnchor = line.newNumber?.let { LineAnchor(LineSide.New, it) } ?: LineAnchor(LineSide.Old, line.oldNumber ?: 0)

  private fun review(
    snapshot: DiffSnapshot,
    comments: List<ReviewComment>,
    toolbar: DiffToolbarModel,
    view: WorkspaceViewState,
    pendingMessage: String?,
    previews: Map<String, PreviewLoad>,
    assets: PreviewAssetLoader,
  ): DiffReviewModel {
    val unsent = comments.filter { it.status == CommentStatus.Unsent }
    val fileCount = snapshot.files.size
    return DiffReviewModel(
      toolbar = toolbar.copy(
        summary = if (snapshot.hasUncommittedChanges) {
          Res.plurals.diff_summary_uncommitted.asLemonadeCodeString(fileCount)
        } else {
          Res.plurals.diff_summary.asLemonadeCodeString(fileCount)
        },
      ),
      files = snapshot.files.map { file(it, comments.filter { comment -> comment.filePath == it.path }, view, previews[it.path]) },
      sendBar = SendBarModel(
        summary = when {
          unsent.isNotEmpty() -> SendBarSummary.Unsent(unsent.size, unsent.map { it.filePath }.distinct().size)
          comments.isNotEmpty() -> SendBarSummary.AllDelivered
          else -> SendBarSummary.NoComments
        },
        canSend = unsent.isNotEmpty(),
        preview = pendingMessage?.takeIf { view.previewOpen && unsent.isNotEmpty() },
      ),
      assets = assets,
    )
  }

  private fun file(
    file: FileDiff,
    comments: List<ReviewComment>,
    view: WorkspaceViewState,
    preview: PreviewLoad?,
  ): DiffFileModel {
    val commented = comments.isNotEmpty()
    val expanded = isBodyVisible(file, view, commented)
    val showingPreview = isShowingPreview(file, view, commented)
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
            title = Res.string.comment_on_line.asLemonadeCodeString("${file.path}:$range"),
            draft = it.draft,
            canSubmit = it.draft.isNotBlank(),
          )
        },
      )
    }
    val rows = when {
      !expanded || file.isBinary || showingPreview -> emptyList()
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
      expanded = expanded,
      showingFullFile = full,
      canShowFullFile = !showingPreview && !file.isBinary && (file.change == FileChange.Modified || file.change == FileChange.Renamed),
      rows = rows,
      preview = file.previewKind?.let { kind -> preview(file, kind, showingPreview, view, preview) },
    )
  }

  private fun preview(
    file: FileDiff,
    kind: PreviewKind,
    showing: Boolean,
    view: WorkspaceViewState,
    load: PreviewLoad?,
  ): FilePreviewModel {
    val bothSides = file.change != FileChange.Added && file.change != FileChange.Deleted
    val before = load?.before
    val after = load?.after
    val content = when {
      !showing -> FilePreviewContentModel.Loading
      before != null && after != null -> FilePreviewContentModel.Ready(
        before = before.toSide(file.previousPath ?: file.path),
        after = after.toSide(file.path),
      )
      load?.error != null -> FilePreviewContentModel.Failed(load.error)
      else -> FilePreviewContentModel.Loading
    }
    return FilePreviewModel(
      kind = when (kind) {
        PreviewKind.Image -> PreviewKindModel.Image
        PreviewKind.Vector -> PreviewKindModel.Vector
        PreviewKind.Model -> PreviewKindModel.Model
        PreviewKind.Markdown -> PreviewKindModel.Markdown
        PreviewKind.Table -> PreviewKindModel.Table
      },
      showing = showing,
      layout = if (bothSides) view.previewLayouts[file.path] ?: defaultLayout(kind) else null,
      content = content,
    )
  }

  private fun FileContent.toSide(path: String): PreviewSideModel? = when (this) {
    FileContent.Missing -> null
    is FileContent.TooLarge -> PreviewSideModel.TooLarge(sizeBytes)
    is FileContent.Bytes -> PreviewSideModel.Content(bytes, path.substringAfterLast('/').substringAfterLast('.', "").lowercase())
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
    authorName = Res.string.comment_author_you.asLemonadeCodeString(),
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
