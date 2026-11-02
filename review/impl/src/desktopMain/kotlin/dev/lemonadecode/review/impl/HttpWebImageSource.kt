package dev.lemonadecode.review.impl

import dev.lemonadecode.domain.review.FileContent
import dev.lemonadecode.platform.architecture.LemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.core.mapError
import dev.lemonadecode.platform.core.safeSuspendRunCatchingOn
import dev.lemonadecode.platform.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Downloads pictures with `HttpURLConnection` (the bundled Java runtime leaves out `java.net.http`),
 * following redirects. Web pages are refused before their body is read: a link that leads to a
 * page instead of a picture would only be thrown away.
 */
@ContributesBinding(AppScope::class)
@Inject
internal class HttpWebImageSource(
  private val dispatchers: LemonadeCodeCoroutineDispatchers,
) : WebImageSource {
  override suspend fun read(url: String, maxBytes: Long): LemonadeCodeResult<FileContent, String> = safeSuspendRunCatchingOn(dispatchers.io) {
    val connection = URI(asciiUrl(url)).toURL().openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = TimeoutMillis
      connection.readTimeout = TimeoutMillis
      connection.instanceFollowRedirects = true
      connection.setRequestProperty("Accept", "image/*,*/*;q=0.5")
      connection.setRequestProperty("User-Agent", "Lemonade-Code")
      val status = connection.responseCode
      if (status !in 200..299) throw IOException("$url answered $status")
      if (connection.contentType.orEmpty().startsWith("text/html", ignoreCase = true)) throw IOException("$url is a web page, not a picture")
      val length = connection.contentLengthLong
      if (length > maxBytes) {
        FileContent.TooLarge(length)
      } else {
        connection.inputStream.use { input ->
          val out = ByteArrayOutputStream()
          val buffer = ByteArray(BufferSize)
          var total = 0L
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return@use FileContent.TooLarge(total)
            out.write(buffer, 0, read)
          }
          FileContent.Bytes(out.toByteArray())
        }
      }
    } finally {
      connection.disconnect()
    }
  }.mapError { it.message ?: it::class.simpleName.orEmpty() }

  private companion object {
    const val TimeoutMillis = 15_000
    const val BufferSize = 64 * 1024
  }
}
