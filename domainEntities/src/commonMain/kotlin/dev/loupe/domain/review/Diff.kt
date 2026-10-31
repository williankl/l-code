package dev.loupe.domain.review

/** The working tree compared with the merge base of [baseRef], untracked files included. */
public data class DiffSnapshot(
  val baseRef: String,
  val files: List<FileDiff>,
  val hasUncommittedChanges: Boolean,
  /** The commit the "before" side is read from; null when it isn't known. */
  val mergeBase: String? = null,
) {
  val additions: Int get() = files.sumOf { it.additions }
  val deletions: Int get() = files.sumOf { it.deletions }
}

/** One changed file with every line of it, so hunks and the full file can both be shown. */
public data class FileDiff(
  val path: String,
  val previousPath: String?,
  val change: FileChange,
  val language: String,
  val lines: List<DiffLine>,
  val isBinary: Boolean,
) {
  val additions: Int get() = lines.count { it.kind == DiffLineKind.Added }
  val deletions: Int get() = lines.count { it.kind == DiffLineKind.Removed }

  /** How the file can be drawn besides its text, or null when only the diff applies. */
  val previewKind: PreviewKind? get() = PreviewKind.of(path)
}

public enum class FileChange { Added, Modified, Deleted, Renamed }

public data class DiffLine(
  val kind: DiffLineKind,
  val oldNumber: Int?,
  val newNumber: Int?,
  val text: String,
  val tokens: List<CodeToken>,
)

public enum class DiffLineKind { Context, Added, Removed }

public data class CodeToken(
  val text: String,
  val kind: CodeTokenKind,
)

public enum class CodeTokenKind { Plain, Keyword, String, Number, Function, Type, Comment, Punctuation, Heading }

public sealed interface DiffState {
  public data object Loading : DiffState

  public data class Ready(val snapshot: DiffSnapshot) : DiffState

  public data object NotARepository : DiffState

  public data class NoBaseBranch(val baseRef: String) : DiffState

  public data class Failed(val message: String) : DiffState
}
