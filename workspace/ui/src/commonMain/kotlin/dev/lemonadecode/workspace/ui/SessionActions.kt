package dev.lemonadecode.workspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.teya.lemonade.Button
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.TextField
import com.teya.lemonade.core.LemonadeButtonSize
import com.teya.lemonade.core.LemonadeButtonType
import com.teya.lemonade.core.LemonadeButtonVariant
import com.teya.lemonade.core.LemonadeIcons
import dev.lemonadecode.platform.designsystem.ContextMenuDivider
import dev.lemonadecode.platform.designsystem.ContextMenuHeader
import dev.lemonadecode.platform.designsystem.ContextMenuItem
import dev.lemonadecode.platform.designsystem.ContextMenuPopover
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.action_cancel
import dev.lemonadecode.workspace.strings.action_create_sub_session
import dev.lemonadecode.workspace.strings.session_close
import dev.lemonadecode.workspace.strings.session_copy_path
import dev.lemonadecode.workspace.strings.session_name_field
import dev.lemonadecode.workspace.strings.session_new_sub_same_folder
import dev.lemonadecode.workspace.strings.session_new_sub_worktree
import dev.lemonadecode.workspace.strings.session_options
import dev.lemonadecode.workspace.strings.session_rename
import dev.lemonadecode.workspace.strings.session_rename_action
import dev.lemonadecode.workspace.strings.session_sub_new_branch
import dev.lemonadecode.workspace.strings.session_sub_same_folder
import dev.lemonadecode.workspace.strings.session_sub_same_folder_branch
import dev.lemonadecode.workspace.strings.session_worktree_needs_repo
import dev.lemonadecode.workspace.uimodel.SessionMenuModel
import dev.lemonadecode.workspace.uimodel.SessionRenameModel
import dev.lemonadecode.workspace.uimodel.SubSessionLocation
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import dev.lemonadecode.workspace.uimodel.WorktreeBranchModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The accessibility actions every session row and tile offers, resolved once per list. */
internal class SessionActionLabels(
  val options: String,
  val rename: String,
  val close: String,
)

@Composable
internal fun sessionActionLabels(): SessionActionLabels = SessionActionLabels(
  options = stringResource(Res.string.session_options),
  rename = stringResource(Res.string.session_rename_action),
  close = stringResource(Res.string.session_close),
)

/**
 * What a session row or tile does besides selecting: middle click closes the session, right click
 * opens its menu where the pointer is ([menuAt]), and the same actions are offered to assistive
 * technology. Place it before the `clickable`, so those clicks never select.
 */
internal fun Modifier.sessionActions(
  sessionId: String,
  labels: SessionActionLabels,
  menuAt: MutableState<IntOffset?>,
  onEvent: (WorkspaceEvent) -> Unit,
): Modifier = semantics {
  customActions = listOf(
    CustomAccessibilityAction(labels.options) {
      menuAt.value = null
      onEvent(WorkspaceEvent.OpenSessionMenu(sessionId))
      true
    },
    CustomAccessibilityAction(labels.rename) {
      menuAt.value = null
      onEvent(WorkspaceEvent.StartRenamingSession(sessionId))
      true
    },
    CustomAccessibilityAction(labels.close) {
      onEvent(WorkspaceEvent.CloseSession(sessionId))
      true
    },
  )
}.pointerInput(sessionId, onEvent) {
  awaitPointerEventScope {
    var middlePressed = false
    while (true) {
      val event = awaitPointerEvent(PointerEventPass.Initial)
      val change = event.changes.firstOrNull() ?: continue
      when (event.type) {
        PointerEventType.Press -> when {
          event.buttons.isSecondaryPressed -> {
            change.consume()
            menuAt.value = change.position.round()
            onEvent(WorkspaceEvent.OpenSessionMenu(sessionId))
          }
          event.buttons.isTertiaryPressed -> {
            change.consume()
            middlePressed = true
          }
        }
        // Like a browser tab: closes on release, unless the pointer left first.
        PointerEventType.Release -> if (middlePressed && !event.buttons.isTertiaryPressed) {
          middlePressed = false
          change.consume()
          val position = change.position
          if (position.x in 0f..size.width.toFloat() && position.y in 0f..size.height.toFloat()) {
            onEvent(WorkspaceEvent.CloseSession(sessionId))
          }
        }
      }
    }
  }
}

