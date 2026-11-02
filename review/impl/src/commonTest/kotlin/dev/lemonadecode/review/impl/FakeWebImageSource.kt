package dev.lemonadecode.review.impl

import dev.lemonadecode.domain.review.FileContent
import dev.lemonadecode.platform.core.LemonadeCodeResult

/** Answers from [images] by address, failing for anything else, and counts what was read. */
internal class FakeWebImageSource(
  var images: Map<String, FileContent> = emptyMap(),
) : WebImageSource {
  val reads: MutableList<String> = mutableListOf()

  override suspend fun read(url: String, maxBytes: Long): LemonadeCodeResult<FileContent, String> {
    reads += url
    val content = images[url] ?: return LemonadeCodeResult.failure("$url answered 404")
    return LemonadeCodeResult.success(if (content is FileContent.Bytes && content.sizeBytes > maxBytes) FileContent.TooLarge(content.sizeBytes) else content)
  }
}
