package dev.lemonadecode.workspace.impl

import dev.lemonadecode.domain.review.CommentStatus
import dev.lemonadecode.domain.review.Review
import dev.lemonadecode.domain.session.BranchName
import dev.lemonadecode.domain.session.Session
import dev.lemonadecode.domain.session.SessionStatus
import dev.lemonadecode.domain.session.SubSessionLocation
import dev.lemonadecode.domain.session.SubSessionPlan
import dev.lemonadecode.domain.settings.AppSettings
import dev.lemonadecode.domain.settings.ThemePreference
import dev.lemonadecode.domain.update.AppInstallation
import dev.lemonadecode.domain.update.UpdateChannel
import dev.lemonadecode.domain.update.UpdateChecks
import dev.lemonadecode.domain.update.UpdateState
import dev.lemonadecode.platform.localization.asLemonadeCodeString
import dev.lemonadecode.platform.process.abbreviateHome
import dev.lemonadecode.workspace.impl.SessionsMapper.toModel
import dev.lemonadecode.workspace.impl.SettingsMapper.toModel
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.branch_note_new
import dev.lemonadecode.workspace.strings.branch_note_same
import dev.lemonadecode.workspace.strings.keep_awake_active
import dev.lemonadecode.workspace.strings.keep_awake_idle
import dev.lemonadecode.workspace.strings.keep_awake_off
import dev.lemonadecode.workspace.strings.keep_awake_tag
import dev.lemonadecode.workspace.strings.sub_help_no_repo
import dev.lemonadecode.workspace.strings.sub_help_same
import dev.lemonadecode.workspace.strings.sub_help_worktree
import dev.lemonadecode.workspace.strings.worktree_branch_invalid
import dev.lemonadecode.workspace.strings.worktree_branch_taken
import dev.lemonadecode.workspace.uimodel.KeepAwakeStatusModel
import dev.lemonadecode.workspace.uimodel.LanguageOption
import dev.lemonadecode.workspace.uimodel.NewSubSessionModel
import dev.lemonadecode.workspace.uimodel.PreviewAssetLoader
import dev.lemonadecode.workspace.uimodel.SessionsPanelModel
import dev.lemonadecode.workspace.uimodel.SettingsModel
import dev.lemonadecode.workspace.uimodel.ThemeOption
import dev.lemonadecode.workspace.uimodel.TopBarModel
import dev.lemonadecode.workspace.uimodel.ViewSwitcherModel
import dev.lemonadecode.workspace.uimodel.WorkspaceContentModel
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import dev.lemonadecode.workspace.uimodel.WorkspaceModel
import dev.lemonadecode.workspace.uimodel.WorkspaceView
import dev.lemonadecode.workspace.uimodel.WorktreeBranchModel
import dev.lemonadecode.workspace.uimodel.SubSessionLocation as LocationModel

/** Everything the workspace Model is built from. */
internal data class WorkspaceInput(
  val sessions: List<Session>,
  /** Review of the selected session's folder. */
  val review: Review?,
  val settings: AppSettings,
  val view: WorkspaceViewState,
  /** What the new sub-session panel would create, for its current location. */
  val subSessionPlan: SubSessionPlan?,
  val pendingMessage: String?,
  val homeDirectory: String,
  /** What has loaded for the files whose preview is on screen, by path. */
  val previews: Map<String, PreviewLoad> = emptyMap(),
  /** Reads what previews refer to, like a README's pictures, for the selected session. */
  val previewAssets: PreviewAssetLoader = PreviewAssetLoader.None,
  /** The worktree the open session menu's branch field would create. */
  val menuWorktreePlan: SubSessionPlan? = null,
  val update: UpdateState = UpdateState.None,
  /** Diff rows mapped for earlier Models, reused while their file and marks stay the same. */
  val diffRows: DiffRowCache = DiffRowCache(),
  val installation: AppInstallation = AppInstallation(version = null, channel = UpdateChannel.Development),
  val updateChecks: UpdateChecks = UpdateChecks(),
  val mobile: MobileInput = MobileInput(),
  /** Now, for "2 h ago"; milliseconds since the epoch. */
  val nowMillis: Long = 0,
  /** The computer's own language, which Match system follows. */
  val systemLanguage: LanguageOption = LanguageOption.English,
)

