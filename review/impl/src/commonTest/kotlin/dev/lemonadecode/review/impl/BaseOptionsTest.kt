package dev.lemonadecode.review.impl

import kotlin.test.Test
import kotlin.test.assertEquals

class BaseOptionsTest {
  @Test
  fun `the default branch comes first and the parent's second, each once`() {
    assertEquals(listOf("main", "feat/x", "alpha", "zeta"), baseOptions("main", "feat/x", listOf("alpha", "feat/x", "main", "zeta")))
  }

  @Test
  fun `without a parent the default branch leads the list`() {
    assertEquals(listOf("main", "alpha"), baseOptions("main", null, listOf("alpha", "main")))
  }

  @Test
  fun `a parent on the default branch isn't listed twice`() {
    assertEquals(listOf("main", "alpha"), baseOptions("main", "main", listOf("alpha")))
  }
}
