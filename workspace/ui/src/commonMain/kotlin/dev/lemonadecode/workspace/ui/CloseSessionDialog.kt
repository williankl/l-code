package dev.lemonadecode.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.teya.lemonade.Button
import com.teya.lemonade.Icon
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.Text
import com.teya.lemonade.core.LemonadeAssetSize
import com.teya.lemonade.core.LemonadeButtonSize
import com.teya.lemonade.core.LemonadeButtonType
import com.teya.lemonade.core.LemonadeButtonVariant
import com.teya.lemonade.core.LemonadeIcons
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.platform.designsystem.ModalDialog
import dev.lemonadecode.platform.designsystem.ModalDialogActions
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.action_cancel
import dev.lemonadecode.workspace.strings.close_session_branch_stays
import dev.lemonadecode.workspace.strings.close_session_branches_stay
import dev.lemonadecode.workspace.strings.close_session_changes
import dev.lemonadecode.workspace.strings.close_session_delete_worktree
import dev.lemonadecode.workspace.strings.close_session_delete_worktrees
import dev.lemonadecode.workspace.strings.close_session_detached
import dev.lemonadecode.workspace.strings.close_session_folder_goes
import dev.lemonadecode.workspace.strings.close_session_keep_worktree
import dev.lemonadecode.workspace.strings.close_session_keep_worktrees
import dev.lemonadecode.workspace.strings.close_session_sub_session
import dev.lemonadecode.workspace.strings.close_session_sub_sessions
import dev.lemonadecode.workspace.strings.close_session_title
import dev.lemonadecode.workspace.strings.close_session_worktree
import dev.lemonadecode.workspace.strings.close_session_worktrees
import dev.lemonadecode.workspace.uimodel.CloseSessionDialogModel
import dev.lemonadecode.workspace.uimodel.ClosingWorktreeModel
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import org.jetbrains.compose.resources.stringResource

/**
 * Asks, before a session closes, whether to delete the worktrees closing it leaves unused. It names
 * the session and each worktree with its branch, says which have changes deleting would lose, and
 * that only the folders go. Esc or Cancel keeps the session open.
 */
@Composable
internal fun CloseSessionDialog(
  dialog: CloseSessionDialogModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val spaces = LemonadeTheme.spaces
  val colors = LemonadeTheme.colors
  val several = dialog.worktrees.size > 1
  val cancel = { onEvent(WorkspaceEvent.CancelCloseSession) }
  ModalDialog(onDismissRequest = cancel) {
    Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing100)) {
      LemonadeUi.Text(
        text = stringResource(Res.string.close_session_title, dialog.sessionName),
        textStyle = LemonadeTheme.typography.headingXXSmall,
      )
      val subSessions = when (dialog.subSessionCount) {
        0 -> null
        1 -> stringResource(Res.string.close_session_sub_session)
        else -> stringResource(Res.string.close_session_sub_sessions)
      }
      val question = stringResource(if (several) Res.string.close_session_worktrees else Res.string.close_session_worktree)
      LemonadeUi.Text(
        text = listOfNotNull(subSessions, question).joinToString(" "),
        textStyle = LemonadeTheme.typography.bodySmallRegular,
        color = colors.content.contentSecondary,
      )
    }
    Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing200)) {
      dialog.worktrees.forEach { ClosingWorktree(it) }
    }
    val branch = dialog.worktrees.firstOrNull()?.branch
    LemonadeUi.Text(
      text = when {
        several -> stringResource(Res.string.close_session_branches_stay)
        branch != null -> stringResource(Res.string.close_session_branch_stays, branch)
        else -> stringResource(Res.string.close_session_folder_goes)
      },
      textStyle = LemonadeTheme.typography.bodyXSmallRegular,
      color = colors.content.contentSecondary,
    )
    ModalDialogActions {
      LemonadeUi.Button(
        label = stringResource(Res.string.action_cancel),
        onClick = cancel,
        variant = LemonadeButtonVariant.Neutral,
        type = LemonadeButtonType.Ghost,
        size = LemonadeButtonSize.Small,
      )
      LemonadeUi.Button(
        label = stringResource(if (several) Res.string.close_session_keep_worktrees else Res.string.close_session_keep_worktree),
        onClick = { onEvent(WorkspaceEvent.ConfirmCloseSession(deleteWorktrees = false)) },
        variant = LemonadeButtonVariant.Neutral,
        type = LemonadeButtonType.Subtle,
        size = LemonadeButtonSize.Small,
      )
      LemonadeUi.Button(
        label = stringResource(if (several) Res.string.close_session_delete_worktrees else Res.string.close_session_delete_worktree),
        onClick = { onEvent(WorkspaceEvent.ConfirmCloseSession(deleteWorktrees = true)) },
        leadingIcon = LemonadeIcons.Trash,
        variant = LemonadeButtonVariant.Critical,
        type = LemonadeButtonType.Solid,
        size = LemonadeButtonSize.Small,
      )
    }
  }
}

/** A worktree's folder and branch, and a warning when deleting it would lose changes. */
@Composable
private fun ClosingWorktree(worktree: ClosingWorktreeModel) {
  val spaces = LemonadeTheme.spaces
  val colors = LemonadeTheme.colors
  val mono = LemonadeCodeTheme.typography.codeSmall
  Column(
    verticalArrangement = Arrangement.spacedBy(2.dp),
    modifier = Modifier
      .fillMaxWidth()
      .background(colors.background.bgSubtle, LemonadeTheme.shapes.radius300)
      .padding(horizontal = spaces.spacing300, vertical = spaces.spacing200),
  ) {
    Mono(text = worktree.path, style = mono, color = LemonadeCodeTheme.code.path)
    Mono(
      text = worktree.branch ?: stringResource(Res.string.close_session_detached),
      style = mono,
      color = if (worktree.branch != null) LemonadeCodeTheme.code.branch else colors.content.contentSecondary,
    )
    if (worktree.hasChanges) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spaces.spacing100),
        modifier = Modifier.padding(top = spaces.spacing100),
      ) {
        LemonadeUi.Icon(
          icon = LemonadeIcons.TriangleAlert,
          contentDescription = null,
          size = LemonadeAssetSize.Small,
          tint = colors.content.contentCritical,
        )
        LemonadeUi.Text(
          text = stringResource(Res.string.close_session_changes),
          textStyle = LemonadeTheme.typography.bodySmallMedium,
          color = colors.content.contentCritical,
        )
      }
    }
  }
}
