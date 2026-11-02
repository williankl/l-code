package dev.lemonadecode.workspace.impl

import dev.lemonadecode.domain.review.FileDiff
import dev.lemonadecode.domain.review.ReviewComment
import dev.lemonadecode.workspace.uimodel.DiffRowModel

/**
 * Each file's rows as last mapped, so a new Model maps only what changed. A file's lines are
 * mapped once per diff; the selection, comments and composer are laid over them again only when
 * those change, and then only the lines they touch are new. A keystroke in the composer, a drag
 * across lines or a session changing status no longer maps every line of a long diff.
 */
internal class DiffRowCache {
  private val files = HashMap<String, Entry>()

  /**
   * The rows of [file]: [plain] maps them, hunked or [full], and [marked] lays [marks] over
   * them. Both run only when their inputs changed since the last call.
   */
  fun rows(
    file: FileDiff,
    full: Boolean,
    marks: LineMarks,
    plain: () -> List<DiffRowModel>,
    marked: (List<DiffRowModel>) -> List<DiffRowModel>,
  ): List<DiffRowModel> {
    val entry = files[file.path]?.takeIf { it.file === file && it.full == full }
      ?: Entry(file, full, plain()).also { files[file.path] = it }
    if (entry.marks != marks) {
      entry.marked = marked(entry.plain)
      entry.marks = marks
    }
    return entry.marked
  }

  /** Forgets files that left the diff or changed in it, so an old diff isn't kept alive. */
  fun retain(current: List<FileDiff>) {
    val byPath = current.associateBy { it.path }
    files.entries.removeAll { (path, entry) -> byPath[path] !== entry.file }
  }

  private class Entry(
    val file: FileDiff,
    val full: Boolean,
    val plain: List<DiffRowModel>,
  ) {
    var marks: LineMarks? = null
    var marked: List<DiffRowModel> = plain
  }
}

/** What is laid over a file's lines: the selected range, the open composer and the comments. */
internal data class LineMarks(
  val selection: LineRange?,
  val composer: Composer?,
  val comments: List<ReviewComment>,
)
