package dev.lemonadecode.domain.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BranchNameTest {
  @Test
  fun `names git takes for a branch are valid`() {
    listOf("main", "worktree/generating-bugs", "feat/rate-limit-sub-1", "fix_42", "a/b/c", "v1.2", "über-feature").forEach {
      assertTrue(BranchName.isValid(it), it)
    }
  }

  @Test
  fun `names git refuses are not`() {
    listOf(
      "",
      "my branch",
      "-starts-with-dash",
      "worktree/",
      "/worktree",
      "worktree//bugs",
      "two..dots",
      "ends.",
      "ends.lock",
      "worktree/.hidden",
      "worktree/x.lock/y",
      "tilde~",
      "caret^",
      "colon:",
      "question?",
      "star*",
      "bracket[",
      "back\\slash",
      "at@{brace",
      "@",
      "tab\there",
      "del\u007F",
    ).forEach { assertFalse(BranchName.isValid(it), it) }
  }
}
