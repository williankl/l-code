package dev.lemonadecode.domain.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkedWorktreeTest {
  private fun worktree(path: String) = LinkedWorktree(path = path, branch = "feat/x", mainCheckout = "/code/storefront", hasChanges = false)

  @Test
  fun `holds its own folder and the folders inside it`() {
    val worktree = worktree("/code/storefront.worktrees/sub-1")
    assertTrue(worktree.contains("/code/storefront.worktrees/sub-1"))
    assertTrue(worktree.contains("/code/storefront.worktrees/sub-1/"))
    assertTrue(worktree.contains("/code/storefront.worktrees/sub-1/src/app"))
  }

  @Test
  fun `not a folder that only starts the same, or the main checkout around it`() {
    assertFalse(worktree("/code/storefront.worktrees/sub-1").contains("/code/storefront.worktrees/sub-10"))
    assertFalse(worktree("/code/storefront/.claude/worktrees/agent-1").contains("/code/storefront"))
  }

  @Test
  fun `on Windows, whichever way the slashes go and whatever the case`() {
    val worktree = worktree("C:/Users/you/code/storefront.worktrees/sub-1")
    assertTrue(worktree.contains("c:\\users\\you\\code\\storefront.worktrees\\sub-1\\src"))
    assertFalse(worktree.contains("C:\\Users\\you\\code\\storefront"))
  }
}
