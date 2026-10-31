package dev.loupe.workspace.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.loupe.domain.review.CommentId
import dev.loupe.domain.review.DiffState
import dev.loupe.domain.review.FileDiff
import dev.loupe.domain.review.NewComment
import dev.loupe.domain.session.Session
import dev.loupe.domain.session.SessionId
import dev.loupe.domain.session.SessionStatus
import dev.loupe.domain.session.SubSessionLocation
import dev.loupe.platform.core.LoupeResult
import dev.loupe.platform.localization.LoupeString
import dev.loupe.platform.presenter.LoupePresenter
import dev.loupe.platform.process.HomeDirectory
import dev.loupe.review.api.ReviewService
import dev.loupe.sessions.api.SessionService
import dev.loupe.settings.api.SettingsService
import dev.loupe.workspace.impl.WorkspaceModelMapper.toDomain
import dev.loupe.workspace.impl.WorkspaceModelMapper.toPreference
import dev.loupe.workspace.uimodel.WorkspaceEvent
import dev.loupe.workspace.uimodel.WorkspaceModel
import dev.loupe.workspace.uimodel.WorkspaceView
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The Loupe workspace: sessions, their terminals and the diff review. Owns only UI state;
 * sessions, reviews and settings live in their services.
 */
@Inject
public class WorkspacePresenter internal constructor(
  private val sessionService: SessionService,
  private val reviewService: ReviewService,
  private val settingsService: SettingsService,
  private val homeDirectory: HomeDirectory,
) : LoupePresenter<WorkspaceModel>() {
  @Composable
  override fun present(): WorkspaceModel {
    val sessions by sessionService.sessions.collectAsState()
    val settings by settingsService.settings.collectAsState()
    var view by remember { mutableStateOf(WorkspaceViewState()) }
    val scope = rememberCoroutineScope()

    val selected = WorkspaceModelMapper.selectedSession(sessions, view)
    val selectedId = selected?.id
    val review by remember(selectedId) { selectedId?.let(reviewService::review) ?: flowOf(null) }.collectAsState(initial = null)

    RefreshWhenTerminalSettles(selected)

    val eventSink: (WorkspaceEvent) -> Unit = remember {
      { event ->
        val current = WorkspaceModelMapper.selectedSession(sessionService.sessions.value, view)
        fun update(transform: (WorkspaceViewState) -> WorkspaceViewState) {
          view = transform(view)
        }
        fun fail(message: LoupeString) = update { it.copy(error = message) }
        fun fileOf(path: String?): FileDiff? = (review?.diff as? DiffState.Ready)?.snapshot?.files?.firstOrNull { it.path == path }

        when (event) {
          is WorkspaceEvent.ToggleSessionsPanel -> if (event.narrowLayout) {
            update { it.copy(drawerOpen = !it.drawerOpen) }
          } else {
            scope.launch { settingsService.update { it.copy(sidebarExpanded = !it.sidebarExpanded) } }
          }
          WorkspaceEvent.CloseDrawer -> update { it.copy(drawerOpen = false) }
          is WorkspaceEvent.SelectSession -> update { it.selecting(SessionId(event.sessionId)) }
          is WorkspaceEvent.ToggleSessionFold -> update {
            val id = SessionId(event.sessionId)
            it.copy(folded = if (id in it.folded) it.folded - id else it.folded + id)
          }
          WorkspaceEvent.CloseSelectedSession -> current?.let { sessionService.close(it.id) }

          WorkspaceEvent.NewSession -> scope.launch {
            when (val opened = sessionService.openTerminal()) {
              is LoupeResult.Success -> update { it.selecting(opened.value).copy(view = WorkspaceView.Terminal) }
              is LoupeResult.Failure -> fail(ErrorMessages.of(opened.error))
            }
          }

          WorkspaceEvent.ToggleSettings -> update { if (it.settingsOpen) it.copy(settingsOpen = false) else it.closingPopovers().copy(settingsOpen = true) }
          WorkspaceEvent.DismissSettings -> update { it.copy(settingsOpen = false) }
          is WorkspaceEvent.SetTheme -> scope.launch { settingsService.update { it.copy(theme = event.theme.toPreference()) } }
          is WorkspaceEvent.SetKeepAwake -> scope.launch { settingsService.update { it.copy(keepAwake = event.enabled) } }

          WorkspaceEvent.OpenParentSession -> current?.parentId?.let { parent -> update { it.selecting(parent) } }
          WorkspaceEvent.ToggleNewSubSessionMenu -> update {
            if (it.subSessionMenu != null) {
              it.copy(subSessionMenu = null)
            } else {
              val remembered = settingsService.settings.value.subSessionLocation
              val location = if (current?.repositoryRoot == null) SubSessionLocation.SameFolder else remembered
              it.closingPopovers().copy(subSessionMenu = location)
            }
          }
          WorkspaceEvent.DismissNewSubSessionMenu -> update { it.copy(subSessionMenu = null) }
          is WorkspaceEvent.SetSubSessionLocation -> update { if (it.subSessionMenu != null) it.copy(subSessionMenu = event.location.toDomain()) else it }
          WorkspaceEvent.CreateSubSession -> {
            val location = view.subSessionMenu
            if (current != null && location != null) {
              scope.launch {
                settingsService.update { it.copy(subSessionLocation = location) }
                when (val started = sessionService.startSubSession(current.id, location)) {
                  is LoupeResult.Success -> update { it.selecting(started.value).copy(subSessionMenu = null, view = WorkspaceView.Terminal) }
                  is LoupeResult.Failure -> fail(ErrorMessages.of(started.error))
                }
              }
            }
          }
          is WorkspaceEvent.SelectView -> {
            update { it.copy(view = event.view, previewOpen = false, dragging = null) }
            if (event.view == WorkspaceView.Diff && current != null) scope.launch { reviewService.refresh(current.id) }
          }

          WorkspaceEvent.RefreshDiff -> current?.let { scope.launch { reviewService.refresh(it.id) } }
          is WorkspaceEvent.ToggleFileExpanded -> {
            val file = fileOf(event.fileId)
            if (file != null) {
              update { it.copy(fileExpansion = it.fileExpansion + (file.path to !DiffMapper.isExpanded(file, it))) }
            }
          }
          is WorkspaceEvent.ToggleFullFile -> update {
            val full = if (event.fileId in it.fullFiles) it.fullFiles - event.fileId else it.fullFiles + event.fileId
            it.copy(fullFiles = full, fileExpansion = it.fileExpansion + (event.fileId to true))
          }
          is WorkspaceEvent.ShowFullFile -> update { it.copy(fullFiles = it.fullFiles + event.fileId) }

          is WorkspaceEvent.StartCommentRange -> update { it.copy(dragging = LineRange(event.fileId, event.lineIndex, event.lineIndex)) }
          is WorkspaceEvent.DragCommentRange -> update { state ->
            state.copy(dragging = state.dragging?.takeIf { it.filePath == event.fileId }?.copy(focusIndex = event.lineIndex) ?: state.dragging)
          }
          WorkspaceEvent.EndCommentRange -> update { state ->
            val range = state.dragging ?: return@update state
            val normalized = LineRange(range.filePath, range.first, range.last)
            val keep = state.composer?.takeIf { it.range == normalized }?.draft.orEmpty()
            state.copy(dragging = null, composer = Composer(normalized, keep))
          }
          is WorkspaceEvent.ExtendCommentRange -> update { state ->
            val composer = state.composer?.takeIf { it.range.filePath == event.fileId }
            if (composer == null) {
              state.copy(composer = Composer(LineRange(event.fileId, event.lineIndex, event.lineIndex), ""))
            } else {
              val range = LineRange(event.fileId, minOf(composer.range.first, event.lineIndex), maxOf(composer.range.last, event.lineIndex))
              state.copy(composer = composer.copy(range = range))
            }
          }
          is WorkspaceEvent.UpdateCommentDraft -> update { state -> state.copy(composer = state.composer?.copy(draft = event.text)) }
          WorkspaceEvent.CancelComment -> update { it.copy(composer = null) }
          WorkspaceEvent.AddComment -> {
            val composer = view.composer
            val file = fileOf(composer?.range?.filePath)
            if (current != null && composer != null && file != null && composer.draft.isNotBlank()) {
              val _ = reviewService.addComment(
                current.id,
                NewComment(
                  filePath = file.path,
                  start = DiffMapper.anchorOf(file.lines[composer.range.first]),
                  end = DiffMapper.anchorOf(file.lines[composer.range.last]),
                  body = composer.draft,
                ),
              )
              update { it.copy(composer = null) }
            }
          }
          is WorkspaceEvent.DeleteComment -> current?.let { reviewService.deleteComment(it.id, CommentId(event.commentId)) }

          WorkspaceEvent.TogglePreview -> update { it.copy(previewOpen = !it.previewOpen) }
          WorkspaceEvent.SendToSession -> current?.let { session ->
            scope.launch {
              when (val sent = reviewService.sendToSession(session.id)) {
                is LoupeResult.Success -> update { it.copy(view = WorkspaceView.Terminal, previewOpen = false, composer = null) }
                is LoupeResult.Failure -> ErrorMessages.of(sent.error)?.let(::fail)
              }
            }
          }
          WorkspaceEvent.SendToNewSubSession -> current?.let { session ->
            scope.launch {
              when (val started = reviewService.sendToNewSubSession(session.id)) {
                is LoupeResult.Success -> update { it.selecting(started.value).copy(view = WorkspaceView.Terminal) }
                is LoupeResult.Failure -> ErrorMessages.of(started.error)?.let(::fail)
              }
            }
          }
          WorkspaceEvent.DismissError -> update { it.copy(error = null) }
        }
      }
    }

    val subSessionLocation = view.subSessionMenu ?: settings.subSessionLocation
    val input = WorkspaceInput(
      sessions = sessions,
      review = review,
      settings = settings,
      view = view,
      subSessionPlan = selectedId?.let { sessionService.planSubSession(it, subSessionLocation) },
      pendingMessage = if (view.previewOpen) selectedId?.let(reviewService::pendingMessage) else null,
      homeDirectory = homeDirectory.path,
    )
    return WorkspaceModelMapper.map(input, eventSink)
  }

  /** An agent finishing its work usually means files changed, so the diff is re-read. */
  @Composable
  private fun RefreshWhenTerminalSettles(selected: Session?) {
    var previous by remember { mutableStateOf<Pair<SessionId, SessionStatus>?>(null) }
    val id = selected?.id
    val status = selected?.status
    LaunchedEffect(id, status) {
      val before = previous
      if (id != null && before?.first == id && before.second == SessionStatus.Working && status != SessionStatus.Working) {
        reviewService.refresh(id)
      }
      previous = if (id != null && status != null) id to status else null
    }
  }
}

private fun WorkspaceViewState.selecting(id: SessionId): WorkspaceViewState = copy(
  selectedId = id,
  drawerOpen = false,
  composer = null,
  dragging = null,
  previewOpen = false,
  fileExpansion = emptyMap(),
  fullFiles = emptySet(),
)

private fun WorkspaceViewState.closingPopovers(): WorkspaceViewState = copy(settingsOpen = false, subSessionMenu = null)
