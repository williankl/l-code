package dev.loupe.review.impl

import dev.loupe.domain.review.DiffLineKind
import dev.loupe.domain.review.FileChange

internal data class ParsedLine(
  val kind: DiffLineKind,
  val oldNumber: Int?,
  val newNumber: Int?,
  val text: String,
)

internal data class ParsedFile(
  val path: String,
  val previousPath: String?,
  val change: FileChange,
  val isBinary: Boolean,
  val lines: List<ParsedLine>,
)

/** Reads `git diff` output: file headers, renames, binary markers and hunks. */
internal object UnifiedDiffParser {
  private val hunkHeader = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")

  fun parse(diff: String): List<ParsedFile> {
    val files = mutableListOf<ParsedFile>()
    var current: Builder? = null
    var oldLine = 0
    var newLine = 0
    var inHunk = false
    for (raw in diff.lineSequence()) {
      if (raw.startsWith("diff --git ")) {
        current?.let { files += it.build() }
        current = Builder(headerPath(raw))
        inHunk = false
        continue
      }
      val file = current ?: continue
      when {
        raw.startsWith("@@") -> hunkHeader.find(raw)?.let { match ->
          oldLine = match.groupValues[1].toInt()
          newLine = match.groupValues[3].toInt()
          inHunk = true
        }
        inHunk && raw.startsWith("+") -> file.lines += ParsedLine(DiffLineKind.Added, null, newLine++, raw.substring(1))
        inHunk && raw.startsWith("-") -> file.lines += ParsedLine(DiffLineKind.Removed, oldLine++, null, raw.substring(1))
        inHunk && raw.startsWith(" ") -> file.lines += ParsedLine(DiffLineKind.Context, oldLine++, newLine++, raw.substring(1))
        inHunk -> Unit
        raw.startsWith("new file mode") -> file.change = FileChange.Added
        raw.startsWith("deleted file mode") -> file.change = FileChange.Deleted
        raw.startsWith("rename from ") -> {
          file.previousPath = raw.removePrefix("rename from ").unquote()
          file.change = FileChange.Renamed
        }
        raw.startsWith("rename to ") -> file.path = raw.removePrefix("rename to ").unquote()
        raw.startsWith("Binary files ") || raw.startsWith("GIT binary patch") -> file.isBinary = true
        raw.startsWith("+++ ") -> raw.removePrefix("+++ ").stripSide("b/")?.let { file.path = it }
      }
    }
    current?.let { files += it.build() }
    return files
  }

  private fun headerPath(header: String): String {
    val rest = header.removePrefix("diff --git ")
    val bIndex = rest.lastIndexOf(" b/")
    return if (bIndex >= 0) rest.substring(bIndex + 3).unquote() else rest.unquote()
  }

  private fun String.stripSide(prefix: String): String? {
    val value = unquote()
    if (value == "/dev/null") return null
    return value.removePrefix(prefix)
  }

  private fun String.unquote(): String {
    if (length < 2 || first() != '"' || last() != '"') return this
    return substring(1, length - 1).replace("\\\"", "\"").replace("\\\\", "\\").replace("\\t", "\t")
  }

  private class Builder(var path: String) {
    var previousPath: String? = null
    var change: FileChange = FileChange.Modified
    var isBinary: Boolean = false
    val lines: MutableList<ParsedLine> = mutableListOf()

    fun build() = ParsedFile(path, previousPath, change, isBinary, lines.toList())
  }
}
