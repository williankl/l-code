package dev.lemonadecode.workspace.uimodel

/** Every intent the workspace UI can raise. */
public sealed interface WorkspaceEvent {
  /** The sidebar on wide windows, the drawer below the narrow-layout breakpoint. */
  public data class ToggleSessionsPanel(val narrowLayout: Boolean) : WorkspaceEvent
  public data object CloseDrawer : WorkspaceEvent

  /** Dragged the sidebar's edge and let go: keeps [width] (dp) as the sidebar's width. */
  public data class ResizeSessionsSidebar(val width: Int) : WorkspaceEvent

  /** Let go of the docked Devices panel's edge at [width] dp. */
  public data class ResizeDevicesPanel(val width: Int) : WorkspaceEvent

  public data class SelectSession(val sessionId: String) : WorkspaceEvent
  public data class ToggleSessionFold(val sessionId: String) : WorkspaceEvent
  public data object CloseSelectedSession : WorkspaceEvent

  /** Closes any session, selected or not, and its sub-sessions. Middle click does this. */
  public data class CloseSession(val sessionId: String) : WorkspaceEvent

  /** Answers the dialog a closing session's worktrees open: closes it, and deletes them when [deleteWorktrees]. */
  public data class ConfirmCloseSession(val deleteWorktrees: Boolean) : WorkspaceEvent

  /** Esc or Cancel in that dialog: nothing closes. */
  public data object CancelCloseSession : WorkspaceEvent

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

  /** Opens one item of the settings list on its own page. */
  public data class OpenSettingsPage(val page: SettingsPage) : WorkspaceEvent

  /** Back from a settings page to the list; Esc on the list closes settings. */
  public data object SettingsBack : WorkspaceEvent
  public data class SetTheme(val theme: ThemeOption) : WorkspaceEvent
  public data class SetLanguage(val language: LanguageOption) : WorkspaceEvent
  public data class SetKeepAwake(val enabled: Boolean) : WorkspaceEvent
  public data class SetStatusSounds(val enabled: Boolean) : WorkspaceEvent

  /** Settings › Sounds › Play: lets you hear [sound], whether status sounds are on or not. */
  public data class PlayStatusSound(val sound: StatusSound) : WorkspaceEvent

  /** Settings › Appearance › Animations. */
  public data class SetAnimations(val enabled: Boolean) : WorkspaceEvent

  public data class SetMobilePlugin(val enabled: Boolean) : WorkspaceEvent

  public data object CheckForUpdates : WorkspaceEvent

  /** Downloads the new version; restarting finishes it. */
  public data object InstallUpdate : WorkspaceEvent

  /** Quits so the downloaded version takes its place; the sessions come back after. */
  public data object RestartToUpdate : WorkspaceEvent

  /** The phone button: opens or closes the Devices panel, or brings its own window forward. */
  public data object ToggleDevices : WorkspaceEvent

  /** Plugins › Mobile › Open devices: closes settings and shows the device list. */
  public data object OpenDevices : WorkspaceEvent
  public data object CloseDevices : WorkspaceEvent

  /** The panel's back arrow: one step back towards the list. */
  public data object DevicesBack : WorkspaceEvent
  public data class MoveDevices(val window: DevicesWindow) : WorkspaceEvent
  public data class SetDevicesLayout(val layout: DevicesLayoutOption) : WorkspaceEvent
  public data class SelectDevice(val deviceId: String) : WorkspaceEvent
  public data class SetDeviceTab(val tab: DeviceTab) : DevicePageEvent
  public data class DisconnectDevice(val deviceId: String) : WorkspaceEvent

  /** A device's ↗ in the list: mirrors it in a window of its own, or closes that window. */
  public data class ToggleDeviceWindow(val deviceId: String) : WorkspaceEvent

  /** The device window's own close button. */
  public data class CloseDeviceWindow(val deviceId: String) : WorkspaceEvent

