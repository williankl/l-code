package dev.lemonadecode.domain.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreviewKindTest {
  @Test
  fun `videos are told apart by their extension, in any case`() {
    listOf("demo.mp4", "clips/intro.MOV", "a.m4v", "b.webm", "c.mkv", "d.avi", "e.ogv", "f.mpg", "g.mpeg").forEach { path ->
      assertEquals(PreviewKind.Video, PreviewKind.of(path), path)
    }
  }

  @Test
  fun `a ts file is TypeScript, not a video`() {
    assertNull(PreviewKind.of("src/app.ts"))
  }

  @Test
  fun `animated pictures stay pictures`() {
    assertEquals(PreviewKind.Image, PreviewKind.of("docs/demo.gif"))
    assertEquals(PreviewKind.Image, PreviewKind.of("docs/demo.webp"))
  }
}
