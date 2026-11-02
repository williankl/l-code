package dev.lemonadecode.workspace.impl

import dev.lemonadecode.platform.localization.LemonadeCodeString
import dev.lemonadecode.platform.localization.asLemonadeCodeString
import dev.lemonadecode.review.api.ReviewError
import dev.lemonadecode.sessions.api.SessionError
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.error_folder_missing
import dev.lemonadecode.workspace.strings.error_launch
import dev.lemonadecode.workspace.strings.error_not_repo
import dev.lemonadecode.workspace.strings.error_preview
import dev.lemonadecode.workspace.strings.error_send
import dev.lemonadecode.workspace.strings.error_worktree
import dev.lemonadecode.workspace.strings.message_terminal_closed
import dev.lemonadecode.workspace.strings.worktree_branch_invalid
import dev.lemonadecode.workspace.strings.worktree_branch_taken

/** What the user reads when a session or review action fails. */
internal object ErrorMessages {
  fun of(error: SessionError): LemonadeCodeString = when (error) {
    is SessionError.FolderNotFound -> Res.string.error_folder_missing.asLemonadeCodeString(error.path)
    SessionError.NotARepository -> Res.string.error_not_repo.asLemonadeCodeString()
    SessionError.UnknownSession -> Res.string.message_terminal_closed.asLemonadeCodeString()
    is SessionError.WorktreeFailed -> Res.string.error_worktree.asLemonadeCodeString(error.message)
    is SessionError.BranchExists -> Res.string.worktree_branch_taken.asLemonadeCodeString(error.branch)
    is SessionError.InvalidBranch -> Res.string.worktree_branch_invalid.asLemonadeCodeString()
    is SessionError.LaunchFailed -> Res.string.error_launch.asLemonadeCodeString(error.message)
  }

  /** Null when there's nothing worth telling, like sending with no comments. */
  fun of(error: ReviewError): LemonadeCodeString? = when (error) {
    ReviewError.NothingToSend -> null
    ReviewError.UnknownSession -> Res.string.message_terminal_closed.asLemonadeCodeString()
    is ReviewError.DeliveryFailed -> Res.string.error_send.asLemonadeCodeString(error.message)
    is ReviewError.ReadFailed -> Res.string.error_preview.asLemonadeCodeString(error.message)
  }
}
