package dev.lemonadecode.domain.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppVersionTest {
  @Test
  fun `release tags and plain versions parse`() {
    assertEquals(AppVersion(1, 2, 3), AppVersion.parse("v1.2.3"))
    assertEquals(AppVersion(10, 0, 12), AppVersion.parse("10.0.12"))
  }

  @Test
  fun `anything else doesn't`() {
    listOf("", "1.2", "1.2.3.4", "1.2.3-beta", "v1.x.3", "1..3", "latest").forEach { assertNull(AppVersion.parse(it), it) }
  }

  @Test
  fun `versions compare number by number`() {
    assertTrue(AppVersion(1, 10, 0) > AppVersion(1, 9, 9))
    assertTrue(AppVersion(2, 0, 0) > AppVersion(1, 99, 99))
    assertTrue(AppVersion(1, 2, 4) > AppVersion(1, 2, 3))
    assertEquals("1.2.3", AppVersion(1, 2, 3).toString())
  }
}
