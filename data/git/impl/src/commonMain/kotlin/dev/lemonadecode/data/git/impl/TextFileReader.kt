package dev.lemonadecode.data.git.impl

import dev.lemonadecode.data.git.GitBlob

/** Reads working-tree files: text for untracked-file diffs, bytes for previews. */
internal expect class TextFileReader() {
  fun read(root: String, path: String, maxBytes: Int): String?

  fun readBytes(root: String, path: String, maxBytes: Long): GitBlob
}
