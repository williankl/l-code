package dev.lemonadecode.workspace.impl

import dev.lemonadecode.domain.mobile.DeviceId
import dev.lemonadecode.domain.mobile.LogPackages
import dev.lemonadecode.domain.mobile.MobileError
import dev.lemonadecode.domain.mobile.MobilePlatform
import dev.lemonadecode.domain.review.FileContent
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SubSessionLocation
import dev.lemonadecode.platform.localization.LemonadeCodeString
import dev.lemonadecode.workspace.uimodel.DeviceTab
import dev.lemonadecode.workspace.uimodel.DevicesWindow
import dev.lemonadecode.workspace.uimodel.LogFilter
import dev.lemonadecode.workspace.uimodel.PreviewLayout
import dev.lemonadecode.workspace.uimodel.SettingsPage
import dev.lemonadecode.workspace.uimodel.WorkspaceView

/** How the session list is drawn: one level with elbows, or any depth with lineage rails. */
internal enum class NavigationStyle { Flat, Tree }

/** A range of a file's lines, by position in the file's full list of lines. */
internal data class LineRange(
  val filePath: String,
  val anchorIndex: Int,
  val focusIndex: Int,
) {
  val first: Int get() = minOf(anchorIndex, focusIndex)
  val last: Int get() = maxOf(anchorIndex, focusIndex)
}

internal data class Composer(
  val range: LineRange,
  val draft: String,
)

/** The workspace's own UI state: everything that isn't owned by a service. */
internal data class WorkspaceViewState(
  val selectedId: SessionId? = null,
  val view: WorkspaceView = WorkspaceView.Terminal,
  val navigationStyle: NavigationStyle = NavigationStyle.Tree,
  val drawerOpen: Boolean = false,
  val folded: Set<SessionId> = emptySet(),
  /** Files the user expanded or collapsed by hand; the rest follow the default. */
  val fileExpansion: Map<String, Boolean> = emptyMap(),
  val fullFiles: Set<String> = emptySet(),
  /**
   * Preview (true) or text diff (false) picked by hand, by path. The rest show their preview when
   * Lemonade Code can draw them, unless they have comments, which only the text diff shows.
   */
  val previewChoices: Map<String, Boolean> = emptyMap(),
  /** Preview sides picked by hand; the rest follow the default for their kind. */
  val previewLayouts: Map<String, PreviewLayout> = emptyMap(),
  val dragging: LineRange? = null,
  val composer: Composer? = null,
  val previewOpen: Boolean = false,
  val settingsOpen: Boolean = false,
  val settingsPage: SettingsPage = SettingsPage.List,
  /** The Devices panel while it's open. */
  val devices: DevicesViewState? = null,
  /** Where the Devices panel opens; kept while it's closed. */
  val devicesWindow: DevicesWindow = DevicesWindow.Docked,
  /** Counts the times the Devices window was asked to come forward. */
  val devicesFocusRequest: Int = 0,
  /** Devices mirrored in windows of their own, in the order they were opened. */
  val deviceWindows: Map<DeviceId, DeviceWindowState> = emptyMap(),
  /** The changed files beside the diff, picked by hand; null follows the window's width. */
  val fileList: Boolean? = null,
  val subSessionMenu: SubSessionLocation? = null,
  /** The list of branches the diff can compare with is open. */
  val basePickerOpen: Boolean = false,
  /** The session whose right-click menu is open. */
  val sessionMenu: SessionId? = null,
  /** The session whose menu shows the name field; the text itself stays in the field until saved. */
  val renaming: SessionId? = null,
  /** The branch typed for a new worktree, while the sub-session panel or a session's menu asks for one. */
  val worktreeBranch: String? = null,
  /** The session whose menu asks for a new worktree's branch instead of showing its items. */
  val namingWorktree: SessionId? = null,
  /** A branch git already has, from the last try; said under the field while that name stays typed. */
  val takenBranch: String? = null,
  /** A sub-session is on its way: another Enter or click waits for it rather than starting a second. */
  val startingSubSession: Boolean = false,
  /** A session about to close that leaves worktrees unused, while the dialog asks what to do with them. */
  val closing: ClosingSession? = null,
  val error: LemonadeCodeString? = null,
  val toast: LemonadeCodeString? = null,
)

/**
 * Both sides of a previewed file as far as they have loaded. A side is null until it has been
 * read; [error] is set when reading failed, and the last good content, if any, stays shown.
 */
internal data class PreviewLoad(
  val before: FileContent? = null,
  val after: FileContent? = null,
  val error: LemonadeCodeString? = null,
)

/** The steps of the Devices panel: the list, a device, and the three steps of pairing. */
internal enum class DevicesStep { List, Device, PairPlatform, PairMethod, PairQr, PairCode, PairNearby, PairCable, Paired }

/** The Devices panel's own state; the devices themselves come from the mobile service. */
internal data class DevicesViewState(
  val step: DevicesStep = DevicesStep.List,
  /** The device shown at [DevicesStep.Device]; kept after going back, so its tab and filter carry over. */
  val page: DevicePageState? = null,
  val pairPlatform: MobilePlatform = MobilePlatform.Android,
  val pairAddress: String = "",
  val pairCode: String = "",
  /** A pairing or connection is under way. */
  val pairing: Boolean = false,
  /** The nearby device being connected to. */
  val connecting: String? = null,
  val pairError: MobileError? = null,
  /** The device the last pairing added. */
  val paired: DeviceId? = null,
  /** Goes up by one to start a QR or cable pairing over. */
  val attempt: Int = 0,
)

/** The device page the Devices panel shows: at its Device step, unless that device is in a window of its own. */
internal val WorkspaceViewState.panelPage: DevicePageState?
  get() = devices?.takeIf { it.step == DevicesStep.Device }?.page?.takeIf { it.deviceId !in deviceWindows }

/** One device's page, in the Devices panel or in a window of its own: its screen and its logs. */
internal data class DevicePageState(
  val deviceId: DeviceId,
  val tab: DeviceTab = DeviceTab.Screen,
  /** Typing goes to the device. */
  val keyboard: Boolean = false,
  val logFilter: LogFilter = LogFilter.All,
  val logSearch: Boolean = false,
  val logQuery: String = "",
  /** The newest line shown while paused; null while live. */
  val pausedAt: Long? = null,
  /** The logs were cleared by hand, so an empty list says so instead of "waiting". */
  val logsCleared: Boolean = false,
  /** Whose lines the log shows; the app in front until another choice is made. */
  val logPackages: LogPackages = LogPackages.AppInFront,
  /** The choice of whose lines the log shows is open. */
  val logAppsOpen: Boolean = false,
)

/** A device mirrored in a window of its own. */
internal data class DeviceWindowState(
  val page: DevicePageState,
  /** Counts the times the window was asked to come forward. */
  val focusRequest: Int = 0,
)
