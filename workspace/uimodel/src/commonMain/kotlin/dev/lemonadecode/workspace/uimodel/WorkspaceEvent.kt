package dev.lemonadecode.workspace.uimodel

/** Every intent the workspace UI can raise. */
public sealed interface WorkspaceEvent {
  /** The sidebar on wide windows, the drawer below the narrow-layout breakpoint. */
  public data class ToggleSessionsPanel(val narrowLayout: Boolean) : WorkspaceEvent
  public data object CloseDrawer : WorkspaceEvent

  /** Dragged the sidebar's edge and let go: keeps [width] (dp) as the sidebar's width. */
  public data class ResizeSessionsSidebar(val width: Int) : WorkspaceEvent

  public data class SelectSession(val sessionId: String) : WorkspaceEvent
  public data class ToggleSessionFold(val sessionId: String) : WorkspaceEvent
  public data object CloseSelectedSession : WorkspaceEvent

  /** Closes any session, selected or not, and its sub-sessions. Middle click does this. */
  public data class CloseSession(val sessionId: String) : WorkspaceEvent

  /** Right-clicked a session: opens its menu. */
  public data class OpenSessionMenu(val sessionId: String) : WorkspaceEvent
  public data object DismissSessionMenu : WorkspaceEvent

  /**
   * Starts a sub-session of any session and selects it. A new worktree goes on the branch typed in
   * the session's menu.
   */
  public data class CreateSubSessionFrom(val sessionId: String, val location: SubSessionLocation) : WorkspaceEvent

  /** Turns the session's menu into a field for a new worktree's branch, filled with a suggestion. */
  public data class StartNamingWorktree(val sessionId: String) : WorkspaceEvent

  /** The branch typed for a new worktree so far, in the menu or in the sub-session panel. */
  public data class SetWorktreeBranch(val name: String) : WorkspaceEvent

  /** Turns the session's menu into a name field. */
  public data class StartRenamingSession(val sessionId: String) : WorkspaceEvent

  /**
   * Keeps [name] for the session. Blank, or the name the app gives it, goes back to following the
   * folder.
   */
  public data class RenameSession(val sessionId: String, val name: String) : WorkspaceEvent

  /** Closes the name field and the menu it is in, renaming nothing. */
  public data object CancelRenamingSession : WorkspaceEvent

  /** The session's folder path is on the clipboard: confirms it with a toast. */
  public data class CopiedSessionPath(val path: String) : WorkspaceEvent

  /** Opens a new terminal at home. */
  public data object NewSession : WorkspaceEvent

  public data object ToggleSettings : WorkspaceEvent
  public data object DismissSettings : WorkspaceEvent
  public data class SetTheme(val theme: ThemeOption) : WorkspaceEvent
  public data class SetKeepAwake(val enabled: Boolean) : WorkspaceEvent

  /** The Update button: opens or closes the panel about the new version. */
  public data object ToggleUpdate : WorkspaceEvent
  public data object DismissUpdate : WorkspaceEvent

  /** Downloads the new version, then restarts into it. */
  public data object InstallUpdate : WorkspaceEvent

  public data object OpenParentSession : WorkspaceEvent
  public data object ToggleNewSubSessionMenu : WorkspaceEvent
  public data object DismissNewSubSessionMenu : WorkspaceEvent
  public data class SetSubSessionLocation(val location: SubSessionLocation) : WorkspaceEvent
  public data object CreateSubSession : WorkspaceEvent
  public data class SelectView(val view: WorkspaceView) : WorkspaceEvent

  public data object RefreshDiff : WorkspaceEvent

  /** Opens or closes the list of branches the diff can compare with. */
  public data object ToggleDiffBasePicker : WorkspaceEvent
  public data object DismissDiffBasePicker : WorkspaceEvent

  /** Compares the diff with [branch] instead. */
  public data class CompareWith(val branch: String) : WorkspaceEvent
  public data class ToggleFileExpanded(val fileId: String) : WorkspaceEvent
  public data class ToggleFullFile(val fileId: String) : WorkspaceEvent
  public data class ShowFullFile(val fileId: String) : WorkspaceEvent

  /** Switches a file between its text diff and its rendered preview. */
  public data class ToggleFilePreview(val fileId: String) : WorkspaceEvent

  /** Shows the before side, the after side or both in a file's preview. */
  public data class SetPreviewLayout(val fileId: String, val layout: PreviewLayout) : WorkspaceEvent

  /** Pressed + on a line: starts a range there. */
  public data class StartCommentRange(val fileId: String, val lineIndex: Int) : WorkspaceEvent

  /** Dragged from + onto another line while still pressed. */
  public data class DragCommentRange(val fileId: String, val lineIndex: Int) : WorkspaceEvent

  /** Released after pressing or dragging; the composer opens under the last line. */
  public data object EndCommentRange : WorkspaceEvent

  /** Shift-clicked + on another line to grow the open range. */
  public data class ExtendCommentRange(val fileId: String, val lineIndex: Int) : WorkspaceEvent

  public data class UpdateCommentDraft(val text: String) : WorkspaceEvent
  public data object CancelComment : WorkspaceEvent
  public data object AddComment : WorkspaceEvent
  public data class DeleteComment(val commentId: String) : WorkspaceEvent

  public data object TogglePreview : WorkspaceEvent
  public data object SendToNewSubSession : WorkspaceEvent
  public data object SendToSession : WorkspaceEvent

  public data object DismissError : WorkspaceEvent
}
