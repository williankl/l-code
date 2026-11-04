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
  fun `sound files are told apart by their extension, in any case`() {
    listOf(
      "sounds/ding.wav", "a.WAVE", "b.mp3", "c.M4A", "d.aac", "e.ogg", "f.oga", "g.opus", "h.flac",
      "i.aif", "j.aiff", "k.aifc", "l.caf", "m.wma", "n.mka", "o.weba", "p.amr", "q.ac3",
    ).forEach { path ->
      assertEquals(PreviewKind.Audio, PreviewKind.of(path), path)
    }
  }

  @Test
  fun `an Ogg video stays a video`() {
    assertEquals(PreviewKind.Video, PreviewKind.of("clips/intro.ogv"))
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
