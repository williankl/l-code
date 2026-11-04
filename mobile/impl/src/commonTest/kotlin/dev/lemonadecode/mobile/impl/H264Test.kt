package dev.lemonadecode.mobile.impl

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class H264Test {
  private val sps = nal(7, 0x42, 0xC0, 0x29)
  private val pps = nal(8, 0xCE, 0x01)
  private val idr = picture(idr = true)
  private val next = picture()

  @Test
  fun `parameter sets go with the picture after them, and a picture ends where the next starts`() {
    val splitter = AccessUnitSplitter()
    val units = splitter.feed(sps + pps + idr + next)
    assertEquals(1, units.size)
    assertContentEquals(sps + pps + idr, units.single())
    // Nothing says the second picture is whole until the stream goes quiet.
    assertContentEquals(next, splitter.flush())
    assertNull(splitter.flush())
  }

  @Test
  fun `a stream fed a byte at a time gives the same units`() {
    val splitter = AccessUnitSplitter()
    val stream = sps + pps + idr + next + next
    val units = stream.indices.flatMap { splitter.feed(byteArrayOf(stream[it])) } + listOfNotNull(splitter.flush())
    assertEquals(listOf(sps + pps + idr, next, next).map { it.toList() }, units.map { it.toList() })
  }

  @Test
  fun `more slices of the same picture stay in its unit`() {
    // first_mb_in_slice isn't 0: its first bit is clear.
    val secondSlice = nal(1, 0x40, 0x11)
    val splitter = AccessUnitSplitter()
    val units = splitter.feed(idr + secondSlice + next)
    assertContentEquals(idr + secondSlice, units.single())
  }

  @Test
  fun `three-byte start codes and bytes before the first start code are dealt with`() {
    val short = byteArrayOf(0, 0, 1, 0x61, 0x88.toByte(), 0x10)
    val splitter = AccessUnitSplitter()
    val units = splitter.feed(byteArrayOf(0x12, 0x34) + short + short)
    assertContentEquals(short, units.single())
  }

  @Test
  fun `bytes that carry on after a flush show it came in the middle of a picture`() {
    val splitter = AccessUnitSplitter()
    splitter.feed(idr.copyOfRange(0, 7))
    splitter.flush()
    splitter.feed(idr.copyOfRange(7, idr.size))
    assertTrue(splitter.cutShort)
  }

  @Test
  fun `a flush followed by the next picture's start code is whole`() {
    val splitter = AccessUnitSplitter()
    splitter.feed(idr)
    splitter.flush()
    // The next start code can come a byte at a time.
    next.forEach { splitter.feed(byteArrayOf(it)) }
    assertFalse(splitter.cutShort)
  }

  @Test
  fun `a flush with only parameter sets gives nothing, and they wait for their picture`() {
    val splitter = AccessUnitSplitter()
    assertEquals(emptyList(), splitter.feed(sps + pps))
    assertNull(splitter.flush())
    splitter.feed(idr)
    assertContentEquals(sps + pps + idr, splitter.flush())
  }
}
