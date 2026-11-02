package dev.lemonadecode.workspace.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.TextField
import dev.lemonadecode.platform.localization.resolve
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.worktree_branch_field
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import dev.lemonadecode.workspace.uimodel.WorktreeBranchModel
import kotlinx.coroutines.flow.drop
import org.jetbrains.compose.resources.stringResource

/**
 * The field naming a new worktree's branch. It takes the focus with the part after the last `/`
 * selected, so typing replaces `generating-bugs` and keeps `worktree/`. Enter creates, when the name
 * can be used; Esc cancels. Edits are reported as they happen, for the folder shown with it.
 */
@Composable
internal fun WorktreeBranchField(
  branch: WorktreeBranchModel,
  onCreate: () -> Unit,
  onCancel: () -> Unit,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  // The field keeps its own text and caret, as the comment composer does: fed back from the Model,
  // it would redraw against older text while the Model caught up.
  val state = remember { TextFieldState(branch.name, TextRange(branch.name.lastIndexOf('/') + 1, branch.name.length)) }
  val currentOnEvent by rememberUpdatedState(onEvent)
  LaunchedEffect(state) {
    snapshotFlow { state.text.toString() }
      .drop(1)
      .collect { currentOnEvent(WorkspaceEvent.SetWorktreeBranch(it)) }
  }
  val create = { if (branch.canCreate) onCreate() }
  val focusRequester = remember { FocusRequester() }
  val problem = branch.problem?.resolve()
  LemonadeUi.TextField(
    state = state,
    label = stringResource(Res.string.worktree_branch_field),
    errorMessage = problem,
    error = problem != null,
    onKeyboardAction = { create() },
    modifier = modifier
      .focusRequester(focusRequester)
      .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
          Key.Enter, Key.NumPadEnter -> {
            create()
            true
          }
          Key.Escape -> {
            onCancel()
            true
          }
          else -> false
        }
      },
  )
  LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
