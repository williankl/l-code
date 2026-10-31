package dev.loupe.review.impl

import dev.loupe.data.git.GitDataSource
import dev.loupe.data.git.GitError
import dev.loupe.domain.review.CodeToken
import dev.loupe.domain.review.CodeTokenKind
import dev.loupe.domain.review.DiffLine
import dev.loupe.domain.review.DiffLineKind
import dev.loupe.domain.review.DiffSnapshot
import dev.loupe.domain.review.DiffState
import dev.loupe.domain.review.FileChange
import dev.loupe.domain.review.FileDiff
import dev.loupe.platform.architecture.LoupeCoroutineDispatchers
import dev.loupe.platform.core.LoupeResult
import dev.loupe.platform.core.getOrElse
import dev.loupe.platform.core.getOrNull
import dev.loupe.platform.syntax.SyntaxHighlighter
import dev.loupe.platform.syntax.SyntaxKind
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.withContext

/** Reads the working tree's diff against the review base, untracked files included, and colours it. */
@Inject
internal class LoadDiffUseCase(
  private val git: GitDataSource,
  private val dispatchers: LoupeCoroutineDispatchers,
) {
  suspend operator fun invoke(repositoryRoot: String): DiffState {
    val base = when (val branch = git.defaultBranch(repositoryRoot)) {
      is LoupeResult.Success -> branch.value
      is LoupeResult.Failure -> return branch.error.toState()
    }
    val diff = when (val raw = git.diffAgainst(repositoryRoot, base)) {
      is LoupeResult.Success -> raw.value
      is LoupeResult.Failure -> return raw.error.toState()
    }
    val untracked = git.untrackedFiles(repositoryRoot).getOrElse { emptyList() }
    val untrackedFiles = untracked.mapNotNull { path ->
      val text = git.readTextFile(repositoryRoot, path, MaxUntrackedBytes).getOrNull()
      ParsedFile(
        path = path,
        previousPath = null,
        change = FileChange.Added,
        isBinary = text == null,
        lines = text?.removeSuffix("\n")?.split('\n')?.mapIndexed { index, line ->
          ParsedLine(DiffLineKind.Added, null, index + 1, line)
        }.orEmpty(),
      )
    }
    val uncommitted = git.hasUncommittedChanges(repositoryRoot).getOrElse { false }
    val mergeBase = git.mergeBase(repositoryRoot, base).getOrNull()
    val files = withContext(dispatchers.default) {
      (UnifiedDiffParser.parse(diff) + untrackedFiles).map { it.toFileDiff() }
    }
    return DiffState.Ready(DiffSnapshot(baseRef = base, files = files, hasUncommittedChanges = uncommitted, mergeBase = mergeBase))
  }

  private fun ParsedFile.toFileDiff(): FileDiff {
    val language = SyntaxHighlighter.languageOf(path)
    val tokens = SyntaxHighlighter.highlight(lines.map { it.text }, language)
    return FileDiff(
      path = path,
      previousPath = previousPath,
      change = change,
      language = language.displayName,
      isBinary = isBinary,
      lines = lines.mapIndexed { index, line ->
        DiffLine(
          kind = line.kind,
          oldNumber = line.oldNumber,
          newNumber = line.newNumber,
          text = line.text,
          tokens = tokens[index].map { CodeToken(it.text, it.kind.toDomain()) },
        )
      },
    )
  }

  private fun SyntaxKind.toDomain(): CodeTokenKind = when (this) {
    SyntaxKind.Plain -> CodeTokenKind.Plain
    SyntaxKind.Keyword -> CodeTokenKind.Keyword
    SyntaxKind.String -> CodeTokenKind.String
    SyntaxKind.Number -> CodeTokenKind.Number
    SyntaxKind.Function -> CodeTokenKind.Function
    SyntaxKind.Type -> CodeTokenKind.Type
    SyntaxKind.Comment -> CodeTokenKind.Comment
    SyntaxKind.Punctuation -> CodeTokenKind.Punctuation
    SyntaxKind.Heading -> CodeTokenKind.Heading
  }

  private fun GitError.toState(): DiffState = when (this) {
    GitError.NotARepository -> DiffState.NotARepository
    is GitError.RefNotFound -> DiffState.NoBaseBranch(ref)
    is GitError.CommandFailed -> DiffState.Failed(message)
    is GitError.Unavailable -> DiffState.Failed(message)
  }

  private companion object {
    const val MaxUntrackedBytes = 512 * 1024
  }
}
