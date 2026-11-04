package dev.lemonadecode.domain.session

/**
 * A git worktree a session can be in: a checkout `git worktree add` made, never a repository's
 * main one. A new-worktree sub-session makes one; an agent like Claude Code can make its own.
 */
public data class LinkedWorktree(
  /** Its top-level folder, absolute. */
  val path: String,
  /** Its branch; null on a detached HEAD. */
  val branch: String?,
  /** The repository's main checkout (or bare repository) it belongs to. */
  val mainCheckout: String,
  /** It has uncommitted changes or untracked files, which deleting it would lose. */
  val hasChanges: Boolean,
) {
  /** Whether [folder] is this worktree's folder or inside it. */
  public fun contains(folder: String): Boolean {
    val root = path.normalizedPath()
    val inner = folder.normalizedPath()
    // Windows paths ignore case, and git writes them with forward slashes.
    val windows = root.getOrNull(1) == ':'
    return inner.equals(root, ignoreCase = windows) || inner.startsWith("$root/", ignoreCase = windows)
  }
}

private fun String.normalizedPath(): String = replace('\\', '/').trimEnd('/')
