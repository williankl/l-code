package dev.lemonadecode.sessions.impl

import dev.lemonadecode.domain.session.BranchName
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class WorktreeNamesTest {
  @Test
  fun `suggestions are two words under worktree, and git takes them`() {
    val random = Random(7)
    repeat(200) {
      val name = WorktreeNames.suggest(random)
      assertTrue(Regex("worktree/[a-z]+-[a-z]+").matches(name), name)
      assertTrue(BranchName.isValid(name), name)
    }
  }

  @Test
  fun `suggestions vary`() {
    val random = Random(7)
    val names = List(50) { WorktreeNames.suggest(random) }.toSet()
    assertTrue(names.size > 30, "only ${names.size} different names in 50: $names")
  }
}
