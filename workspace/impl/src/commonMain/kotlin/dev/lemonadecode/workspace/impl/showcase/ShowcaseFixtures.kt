package dev.lemonadecode.workspace.impl.showcase

import dev.lemonadecode.domain.review.CodeToken
import dev.lemonadecode.domain.review.CodeTokenKind
import dev.lemonadecode.domain.review.CommentId
import dev.lemonadecode.domain.review.CommentStatus
import dev.lemonadecode.domain.review.DiffLine
import dev.lemonadecode.domain.review.DiffLineKind
import dev.lemonadecode.domain.review.DiffSnapshot
import dev.lemonadecode.domain.review.FileChange
import dev.lemonadecode.domain.review.FileDiff
import dev.lemonadecode.domain.review.LineAnchor
import dev.lemonadecode.domain.review.LineSide
import dev.lemonadecode.domain.review.ReviewComment
import dev.lemonadecode.domain.session.Session
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SessionOrigin
import dev.lemonadecode.domain.session.SessionPlacement
import dev.lemonadecode.domain.session.SessionStatus
import dev.lemonadecode.platform.syntax.SyntaxHighlighter
import dev.lemonadecode.platform.syntax.SyntaxKind

/** The data behind every showcase screen: the rate-limiting change from the design. */
internal object ShowcaseFixtures {
  const val Home = "/Users/you"
  const val Repo = "$Home/code/storefront"
  const val Branch = "feat/rate-limit"

  val snapshot: DiffSnapshot = DiffSnapshot(
    baseRef = "main",
    files = SampleSources.files.map(::fileDiff),
    hasUncommittedChanges = true,
  )

  fun session(
    id: String,
    name: String,
    status: SessionStatus = SessionStatus.Idle,
    parent: String? = null,
    shell: String = "zsh",
    branch: String? = Branch,
    origin: SessionOrigin = if (parent == null) SessionOrigin.Opened else SessionOrigin.Split,
    placement: SessionPlacement = if (parent == null) SessionPlacement.Root else SessionPlacement.SameFolder,
    folder: String = Repo,
  ) = Session(
    id = SessionId(id),
    name = name,
    shell = shell,
    workingDirectory = folder,
    repositoryRoot = folder,
    branch = branch,
    parentId = parent?.let(::SessionId),
    placement = placement,
    origin = origin,
    status = status,
    // The design's sessions run Claude Code whenever they show a status.
    agent = "claude".takeIf { status != SessionStatus.Shell && status != SessionStatus.Exited },
  )

  fun comment(
    fileId: String,
    line: Int,
    status: CommentStatus = CommentStatus.Unsent,
    endLine: Int = line,
    body: String = SampleSources.commentTexts.getValue("$fileId:$line"),
  ) = ReviewComment(
    id = CommentId("$fileId$line"),
    filePath = path(fileId),
    start = LineAnchor(LineSide.New, line),
    end = LineAnchor(LineSide.New, endLine),
    body = body,
    status = status,
  )

  fun path(fileId: String): String = SampleSources.files.first { it.id == fileId }.path

  /** Index of the row showing new-side line [line] of [fileId]. */
  fun rowOf(fileId: String, line: Int): Int = snapshot.files.first { it.path == path(fileId) }.lines.indexOfFirst { it.newNumber == line }

  private fun fileDiff(source: SampleFileSource): FileDiff {
    val language = SyntaxHighlighter.languageOf(source.path)
    val codes = source.lines.map { it.drop(1) }
    val tokens = SyntaxHighlighter.highlight(codes, language)
    var old = 0
    var new = 0
    val lines = source.lines.mapIndexed { index, raw ->
      val kind = when (raw.first()) {
        '+' -> DiffLineKind.Added
        '-' -> DiffLineKind.Removed
        else -> DiffLineKind.Context
      }
      val oldNumber = if (kind != DiffLineKind.Added) ++old else null
      val newNumber = if (kind != DiffLineKind.Removed) ++new else null
      DiffLine(kind, oldNumber, newNumber, codes[index], tokens[index].map { CodeToken(it.text, it.kind.toDomain()) })
    }
    return FileDiff(
      path = source.path,
      previousPath = null,
      change = when (source.change) {
        SampleChange.Added -> FileChange.Added
        SampleChange.Modified -> FileChange.Modified
        SampleChange.Deleted -> FileChange.Deleted
      },
      language = language.displayName,
      lines = lines,
      isBinary = false,
    )
  }

  private fun SyntaxKind.toDomain(): CodeTokenKind = CodeTokenKind.valueOf(name)
}