/**
 * The right-click menu of a session, opened at [offset] inside its row or tile; just under the top
 * when it was opened without the pointer. It names the session and its folder, then offers its
 * actions; Rename swaps the actions for a name field in the same panel, and a new worktree for a
 * field naming its branch.
 */
@Composable
internal fun SessionMenu(
  menu: SessionMenuModel,
  offset: IntOffset?,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val fallback = with(LocalDensity.current) { IntOffset(0, 40.dp.roundToPx()) }
  // Read out here: inside the popup it would be the popup's own, which tests can't stand in for.
  val clipboard = LocalClipboard.current
  ContextMenuPopover(offset = offset ?: fallback, onDismissRequest = { onEvent(WorkspaceEvent.DismissSessionMenu) }) {
    ContextMenuHeader(title = menu.title, detail = menu.location)
    val rename = menu.rename
    val newWorktree = menu.newWorktree
    when {
      rename != null -> RenameForm(sessionId = menu.sessionId, rename = rename, onEvent = onEvent)
      newWorktree != null -> NewWorktreeForm(sessionId = menu.sessionId, branch = newWorktree, onEvent = onEvent)
      else -> SessionMenuItems(menu, clipboard, onEvent)
    }
  }
}

@Composable
private fun SessionMenuItems(
  menu: SessionMenuModel,
  clipboard: Clipboard,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val scope = rememberCoroutineScope()
  val id = menu.sessionId
  ContextMenuItem(
    label = stringResource(Res.string.session_rename),
    icon = LemonadeIcons.SquarePencil,
    onClick = { onEvent(WorkspaceEvent.StartRenamingSession(id)) },
  )
  ContextMenuItem(
    label = stringResource(Res.string.session_copy_path),
    icon = LemonadeIcons.Copy,
    onClick = {
      scope.launch {
        // The menu closes once the text is on the clipboard; closing first would cancel this.
        val _ = runCatching { clipboard.setClipEntry(plainTextClipEntry(menu.folderPath)) }
        onEvent(WorkspaceEvent.CopiedSessionPath(menu.folderPath))
      }
    },
  )
  if (menu.canStartSubSession) {
    ContextMenuDivider()
    ContextMenuItem(
      label = stringResource(Res.string.session_new_sub_same_folder),
      icon = LemonadeIcons.ArrowSplit,
      detail = menu.branch?.let { stringResource(Res.string.session_sub_same_folder_branch, it) }
        ?: stringResource(Res.string.session_sub_same_folder),
      onClick = { onEvent(WorkspaceEvent.CreateSubSessionFrom(id, SubSessionLocation.SameFolder)) },
    )
    val branch = menu.branch
    ContextMenuItem(
      label = stringResource(Res.string.session_new_sub_worktree),
      icon = LemonadeIcons.ArrowSplit,
      detail = when {
        !menu.worktreeAvailable -> stringResource(Res.string.session_worktree_needs_repo)
        branch != null -> stringResource(Res.string.session_sub_new_branch, branch)
        else -> null
      },
      enabled = menu.worktreeAvailable,
      onClick = { onEvent(WorkspaceEvent.StartNamingWorktree(id)) },
    )
  }
  // Last, and apart: like a middle click, it ends the session and its sub-sessions.
  ContextMenuDivider()
  ContextMenuItem(
    label = stringResource(Res.string.session_close),
    icon = LemonadeIcons.Trash,
    critical = true,
    onClick = { onEvent(WorkspaceEvent.CloseSession(id)) },
  )
}

/** Text on the clipboard as plain text. */
internal expect fun plainTextClipEntry(text: String): ClipEntry

/**
 * The name field the menu turns into. It starts with the whole name selected, so typing replaces
 * it; Enter or Rename saves, Esc or Cancel closes. A blank name can't be saved.
 */
