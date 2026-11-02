package dev.lemonadecode.review.impl

import dev.lemonadecode.domain.review.FileContent
import dev.lemonadecode.platform.core.LemonadeCodeResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reads pictures from the web for previews, like the badges at the top of a README. */
internal interface WebImageSource {
  /** What's at [url]: [FileContent.TooLarge] past [maxBytes], else its bytes; the failure says why not. */
  suspend fun read(url: String, maxBytes: Long): LemonadeCodeResult<FileContent, String>
}

/**
 * The web pictures read lately, so scrolling a README past its badges again doesn't download
 * them again. Keeps the most recently used, up to [maxEntries] pictures and [maxBytes] in all.
 */
internal class WebImageCache(
  private val maxEntries: Int = 128,
  private val maxBytes: Long = 32L * 1024 * 1024,
) {
  private val lock = Mutex()
  private val entries = LinkedHashMap<String, FileContent.Bytes>()
  private var totalBytes = 0L

  suspend fun get(url: String): FileContent.Bytes? = lock.withLock {
    entries.remove(url)?.also { entries[url] = it }
  }

  suspend fun put(url: String, content: FileContent.Bytes) {
    if (content.sizeBytes > maxBytes) return
    lock.withLock {
      entries.remove(url)?.let { totalBytes -= it.sizeBytes }
      entries[url] = content
      totalBytes += content.sizeBytes
      val oldest = entries.entries.iterator()
      while ((entries.size > maxEntries || totalBytes > maxBytes) && oldest.hasNext()) {
        totalBytes -= oldest.next().value.sizeBytes
        oldest.remove()
      }
    }
  }
}

/**
 * Whether [path] names a file inside the checkout: relative, with no `..` or `.` steps, no
 * backslashes and no colons, which would let a picture's address read files elsewhere or make
 * git read another revision.
 */
internal fun isCheckoutPath(path: String): Boolean {
  if (path.isEmpty() || path.startsWith('/') || '\\' in path || ':' in path || '\u0000' in path) return false
  return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
}

/** [url] with spaces and characters beyond ASCII percent-encoded as UTF-8, which `java.net.URI` refuses as they are. */
internal fun asciiUrl(url: String): String = buildString {
  var i = 0
  while (i < url.length) {
    val c = url[i]
    val pair = c.isHighSurrogate() && i + 1 < url.length && url[i + 1].isLowSurrogate()
    val unit = if (pair) url.substring(i, i + 2) else c.toString()
    if (!pair && c.code in 0x21..0x7E && c !in "\"<>\\^`{|}") {
      append(c)
    } else {
      unit.encodeToByteArray().forEach { byte ->
        append('%')
        append(HexDigits[(byte.toInt() shr 4) and 0xF])
        append(HexDigits[byte.toInt() and 0xF])
      }
    }
    i += unit.length
  }
}

private const val HexDigits = "0123456789ABCDEF"
