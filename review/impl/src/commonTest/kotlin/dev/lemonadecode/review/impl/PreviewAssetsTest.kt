package dev.lemonadecode.review.impl

import dev.lemonadecode.domain.review.FileContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewAssetsTest {
  @Test
  fun `checkout paths stay inside the checkout`() {
    assertTrue(isCheckoutPath("docs/images/hero.png"))
    assertTrue(isCheckoutPath("a b/ç.png"))
    listOf("", "/abs", "../up", "a/../b", "./a", "a//b", "a\\b", "C:/x", "rev:path").forEach { assertFalse(isCheckoutPath(it), it) }
  }

  @Test
  fun `urls are made plain ASCII`() {
    assertEquals(
      "https://img.shields.io/badge/runs_on-macOS_%C2%B7_Linux-E1E51A?style=flat",
      asciiUrl("https://img.shields.io/badge/runs_on-macOS_·_Linux-E1E51A?style=flat"),
    )
    assertEquals("https://x.dev/built_with-%F0%9F%8D%8B_Lemonade%20now", asciiUrl("https://x.dev/built_with-🍋_Lemonade now"))
    assertEquals("https://x.dev/a%20b?q=1%25", asciiUrl("https://x.dev/a%20b?q=1%25"))
  }

  @Test
  fun `the cache keeps the most recently used pictures within its limits`() = runTest {
    val cache = WebImageCache(maxEntries = 2, maxBytes = 10)
    cache.put("a", FileContent.Bytes(ByteArray(4)))
    cache.put("b", FileContent.Bytes(ByteArray(4)))
    cache.get("a")
    cache.put("c", FileContent.Bytes(ByteArray(4)))
    assertNull(cache.get("b"))
    assertEquals(4L, cache.get("a")?.sizeBytes)
    cache.put("huge", FileContent.Bytes(ByteArray(11)))
    assertNull(cache.get("huge"))
    cache.put("d", FileContent.Bytes(ByteArray(8)))
    assertNull(cache.get("a"))
    assertNull(cache.get("c"))
    assertEquals(8L, cache.get("d")?.sizeBytes)
  }
}
