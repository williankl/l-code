package dev.lemonadecode.workspace.ui.preview

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.workspace.uimodel.PreviewAssetLoader
import dev.lemonadecode.workspace.uimodel.PreviewVersion
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Pictures in the Markdown preview: where they're read from, and how each kind is decoded. */
@OptIn(ExperimentalTestApi::class)
class MarkdownPicturesTest {
  private class Loader(
    private val files: Map<String, ByteArray> = emptyMap(),
    private val web: Map<String, ByteArray> = emptyMap(),
  ) : PreviewAssetLoader {
    val asked = mutableListOf<Pair<String, PreviewVersion>>()

    override suspend fun checkoutFile(path: String, version: PreviewVersion): ByteArray? {
      asked += path to version
      return files[path]
    }

    override suspend fun webImage(url: String): ByteArray? = web[url]
  }

  private fun picture(description: String) = hasContentDescription(description) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image)

  @Test
  fun `pictures next to the document load from its side of the change`() = runDesktopComposeUiTest(width = 800, height = 600) {
    val loader = Loader(files = mapOf("docs/images/shot.png" to png(8, 4)))
    val markdown = "![A screenshot](images/shot.png)\n\n![Gone](images/missing.png)"
    setContent {
      LemonadeCodeTheme {
        MarkdownPreview(markdown.encodeToByteArray(), images = MarkdownImageSource("docs/GUIDE.md", PreviewVersion.Before, loader))
      }
    }
    waitUntil(timeoutMillis = 5_000) { onAllNodes(picture("A screenshot")).fetchSemanticsNodes().isNotEmpty() }
    waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Image: Gone")).fetchSemanticsNodes().isNotEmpty() }
    assertEquals(setOf("docs/images/shot.png" to PreviewVersion.Before, "docs/images/missing.png" to PreviewVersion.Before), loader.asked.toSet())
  }

  @Test
  fun `badges come from the web, inside an html block`() = runDesktopComposeUiTest(width = 800, height = 600) {
    val loader = Loader(web = mapOf("https://img.shields.io/badge/price-free" to BadgeSvg.encodeToByteArray()))
    val markdown = "<div align=\"center\">\n\n[![Free](https://img.shields.io/badge/price-free)](https://example.com)\n\n</div>"
    setContent {
      LemonadeCodeTheme { MarkdownPreview(markdown.encodeToByteArray(), images = MarkdownImageSource("README.md", PreviewVersion.After, loader)) }
    }
    waitUntil(timeoutMillis = 10_000) { onAllNodes(picture("Free")).fetchSemanticsNodes().isNotEmpty() }
  }

  @Test
  fun `each kind of picture is decoded the way it draws best`() {
    assertIs<Picture.Raster>(decodePicture(png(8, 4), density = 1f)).let { assertEquals(8f, it.width) }
    // Skia drops SVG text, so an SVG with text is drawn into a bitmap, at its own size.
    assertIs<Picture.Raster>(decodePicture(BadgeSvg.encodeToByteArray(), density = 2f)).let {
      assertEquals(60f, it.width)
      assertEquals(20f, it.height)
      assertEquals(120, it.image.width)
    }
    assertIs<Picture.Vector>(decodePicture("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"5\"/>".encodeToByteArray(), density = 1f))
    assertIs<Picture.Animated>(decodePicture(Gif, density = 1f)).animation.close()
    assertNull(decodePicture("not a picture".encodeToByteArray(), density = 1f))
  }

  private fun png(width: Int, height: Int): ByteArray = Surface.makeRasterN32Premul(width, height).use { surface ->
    requireNotNull(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)).bytes
  }

  private companion object {
    const val BadgeSvg = """<svg xmlns="http://www.w3.org/2000/svg" width="60" height="20"><rect width="60" height="20" fill="#28310b"/><text x="30" y="14" fill="#fff" text-anchor="middle" font-size="11">free</text></svg>"""

    val Gif = Base64.decode(
      "R0lGODlhBAAEAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQECgAAACwAAAAABAAEAAAICQABCBxIsCCAgAAh+QQFFAACACwAAAAAAgAC" +
        "AIH/AAAAAP8AAAAAAAAIBgADCAwQEAAh+QQFAAABACwAAAAABAAEAIEA/wAAAAAAAAAAAAAICQABCBxIsCCAgAA7",
    )
  }
}
