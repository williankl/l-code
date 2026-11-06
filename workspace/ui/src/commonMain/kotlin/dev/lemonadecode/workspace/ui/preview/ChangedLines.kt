package dev.lemonadecode.workspace.ui.preview

/**
 * The lines a change touched on one side of a file, given as the diff counts them (from 1), for
 * asking whether a span of the file's lines (from 0) holds any.
 */
internal class ChangedLines(ranges: List<IntRange>) {
  // before[i] is how many changed lines come before line i.
  private val before: IntArray

  init {
    val end = ranges.maxOfOrNull { it.last } ?: 0
    val changed = BooleanArray(end.coerceAtLeast(0))
    ranges.forEach { range -> for (line in range.first.coerceAtLeast(1)..range.last) changed[line - 1] = true }
    before = IntArray(changed.size + 1)
    changed.forEachIndexed { index, isChanged -> before[index + 1] = before[index] + if (isChanged) 1 else 0 }
  }

  /** Whether any of [lines], counted from 0, changed. */
  fun any(lines: IntRange): Boolean {
    if (lines.isEmpty()) return false
    val last = before.lastIndex
    return before[(lines.last + 1).coerceIn(0, last)] - before[lines.first.coerceIn(0, last)] > 0
  }
}
