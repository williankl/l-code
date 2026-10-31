package dev.loupe.data.git.impl

import dev.loupe.data.git.GitBlob
import java.io.File

internal actual class TextFileReader actual constructor() {
  actual fun read(root: String, path: String, maxBytes: Int): String? {
    val file = File(root, path)
    if (!file.isFile || file.length() > maxBytes) return null
    val bytes = file.readBytes()
    if (bytes.any { it == 0.toByte() }) return null
    return bytes.decodeToString()
  }

  actual fun readBytes(root: String, path: String, maxBytes: Long): GitBlob {
    val file = File(root, path)
    if (!file.isFile) return GitBlob.Missing
    val size = file.length()
    if (size > maxBytes) return GitBlob.TooLarge(size)
    return GitBlob.Content(file.readBytes())
  }
}
