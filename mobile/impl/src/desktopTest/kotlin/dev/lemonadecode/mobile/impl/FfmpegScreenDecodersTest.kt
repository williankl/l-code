package dev.lemonadecode.mobile.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FfmpegScreenDecodersTest {
  /** 12 pictures of an emulator's home screen, recorded with `screenrecord --output-format=h264 --size 320x200`. */
  private val recording = checkNotNull(javaClass.getResourceAsStream("/screenrecord-320x200.h264")).use { it.readBytes() }

  @Test
  fun `a screen recording decodes to every picture in it, as BGRA, however its bytes arrive`() {
    for (chunk in listOf(recording.size, 4096, 7, 1)) {
      val splitter = AccessUnitSplitter()
      FfmpegScreenDecoders().h264().use { decoder ->
        val units = recording.asList().chunked(chunk).flatMap { splitter.feed(it.toByteArray()) } + listOfNotNull(splitter.flush())
        val pictures = units.flatMap(decoder::decode)
        assertEquals(12, pictures.size, "in chunks of $chunk")
        val first = pictures.first()
        assertEquals(320 to 200, first.width to first.height)
        assertEquals(320 * 4, first.rowBytes)
        assertEquals(320 * 200 * 4, first.pixels.size)
        // Opaque, and not one flat colour: the home screen has icons on it.
        assertTrue((3 until first.pixels.size step 4).all { first.pixels[it] == (-1).toByte() })
        assertTrue(first.pixels.asList().chunked(4).distinct().size > 100)
      }
    }
  }

  @Test
  fun `bytes that aren't H264 give no pictures and don't stop the decoder`() {
    FfmpegScreenDecoders().h264().use { decoder ->
      assertEquals(0, decoder.decode(byteArrayOf(0, 0, 0, 1, 0x65, 0x12, 0x34)).size)
      val splitter = AccessUnitSplitter()
      val units = splitter.feed(recording) + listOfNotNull(splitter.flush())
      assertEquals(12, units.flatMap(decoder::decode).size)
    }
  }
}