/** Turns domain state and the workspace's UI state into the immutable Model the UI draws. */
internal object WorkspaceModelMapper {
  fun map(input: WorkspaceInput, eventSink: (WorkspaceEvent) -> Unit): WorkspaceModel {
    val sessions = input.sessions
    val view = input.view
    val selected = selectedSession(sessions, view)
    val parent = selected?.parentId?.let { id -> sessions.firstOrNull { it.id == id } }
    val working = sessions.count { it.status == SessionStatus.Working }
    val unsent = input.review?.comments.orEmpty().count { it.status == CommentStatus.Unsent }
    val updates = SettingsMapper.updates(input.installation, input.update, input.updateChecks, input.nowMillis)

    return WorkspaceModel(
      topBar = TopBarModel(
        path = selected?.workingDirectory?.let { abbreviateHome(it, input.homeDirectory) },
        keepAwakeLabel = if (input.settings.keepAwake && working > 0) Res.string.keep_awake_tag.asLemonadeCodeString(working) else null,
        anySessionNeedsInput = sessions.any { it.status == SessionStatus.NeedsInput },
        updateAttention = updates.needsAttention,
        devices = DevicesMapper.button(input.mobile, view),
      ),
      sessions = SessionsPanelModel(
        expanded = input.settings.sidebarExpanded,
        sidebarWidth = clampSidebarWidth(input.settings.sidebarWidth),
        drawerOpen = view.drawerOpen,
        navigation = SessionsMapper.navigation(sessions, selected, view.navigationStyle, view.folded, input.homeDirectory),
        tiles = SessionsMapper.tiles(sessions, selected),
        menu = view.sessionMenu?.let { id -> sessions.firstOrNull { it.id == id } }?.let { session ->
          SessionsMapper.menu(
            session = session,
            style = view.navigationStyle,
            home = input.homeDirectory,
            renaming = view.renaming == session.id,
            newWorktree = view.worktreeBranch?.takeIf { view.namingWorktree == session.id }?.let {
              worktreeBranch(it, input.menuWorktreePlan, view.takenBranch, input.homeDirectory)
            },
          )
        },
      ),
      header = selected?.let { SessionsMapper.header(it, parent, view.navigationStyle) },
      viewSwitcher = selected?.let {
        ViewSwitcherModel(
          selected = view.view,
          sessionStatus = it.status.toModel(),
          diffStat = DiffMapper.stat(input.review),
          unsentCommentCount = unsent,
        )
      },
      content = when {
        selected == null -> WorkspaceContentModel.NoSessions
        view.view == WorkspaceView.Terminal -> WorkspaceContentModel.Terminal(selected.id.value)
        else -> DiffMapper.content(input.review, view, input.pendingMessage, input.previews, input.previewAssets, input.diffRows, input.nowMillis)
      },
      settings = SettingsModel(
        open = view.settingsOpen,
        theme = input.settings.theme.toModel(),
        keepAwake = input.settings.keepAwake,
        keepAwakeStatus = when {
          !input.settings.keepAwake -> KeepAwakeStatusModel(Res.string.keep_awake_off.asLemonadeCodeString(), active = false)
          working > 0 -> KeepAwakeStatusModel(Res.plurals.keep_awake_active.asLemonadeCodeString(working), active = true)
          else -> KeepAwakeStatusModel(Res.string.keep_awake_idle.asLemonadeCodeString(), active = false)
        },
        statusSounds = input.settings.statusSounds,
        page = view.settingsPage,
        language = input.settings.language.toModel(),
        systemLanguage = input.systemLanguage,
        mobile = SettingsMapper.mobile(input.settings.mobilePlugin, input.mobile.devices),
        updates = updates,
      ),
      newSubSession = newSubSession(selected, view.subSessionMenu ?: input.settings.subSessionLocation, view.subSessionMenu != null, input),
      closeSession = view.closing?.let { ClosingSessions.dialog(it, input.homeDirectory) },
      devices = DevicesMapper.panel(
        input = input.mobile,
        view = view,
        layout = input.settings.devicesLayout,
        agent = selected?.let { it.agent ?: it.shell },
        widths = DevicesPanelWidths(narrow = input.settings.devicesPanelWidth, wide = input.settings.devicesPanelWideWidth),
      ),
      error = view.error,
      toast = view.toast,
      eventSink = eventSink,
    )
  }

