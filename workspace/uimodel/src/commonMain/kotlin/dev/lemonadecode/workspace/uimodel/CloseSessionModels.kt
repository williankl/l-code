package dev.lemonadecode.workspace.uimodel

import androidx.compose.runtime.Immutable

/**
 * Asked before a session closes when closing it leaves git worktrees with no session in them: its
 * own, and its sub-sessions', unless a session that stays open uses them. Close and delete them,
 * close and keep them, or cancel.
 */
@Immutable
public data class CloseSessionDialogModel(
  /** The session being closed, as the list names it. */
  val sessionName: String,
  /** How many sub-sessions close with it. */
  val subSessionCount: Int,
  val worktrees: List<ClosingWorktreeModel>,
)

/** A worktree the dialog offers to delete. */
@Immutable
public data class ClosingWorktreeModel(
  /** Its folder, with `~` for home. */
  val path: String,
  /** Its branch, which stays either way; null on a detached HEAD. */
  val branch: String?,
  /** It has uncommitted changes or untracked files, which deleting it loses. */
  val hasChanges: Boolean,
)
