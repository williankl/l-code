package dev.loupe.data.git.impl

/** Reads working-tree files for untracked-file diffs. */
internal expect class TextFileReader() {
  fun read(root: String, path: String, maxBytes: Int): String?
}
