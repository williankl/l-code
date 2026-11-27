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
  public fun contains(folder: String): Boolean = folder.isInFolder(path)
}

/** Whether this folder is [root] or a folder inside it, both absolute. */
public fun String.isInFolder(root: String): Boolean {
  val outer = root.normalizedPath()
  val inner = normalizedPath()
  // Windows paths ignore case, and git writes them with forward slashes.
  val windows = outer.getOrNull(1) == ':'
  return inner.equals(outer, ignoreCase = windows) || inner.startsWith("$outer/", ignoreCase = windows)
}

private fun String.normalizedPath(): String = replace('\\', '/').trimEnd('/')
