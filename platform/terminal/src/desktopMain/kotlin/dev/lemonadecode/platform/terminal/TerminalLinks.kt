package dev.lemonadecode.platform.terminal

import com.jediterm.terminal.model.hyperlinks.LinkInfo
import java.awt.Desktop
import java.io.File
import java.net.URI
import kotlin.concurrent.thread

/** A link in a run of text: what it points to and which characters show it (the text may leave out the scheme). */
internal data class FoundLink(val url: String, val range: IntRange)

/**
 * The link covering character [index] of [text]. A link the program marked ([explicit] holds each
 * character's target, from OSC 8) comes first; otherwise a URL written out in the text, including
 * a `www.` address with no scheme, which opens over https.
 */
internal fun linkAt(text: String, index: Int, explicit: List<String?>): FoundLink? {
  if (index !in text.indices) return null
  explicit.getOrNull(index)?.let { url ->
    var from = index
    while (from > 0 && explicit[from - 1] == url) from--
    var to = index
    while (to + 1 < explicit.size && explicit[to + 1] == url) to++
    return FoundLink(withScheme(url), from..to)
  }
  return WrittenUrl.findAll(text)
    .map { match -> match.range.first..(match.range.first + trimmedLength(match.value) - 1) }
    .firstOrNull { index in it }
    ?.let { range -> FoundLink(withScheme(text.substring(range)), range) }
}

private fun withScheme(url: String): String = if (url.startsWith("www.", ignoreCase = true)) "https://$url" else url

/** The schemes a link may open. Others (javascript:, ssh:, custom app schemes) are left alone. */
internal fun isOpenable(url: String): Boolean = SafeSchemes.any { url.startsWith(it, ignoreCase = true) }

/**
 * Opens [url] the way the system does: web and mail links in the default browser or mail app,
 * file links shown in Finder (never run, since a link's text can hide where it points). On a Mac
 * this goes through `open`, as other terminals do; the URL is passed as an argument, never
 * through a shell.
 */
public fun openInBrowser(url: String) {
  if (!isOpenable(url)) return
  System.err.println("Lemonade Code: opening link $url")
  val _ = thread(name = "lemonade-code-open-link", isDaemon = true) {
    runCatching {
      val uri = URI(url)
      val file = if (uri.scheme.equals("file", ignoreCase = true)) File(uri) else null
      val command = when {
        IsMac && file != null -> listOf("open", "-R", file.path)
        IsMac -> listOf("open", uri.toASCIIString())
        IsLinux -> listOf("xdg-open", (file?.parentFile?.path ?: uri.toASCIIString()))
        else -> null
      }
      if (command != null) {
        val _ = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
      } else {
        val desktop = Desktop.getDesktop()
        when {
          uri.scheme.equals("mailto", ignoreCase = true) -> desktop.mail(uri)
          file != null -> desktop.open(file.parentFile)
          else -> desktop.browse(uri)
        }
      }
    }.onFailure { System.err.println("Lemonade Code: couldn't open $url: ${it.message}") }
  }
}

private val IsMac = System.getProperty("os.name").orEmpty().startsWith("Mac")
private val IsLinux = System.getProperty("os.name").orEmpty().startsWith("Linux")

/** JediTerm's tag for cells inside an OSC 8 link, carrying where the link points. */
internal class UrlLink(val url: String) : LinkInfo({})

private val SafeSchemes = listOf("http://", "https://", "mailto:", "file://")

private val WrittenUrl = Regex("""(?i)\b(?:(?:https?://|file://|mailto:)[^\s"'<>`]+|www\.[a-z0-9-]+\.[^\s"'<>`]+)""")

/** Leaves off what reads as the end of the sentence: trailing punctuation and unmatched closing brackets. */
private fun trimmedLength(url: String): Int {
  var end = url.length
  while (end > 0) {
    val last = url[end - 1]
    val unmatched = when (last) {
      ')' -> url.substring(0, end).count { it == '(' } < url.substring(0, end).count { it == ')' }
      ']' -> url.substring(0, end).count { it == '[' } < url.substring(0, end).count { it == ']' }
      '}' -> url.substring(0, end).count { it == '{' } < url.substring(0, end).count { it == '}' }
      else -> false
    }
    if (last in ".,;:!?" || unmatched) end-- else break
  }
  return end
}
