package dev.lemonadecode.platform.terminal

import java.io.ByteArrayOutputStream

/**
 * The folder in an OSC 7 report (what follows `7;`): `file://host/path`, the path percent-encoded.
 * On [windows] the path reads `/C:/Users/me` and comes back as `C:\Users\me`; one without a drive
 * letter is no Windows folder, so it's dropped. Null when it isn't a file URL.
 */
internal fun folderFromOsc7(data: String, windows: Boolean): String? {
  if (!data.startsWith(FileScheme, ignoreCase = true)) return null
  val afterHost = data.indexOf('/', startIndex = FileScheme.length).takeIf { it >= 0 } ?: return null
  val path = percentDecoded(data.substring(afterHost))
  if (!windows) return path.trimEnd('/').ifEmpty { "/" }
  val drive = WindowsDrivePath.matchEntire(path) ?: return null
  val (letter, rest) = drive.destructured
  return letter + rest.replace('/', '\\').trimEnd('\\').ifEmpty { "\\" }
}

/** The folder in an OSC 9;9 report (what follows `9;9;`), ConEmu's and Windows Terminal's: the path as is, maybe quoted. */
internal fun folderFromOsc9(data: String): String? = data.removeSurrounding("\"").takeIf { it.isNotBlank() }

/** `%20` → a space, and so on, read as UTF-8. A `%` that starts no escape is kept as written. */
private fun percentDecoded(text: String): String {
  if ('%' !in text) return text
  val bytes = ByteArrayOutputStream()
  var index = 0
  while (index < text.length) {
    val high = if (text[index] == '%' && index + 2 < text.length) hexValue(text[index + 1]) else -1
    val low = if (high >= 0) hexValue(text[index + 2]) else -1
    if (low >= 0) {
      bytes.write(high * 16 + low)
      index += 3
    } else {
      val end = text.indexOf('%', startIndex = index + 1).takeIf { it >= 0 } ?: text.length
      bytes.writeBytes(text.substring(index, end).encodeToByteArray())
      index = end
    }
  }
  return bytes.toByteArray().decodeToString()
}

private fun hexValue(char: Char): Int = when (char) {
  in '0'..'9' -> char - '0'
  in 'a'..'f' -> char - 'a' + 10
  in 'A'..'F' -> char - 'A' + 10
  else -> -1
}

private const val FileScheme = "file://"

/** `/C:/Users/me` or `/C:`: a drive letter, then the rest of the path. */
private val WindowsDrivePath = Regex("""/([A-Za-z]:)((?:[/\\].*)?)""")
