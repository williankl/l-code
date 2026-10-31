package dev.loupe.workspace.impl

import dev.loupe.platform.localization.LoupeString
import dev.loupe.platform.localization.asLoupeString
import dev.loupe.review.api.ReviewError
import dev.loupe.sessions.api.SessionError
import dev.loupe.workspace.strings.Res
import dev.loupe.workspace.strings.error_folder_missing
import dev.loupe.workspace.strings.error_launch
import dev.loupe.workspace.strings.error_not_repo
import dev.loupe.workspace.strings.error_preview
import dev.loupe.workspace.strings.error_send
import dev.loupe.workspace.strings.error_worktree
import dev.loupe.workspace.strings.message_terminal_closed

/** What the user reads when a session or review action fails. */
internal object ErrorMessages {
  fun of(error: SessionError): LoupeString = when (error) {
    is SessionError.FolderNotFound -> Res.string.error_folder_missing.asLoupeString(error.path)
    SessionError.NotARepository -> Res.string.error_not_repo.asLoupeString()
    SessionError.UnknownSession -> Res.string.message_terminal_closed.asLoupeString()
    is SessionError.WorktreeFailed -> Res.string.error_worktree.asLoupeString(error.message)
    is SessionError.LaunchFailed -> Res.string.error_launch.asLoupeString(error.message)
  }

  /** Null when there's nothing worth telling, like sending with no comments. */
  fun of(error: ReviewError): LoupeString? = when (error) {
    ReviewError.NothingToSend -> null
    ReviewError.UnknownSession -> Res.string.message_terminal_closed.asLoupeString()
    is ReviewError.DeliveryFailed -> Res.string.error_send.asLoupeString(error.message)
    is ReviewError.ReadFailed -> Res.string.error_preview.asLoupeString(error.message)
  }
}