  fun clampSidebarWidth(width: Int): Int = width.coerceIn(SessionsPanelModel.MinSidebarWidth, SessionsPanelModel.MaxSidebarWidth)

  fun selectedSession(sessions: List<Session>, view: WorkspaceViewState): Session? = sessions.firstOrNull { it.id == view.selectedId } ?: sessions.firstOrNull()

  private fun newSubSession(selected: Session?, location: SubSessionLocation, open: Boolean, input: WorkspaceInput): NewSubSessionModel {
    val plan = input.subSessionPlan
    val name = selected?.displayName.orEmpty()
    val worktree = plan?.location == SubSessionLocation.NewWorktree
    return NewSubSessionModel(
      open = open && selected != null,
      fromSessionName = name,
      location = location.toModel(),
      path = plan?.workingDirectory?.let { abbreviateHome(it, input.homeDirectory) }.orEmpty(),
      branch = plan?.branch.orEmpty(),
      branchNote = if (worktree) {
        Res.string.branch_note_new.asLemonadeCodeString(plan.baseBranch ?: "HEAD")
      } else {
        Res.string.branch_note_same.asLemonadeCodeString()
      },
      help = when {
        selected?.repositoryRoot == null -> Res.string.sub_help_no_repo.asLemonadeCodeString()
        worktree -> Res.string.sub_help_worktree.asLemonadeCodeString(name)
        else -> Res.string.sub_help_same.asLemonadeCodeString(name)
      },
      worktreeAvailable = selected?.repositoryRoot != null,
      worktreeBranch = input.view.worktreeBranch?.takeIf { worktree }?.let { worktreeBranch(it, plan, input.view.takenBranch, input.homeDirectory) },
    )
  }

  /** The field naming a new worktree's branch: [name] as typed, going where [plan] says. */
  private fun worktreeBranch(name: String, plan: SubSessionPlan?, taken: String?, home: String): WorktreeBranchModel = WorktreeBranchModel(
    name = name,
    path = plan?.workingDirectory?.let { abbreviateHome(it, home) }.orEmpty(),
    problem = when {
      name.isEmpty() -> null
      !BranchName.isValid(name) -> Res.string.worktree_branch_invalid.asLemonadeCodeString()
      name == taken -> Res.string.worktree_branch_taken.asLemonadeCodeString(name)
      else -> null
    },
    canCreate = canUseWorktreeBranch(name, taken),
  )

  /** Whether a new worktree can go on [name]: git takes it, and didn't say it already has it ([taken]). */
  fun canUseWorktreeBranch(name: String, taken: String?): Boolean = BranchName.isValid(name) && name != taken

  fun ThemePreference.toModel(): ThemeOption = when (this) {
    ThemePreference.Dark -> ThemeOption.Dark
    ThemePreference.Light -> ThemeOption.Light
    ThemePreference.System -> ThemeOption.System
  }

  fun ThemeOption.toPreference(): ThemePreference = when (this) {
    ThemeOption.Dark -> ThemePreference.Dark
    ThemeOption.Light -> ThemePreference.Light
    ThemeOption.System -> ThemePreference.System
  }

  fun SubSessionLocation.toModel(): LocationModel = when (this) {
    SubSessionLocation.SameFolder -> LocationModel.SameFolder
    SubSessionLocation.NewWorktree -> LocationModel.NewWorktree
  }

  fun LocationModel.toDomain(): SubSessionLocation = when (this) {
    LocationModel.SameFolder -> SubSessionLocation.SameFolder
    LocationModel.NewWorktree -> SubSessionLocation.NewWorktree
  }
}
