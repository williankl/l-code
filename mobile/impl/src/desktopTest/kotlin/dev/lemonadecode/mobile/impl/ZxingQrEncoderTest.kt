package dev.lemonadecode.mobile.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZxingQrEncoderTest {
  @Test
  fun `a pairing payload becomes a code with no quiet zone`() {
    val code = ZxingQrEncoder().encode("WIFI:T:ADB;S:lemonade-abc123;P:abcdefghijkl;;")
    // Version 3 at level M holds 42 bytes; this payload needs version 3, 29 modules a side.
    assertEquals(0, (code.size - 21) % 4)
    assertTrue(code.size >= 25)
    // The three finder patterns start at the very edge: a dark 7 × 7 ring, a light ring, a dark centre.
    for ((left, top) in listOf(0 to 0, code.size - 7 to 0, 0 to code.size - 7)) {
      assertTrue(code.isDark(left, top) && code.isDark(left + 6, top + 6), "ring at $left,$top")
      assertFalse(code.isDark(left + 1, top + 1), "light ring at $left,$top")
      assertTrue(code.isDark(left + 3, top + 3), "centre at $left,$top")
    }
    // The timing pattern between two finders alternates.
    assertEquals((8 until code.size - 8).map { it % 2 == 0 }, (8 until code.size - 8).map { code.isDark(it, 6) })
  }
}
