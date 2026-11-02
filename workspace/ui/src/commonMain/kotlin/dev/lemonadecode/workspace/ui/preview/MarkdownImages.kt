package dev.lemonadecode.workspace.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.lemonadecode.workspace.uimodel.PreviewAssetLoader
import dev.lemonadecode.workspace.uimodel.PreviewVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.decodeToSvgPainter
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Where a picture in a document lives, once its `src` is read against the document's own path. */
internal sealed interface ImageAddress {
  /** A file in the checkout, from its root. */
  data class Checkout(val path: String) : ImageAddress

  data class Web(val url: String) : ImageAddress

  /** A `data:` URI's bytes. */
  class Inline(val bytes: ByteArray) : ImageAddress
}

/**
 * Where a Markdown document's pictures come from: the checkout, on the same side of the change as
 * the document, or the web. [documentPath] is the document's path from the checkout's root.
 */
@Stable
internal class MarkdownImageSource(
  private val documentPath: String?,
  private val version: PreviewVersion,
  private val assets: PreviewAssetLoader,
) {
  fun resolve(source: String): ImageAddress? = resolveImage(documentPath, source)

  suspend fun read(address: ImageAddress): ByteArray? = when (address) {
    is ImageAddress.Checkout -> assets.checkoutFile(address.path, version)
    is ImageAddress.Web -> assets.webImage(address.url)
    is ImageAddress.Inline -> address.bytes
  }

  companion object {
    /** Reads nothing, so every picture shows its alt text. */
    val None: MarkdownImageSource = MarkdownImageSource(null, PreviewVersion.After, PreviewAssetLoader.None)
  }
}

internal val LocalMarkdownImages = staticCompositionLocalOf { MarkdownImageSource.None }

private val Scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/**
 * A picture's `src` read the way GitHub reads it in a file at [documentPath]: web addresses as
 * they are, `/path` from the checkout's root, anything else next to the document. Null for
 * addresses that lead outside the checkout or to anything but a picture.
 */
internal fun resolveImage(documentPath: String?, source: String): ImageAddress? {
  val src = source.trim().removeSurrounding("<", ">")
  if (src.isEmpty()) return null
  val lower = src.lowercase()
  return when {
    lower.startsWith("https://") || lower.startsWith("http://") -> ImageAddress.Web(src)
    src.startsWith("//") -> ImageAddress.Web("https:$src")
    lower.startsWith("data:") -> dataUri(src)?.let(ImageAddress::Inline)
    Scheme.containsMatchIn(src) -> null
    documentPath == null -> null
    else -> {
      val path = percentDecode(src.substringBefore('#').substringBefore('?'))
      val folder = if (path.startsWith('/')) emptyList() else documentPath.substringBeforeLast('/', "").split('/')
      normalizePath(folder + path.split('/'))?.let(ImageAddress::Checkout)
    }
  }
}

/** `a/./b/../c` as `a/c`; null when `..` climbs out of the root or nothing is left. */
private fun normalizePath(segments: List<String>): String? {
  val out = ArrayList<String>()
  segments.forEach { segment ->
    when (segment) {
      "", "." -> Unit
      ".." -> if (out.isEmpty()) return null else out.removeAt(out.lastIndex)
      else -> out += segment
    }
  }
  return out.takeIf { it.isNotEmpty() }?.joinToString("/")
}

/** `%20` and friends, decoded as UTF-8; malformed escapes stay as written. */
internal fun percentDecode(text: String): String {
  if ('%' !in text) return text
  val bytes = ArrayList<Byte>(text.length)
  var i = 0
  while (i < text.length) {
    val c = text[i]
    val hex = if (c == '%' && i + 2 <= text.lastIndex) text.substring(i + 1, i + 3).toIntOrNull(16) else null
    if (hex != null) {
      bytes += hex.toByte()
      i += 3
    } else {
      c.toString().encodeToByteArray().forEach { bytes += it }
      i++
    }
  }
  return bytes.toByteArray().decodeToString()
}

@OptIn(ExperimentalEncodingApi::class)
private fun dataUri(uri: String): ByteArray? {
  val comma = uri.indexOf(',')
  if (comma < 0) return null
  val header = uri.substring(5, comma).lowercase()
  val data = uri.substring(comma + 1)
  return if (header.endsWith(";base64")) {
    runCatching { Base64.decode(data.filterNot { it.isWhitespace() }) }.getOrNull()
  } else {
    percentDecode(data).encodeToByteArray()
  }
}

/** A decoded picture. [width] and [height] are its own size in pixels, which a page lays out as dp. */
internal sealed interface Picture {
  val width: Float
  val height: Float

  /** [width] and [height] differ from the bitmap's own for an SVG drawn into a bitmap at screen density. */
  class Raster(
    val image: ImageBitmap,
    override val width: Float = image.width.toFloat(),
    override val height: Float = image.height.toFloat(),
  ) : Picture

  class Vector(val painter: Painter, override val width: Float, override val height: Float) : Picture
}

internal sealed interface PictureState {
  data object Loading : PictureState

  data object Failed : PictureState

  class Ready(val picture: Picture) : PictureState
}

/**
 * Loads and decodes the picture at [source], off the main thread. While a new copy loads (say,
 * after the diff is re-read) the previous one stays on screen.
 */
@Composable
internal fun rememberPicture(source: String): State<PictureState> {
  val images = LocalMarkdownImages.current
  val density = LocalDensity.current.density
  val address = remember(source, images) { images.resolve(source) }
  return produceState<PictureState>(if (address == null) PictureState.Failed else PictureState.Loading, address, images, density) {
    if (address == null) {
      value = PictureState.Failed
      return@produceState
    }
    val bytes = try {
      images.read(address)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
    value = if (bytes == null || bytes.isEmpty()) {
      PictureState.Failed
    } else {
      withContext(Dispatchers.Default) { decodePicture(bytes, density) }?.let(PictureState::Ready) ?: PictureState.Failed
    }
  }
}

/**
 * SVG by its content, anything else as a bitmap; null when it isn't a picture at all. An SVG with
 * text is drawn into a bitmap at [density], since Skia's SVG renderer would leave the text out.
 */
@OptIn(ExperimentalResourceApi::class)
internal fun decodePicture(bytes: ByteArray, density: Float): Picture? = try {
  val svg = looksLikeSvg(bytes)
  val withText = if (svg && svgHasText(bytes)) rasterizeSvg(bytes, scale = maxOf(density, 2f)) else null
  if (withText != null) {
    Picture.Raster(withText.image, withText.width, withText.height)
  } else if (svg) {
    val painter = bytes.decodeToSvgPainter(Density(1f))
    // Browsers give an SVG without its own size 300 × 150.
    val size = painter.intrinsicSize.takeIf { it.isSpecified && it.width > 0f && it.height > 0f }
    Picture.Vector(painter, size?.width ?: 300f, size?.height ?: 150f)
  } else {
    Picture.Raster(bytes.decodeToImageBitmap())
  }
} catch (e: CancellationException) {
  throw e
} catch (_: Exception) {
  null
}

private fun looksLikeSvg(bytes: ByteArray): Boolean {
  val head = bytes.decodeToString(0, minOf(bytes.size, 4096)).trimStart('﻿', ' ', '\t', '\r', '\n')
  return head.startsWith("<svg") || ((head.startsWith("<?xml") || head.startsWith("<!--") || head.startsWith("<!DOCTYPE")) && "<svg" in head)
}