@Composable
private fun RenameForm(
  sessionId: String,
  rename: SessionRenameModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val spaces = LemonadeTheme.spaces
  val state = remember(sessionId) { TextFieldState(rename.name, TextRange(0, rename.name.length)) }
  val canSave by remember(state) { derivedStateOf { state.text.isNotBlank() } }
  val save = { if (canSave) onEvent(WorkspaceEvent.RenameSession(sessionId, state.text.toString())) }
  val focusRequester = remember { FocusRequester() }
  Column(
    verticalArrangement = Arrangement.spacedBy(spaces.spacing300),
    modifier = Modifier.fillMaxWidth().padding(start = spaces.spacing200, end = spaces.spacing200, top = spaces.spacing100, bottom = spaces.spacing200),
  ) {
    LemonadeUi.TextField(
      state = state,
      label = stringResource(Res.string.session_name_field),
      onKeyboardAction = { save() },
      modifier = Modifier
        .fillMaxWidth()
        .focusRequester(focusRequester)
        .onPreviewKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
          when (event.key) {
            Key.Enter, Key.NumPadEnter -> {
              save()
              true
            }
            Key.Escape -> {
              onEvent(WorkspaceEvent.CancelRenamingSession)
              true
            }
            else -> false
          }
        },
    )
    Row(
      horizontalArrangement = Arrangement.spacedBy(spaces.spacing200, Alignment.End),
      modifier = Modifier.fillMaxWidth(),
    ) {
      LemonadeUi.Button(
        label = stringResource(Res.string.action_cancel),
        onClick = { onEvent(WorkspaceEvent.CancelRenamingSession) },
        variant = LemonadeButtonVariant.Neutral,
        type = LemonadeButtonType.Subtle,
        size = LemonadeButtonSize.Small,
      )
      LemonadeUi.Button(
        label = stringResource(Res.string.session_rename),
        onClick = save,
        variant = LemonadeButtonVariant.Primary,
        type = LemonadeButtonType.Solid,
        size = LemonadeButtonSize.Small,
        enabled = canSave,
      )
    }
  }
  LaunchedEffect(sessionId) { focusRequester.requestFocus() }
}

/**
 * The branch field the menu turns into for a new worktree, with the folder it will get. Enter or
 * Create starts it, Esc or Cancel closes the menu.
 */
@Composable
private fun NewWorktreeForm(
  sessionId: String,
  branch: WorktreeBranchModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val spaces = LemonadeTheme.spaces
  val create = { onEvent(WorkspaceEvent.CreateSubSessionFrom(sessionId, SubSessionLocation.NewWorktree)) }
  val cancel = { onEvent(WorkspaceEvent.DismissSessionMenu) }
  Column(
    verticalArrangement = Arrangement.spacedBy(spaces.spacing300),
    modifier = Modifier.fillMaxWidth().padding(start = spaces.spacing200, end = spaces.spacing200, top = spaces.spacing100, bottom = spaces.spacing200),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing100)) {
      WorktreeBranchField(branch = branch, onCreate = create, onCancel = cancel, onEvent = onEvent, modifier = Modifier.fillMaxWidth())
      Mono(text = branch.path, style = LemonadeCodeTheme.typography.codeSmall, color = LemonadeCodeTheme.code.path)
    }
    Row(
      horizontalArrangement = Arrangement.spacedBy(spaces.spacing200, Alignment.End),
      modifier = Modifier.fillMaxWidth(),
    ) {
      LemonadeUi.Button(
        label = stringResource(Res.string.action_cancel),
        onClick = cancel,
        variant = LemonadeButtonVariant.Neutral,
        type = LemonadeButtonType.Subtle,
        size = LemonadeButtonSize.Small,
      )
      LemonadeUi.Button(
        label = stringResource(Res.string.action_create_sub_session),
        onClick = create,
        variant = LemonadeButtonVariant.Primary,
        type = LemonadeButtonType.Solid,
        size = LemonadeButtonSize.Small,
        enabled = branch.canCreate,
      )
    }
  }
}
