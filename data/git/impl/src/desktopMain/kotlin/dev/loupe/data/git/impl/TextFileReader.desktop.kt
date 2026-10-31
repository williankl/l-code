package dev.loupe.data.git.impl

import java.io.File

internal actual class TextFileReader actual constructor() {
  actual fun read(root: String, path: String, maxBytes: Int): String? {
    val file = File(root, path)
    if (!file.isFile || file.length() > maxBytes) return null
    val bytes = file.readBytes()
    if (bytes.any { it == 0.toByte() }) return null
    return bytes.decodeToString()
  }
}