  /** [event] happened in the window mirroring [deviceId], not in the Devices panel. */
  public data class InDeviceWindow(val deviceId: String, val event: DevicePageEvent) : WorkspaceEvent

  /** A click on the mirrored screen, in the device's pixels. */
  public data class TapDevice(val x: Int, val y: Int) : DevicePageEvent

  /** A drag on the mirrored screen, in the device's pixels. */
  public data class SwipeDevice(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val durationMillis: Long) : DevicePageEvent
  public data object PressDeviceBack : DevicePageEvent
  public data object PressDeviceHome : DevicePageEvent
  public data object RotateDevice : DevicePageEvent

  /** Saves the device's screen and hands the picture to the selected session. */
  public data object ScreenshotToSession : DevicePageEvent

  /** Typing goes to the device, or stops going there. */
  public data object ToggleDeviceKeyboard : DevicePageEvent
  public data class TypeOnDevice(val text: String) : DevicePageEvent
  public data class PressDeviceKey(val key: DeviceKeyModel) : DevicePageEvent

  public data class SetLogFilter(val filter: LogFilter) : DevicePageEvent
  public data object ToggleLogSearch : DevicePageEvent
  public data class SetLogQuery(val query: String) : DevicePageEvent
  public data object ClearLogs : DevicePageEvent

  /** Live ↔ paused: paused, the list stops moving while lines keep arriving underneath. */
  public data object ToggleLogsPaused : DevicePageEvent

  /** Hands the error lines shown to the selected session's agent. */
  public data object SendLogErrors : DevicePageEvent

  /** Opens or closes the choice of whose lines the device's log shows. */
  public data object ToggleLogApps : DevicePageEvent
  public data object DismissLogApps : DevicePageEvent

  /** The log follows whichever app is in front, as it does until another choice is made. */
  public data object FollowAppInFront : DevicePageEvent

  /** The log shows every line, from every app and the system. */
  public data object ShowAllApps : DevicePageEvent

  /** Adds the app's lines to the log, or takes them out; with no app left, every line shows. */
  public data class ToggleLogApp(val packageName: String) : DevicePageEvent

  public data object StartPairing : WorkspaceEvent
  public data class ChoosePairingPlatform(val platform: MobilePlatformModel) : WorkspaceEvent
  public data class ChoosePairingMethod(val method: PairingMethod) : WorkspaceEvent
  public data class SetPairingAddress(val address: String) : WorkspaceEvent
  public data class SetPairingCode(val code: String) : WorkspaceEvent
  public data object PairWithCode : WorkspaceEvent

  /** Pair or Connect on a device found on this network. */
  public data class ConnectNearby(val address: String) : WorkspaceEvent

  /** Opens the device just paired. */
  public data object OpenPairedDevice : WorkspaceEvent

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

  /** Shows or hides the changed files beside the diff. */
  public data class ShowFileList(val shown: Boolean) : WorkspaceEvent

  /** Picked a file in the list beside the diff: it opens, if it was folded, as the diff scrolls to it. */
  public data class RevealFile(val fileId: String) : WorkspaceEvent
  public data class ToggleFileExpanded(val fileId: String) : WorkspaceEvent
  public data class ToggleFullFile(val fileId: String) : WorkspaceEvent
  public data class ShowFullFile(val fileId: String) : WorkspaceEvent

  /** A changed file's path is on the clipboard: confirms it with a toast. */
  public data class CopiedFilePath(val path: String) : WorkspaceEvent

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

/** What happens on one device's page: its screen, its controls and its logs. */
public sealed interface DevicePageEvent : WorkspaceEvent

/** How to pair, at step 2. */
public enum class PairingMethod { QrCode, PairingCode, Nearby, Cable }

/** Keys that reach the device while typing goes to it; letters go as text. */
public enum class DeviceKeyModel { Enter, Backspace, ForwardDelete, Tab, Escape, Up, Down, Left, Right }
