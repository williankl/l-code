package dev.loupe.workspace.impl

import dev.loupe.domain.review.CommentStatus
import dev.loupe.domain.review.Review
import dev.loupe.domain.session.Session
import dev.loupe.domain.session.SessionStatus
import dev.loupe.domain.session.SubSessionLocation
import dev.loupe.domain.session.SubSessionPlan
import dev.loupe.domain.settings.AppSettings
import dev.loupe.domain.settings.ThemePreference
import dev.loupe.platform.localization.asLoupeString
import dev.loupe.platform.process.abbreviateHome
import dev.loupe.workspace.impl.SessionsMapper.toModel
import dev.loupe.workspace.strings.Res
import dev.loupe.workspace.strings.branch_note_new
import dev.loupe.workspace.strings.branch_note_same
import dev.loupe.workspace.strings.keep_awake_active
import dev.loupe.workspace.strings.keep_awake_idle
import dev.loupe.workspace.strings.keep_awake_off
import dev.loupe.workspace.strings.keep_awake_tag
import dev.loupe.workspace.strings.sub_help_no_repo
import dev.loupe.workspace.strings.sub_help_same
import dev.loupe.workspace.strings.sub_help_worktree
import dev.loupe.workspace.uimodel.KeepAwakeStatusModel
import dev.loupe.workspace.uimodel.NewSubSessionModel
import dev.loupe.workspace.uimodel.SessionsPanelModel
import dev.loupe.workspace.uimodel.SettingsModel
import dev.loupe.workspace.uimodel.ThemeOption
import dev.loupe.workspace.uimodel.TopBarModel
import dev.loupe.workspace.uimodel.ViewSwitcherModel
import dev.loupe.workspace.uimodel.WorkspaceContentModel
import dev.loupe.workspace.uimodel.WorkspaceEvent
import dev.loupe.workspace.uimodel.WorkspaceModel
import dev.loupe.workspace.uimodel.WorkspaceView
import dev.loupe.workspace.uimodel.SubSessionLocation as LocationModel

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
  /** What has loaded for the files in [WorkspaceViewState.previews], by path. */
  val previews: Map<String, PreviewLoad> = emptyMap(),
  /** The worktree a sub-session started from the open session menu would get. */
  val menuWorktreePlan: SubSessionPlan? = null,
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

    return WorkspaceModel(
      topBar = TopBarModel(
        path = selected?.workingDirectory?.let { abbreviateHome(it, input.homeDirectory) },
        keepAwakeLabel = if (input.settings.keepAwake && working > 0) Res.string.keep_awake_tag.asLoupeString(working) else null,
        anySessionNeedsInput = sessions.any { it.status == SessionStatus.NeedsInput },
      ),
      sessions = SessionsPanelModel(
        expanded = input.settings.sidebarExpanded,
        drawerOpen = view.drawerOpen,
        navigation = SessionsMapper.navigation(sessions, selected, view.navigationStyle, view.folded, input.homeDirectory),
        tiles = SessionsMapper.tiles(sessions, selected),
        menu = view.sessionMenu?.let { id -> sessions.firstOrNull { it.id == id } }?.let { session ->
          SessionsMapper.menu(
            session = session,
            style = view.navigationStyle,
            home = input.homeDirectory,
            worktreePlan = input.menuWorktreePlan,
            renaming = view.renaming == session.id,
          )
        },
      ),
      header = selected?.let { SessionsMapper.header(it, parent, view.navigationStyle) },
      viewSwitcher = selected?.let {
        ViewSwitcherModel(
          selected = view.view,
          sessionStatus = it.status.toModel(),
          diffTarget = DiffMapper.baseRef(input.review),
          diffStat = DiffMapper.stat(input.review),
          unsentCommentCount = unsent,
        )
      },
      content = when {
        selected == null -> WorkspaceContentModel.NoSessions
        view.view == WorkspaceView.Terminal -> WorkspaceContentModel.Terminal(selected.id.value)
        else -> DiffMapper.content(input.review, view, input.pendingMessage, input.previews)
      },
      settings = SettingsModel(
        open = view.settingsOpen,
        theme = input.settings.theme.toModel(),
        keepAwake = input.settings.keepAwake,
        keepAwakeStatus = when {
          !input.settings.keepAwake -> KeepAwakeStatusModel(Res.string.keep_awake_off.asLoupeString(), active = false)
          working > 0 -> KeepAwakeStatusModel(Res.plurals.keep_awake_active.asLoupeString(working), active = true)
          else -> KeepAwakeStatusModel(Res.string.keep_awake_idle.asLoupeString(), active = false)
        },
      ),
      newSubSession = newSubSession(selected, view.subSessionMenu ?: input.settings.subSessionLocation, view.subSessionMenu != null, input),
      error = view.error,
      toast = view.toast,
      eventSink = eventSink,
    )
  }

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
        Res.string.branch_note_new.asLoupeString(plan.baseBranch ?: "HEAD")
      } else {
        Res.string.branch_note_same.asLoupeString()
      },
      help = when {
        selected?.repositoryRoot == null -> Res.string.sub_help_no_repo.asLoupeString()
        worktree -> Res.string.sub_help_worktree.asLoupeString(name)
        else -> Res.string.sub_help_same.asLoupeString(name)
      },
      worktreeAvailable = selected?.repositoryRoot != null,
    )
  }

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
