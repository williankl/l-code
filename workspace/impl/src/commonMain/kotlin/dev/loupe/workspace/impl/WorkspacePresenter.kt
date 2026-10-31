package dev.loupe.workspace.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import dev.loupe.domain.review.CommentId
import dev.loupe.domain.review.DiffState
import dev.loupe.domain.review.FileContent
import dev.loupe.domain.review.FileDiff
import dev.loupe.domain.review.FileVersion
import dev.loupe.domain.review.NewComment
import dev.loupe.domain.review.Review
import dev.loupe.domain.session.Session
import dev.loupe.domain.session.SessionId
import dev.loupe.domain.session.SessionStatus
import dev.loupe.domain.session.SubSessionLocation
import dev.loupe.platform.core.LoupeResult
import dev.loupe.platform.localization.LoupeString
import dev.loupe.platform.localization.asLoupeString
import dev.loupe.platform.presenter.LoupePresenter
import dev.loupe.platform.process.HomeDirectory
import dev.loupe.review.api.ReviewService
import dev.loupe.sessions.api.SessionService
import dev.loupe.settings.api.SettingsService
import dev.loupe.workspace.impl.WorkspaceModelMapper.toDomain
import dev.loupe.workspace.impl.WorkspaceModelMapper.toPreference
import dev.loupe.workspace.strings.Res
import dev.loupe.workspace.strings.session_path_copied
import dev.loupe.workspace.uimodel.WorkspaceEvent
import dev.loupe.workspace.uimodel.WorkspaceModel
import dev.loupe.workspace.uimodel.WorkspaceView
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
    val toastTimer = remember { ToastTimer() }

    val selected = WorkspaceModelMapper.selectedSession(sessions, view)
    val selectedId = selected?.id
    val review by remember(selectedId) { selectedId?.let(reviewService::review) ?: flowOf(null) }.collectAsState(initial = null)

    // Bumped whenever the diff is re-read, so open previews re-read their files too: a changed
    // image still diffs as "Binary files differ", so the diff alone can't tell it changed.
    var previewGeneration by remember { mutableIntStateOf(0) }
    val previewLoads = remember(selectedId) { mutableStateMapOf<String, PreviewLoad>() }
    LoadPreviews(selectedId, review, view.previews, previewGeneration, previewLoads)

    RefreshWhenTerminalSettles(selected, onRefresh = { previewGeneration++ })

    val eventSink: (WorkspaceEvent) -> Unit = remember {
      { event ->
        val current = WorkspaceModelMapper.selectedSession(sessionService.sessions.value, view)
        fun update(transform: (WorkspaceViewState) -> WorkspaceViewState) {
          view = transform(view)
        }
        fun fail(message: LoupeString) = update { it.copy(error = message) }
        fun fileOf(path: String?): FileDiff? = (review?.diff as? DiffState.Ready)?.snapshot?.files?.firstOrNull { it.path == path }
        fun sessionOf(id: String): Session? = sessionService.sessions.value.firstOrNull { it.id.value == id }
        fun close(id: SessionId) {
          val closing = SessionsMapper.subtree(sessionService.sessions.value, id)
          sessionService.close(id)
          update { state ->
            state.copy(
              sessionMenu = state.sessionMenu?.takeUnless { it in closing },
              renaming = state.renaming?.takeUnless { it in closing },
            )
          }
        }

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
          WorkspaceEvent.CloseSelectedSession -> current?.let { close(it.id) }
          is WorkspaceEvent.CloseSession -> close(SessionId(event.sessionId))

          is WorkspaceEvent.OpenSessionMenu -> sessionOf(event.sessionId)?.let { session ->
            update { it.closingPopovers().copy(sessionMenu = session.id) }
          }
          WorkspaceEvent.DismissSessionMenu -> update { it.copy(sessionMenu = null, renaming = null) }
          is WorkspaceEvent.CreateSubSessionFrom -> {
            val parent = sessionOf(event.sessionId)
            val location = event.location.toDomain()
            update { it.copy(sessionMenu = null) }
            if (parent != null) {
              scope.launch {
                settingsService.update { it.copy(subSessionLocation = location) }
                when (val started = sessionService.startSubSession(parent.id, location)) {
                  is LoupeResult.Success -> update { it.selecting(started.value).copy(view = WorkspaceView.Terminal) }
                  is LoupeResult.Failure -> fail(ErrorMessages.of(started.error))
                }
              }
            }
          }
          // The menu stays open around the name field, where it was.
          is WorkspaceEvent.StartRenamingSession -> sessionOf(event.sessionId)?.let { session ->
            update { it.closingPopovers().copy(sessionMenu = session.id, renaming = session.id) }
          }
          is WorkspaceEvent.RenameSession -> {
            val session = sessionOf(event.sessionId)
            // Only while the field is open: a second Enter, or one after Esc, renames nothing.
            if (session != null && view.renaming == session.id) {
              update { it.copy(renaming = null, sessionMenu = null) }
              // The automatic name, typed back in, means following the folder again.
              val custom = event.name.trim().ifEmpty { null }?.takeUnless { it == session.name }
              if (custom != session.customName) sessionService.rename(session.id, custom)
            }
          }
          WorkspaceEvent.CancelRenamingSession -> update { it.copy(renaming = null, sessionMenu = null) }
          is WorkspaceEvent.CopiedSessionPath -> {
            val message = Res.string.session_path_copied.asLoupeString(event.path)
            update { it.copy(sessionMenu = null, renaming = null, toast = message) }
            toastTimer.restart(scope) { update { state -> if (state.toast === message) state.copy(toast = null) else state } }
          }

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
            if (event.view == WorkspaceView.Diff && current != null) {
              previewGeneration++
              scope.launch { reviewService.refresh(current.id) }
            }
          }

          WorkspaceEvent.RefreshDiff -> current?.let {
            previewGeneration++
            scope.launch { reviewService.refresh(it.id) }
          }
          is WorkspaceEvent.ToggleFileExpanded -> {
            val file = fileOf(event.fileId)
            if (file != null) {
              update { state ->
                val expand = !DiffMapper.isBodyVisible(file, state)
                // A binary file has nothing to show but its preview, so opening it shows that.
                val previews = if (expand && file.isBinary && file.previewKind != null) state.previews + file.path else state.previews
                state.copy(fileExpansion = state.fileExpansion + (file.path to expand), previews = previews)
              }
            }
          }
          is WorkspaceEvent.ToggleFilePreview -> {
            val file = fileOf(event.fileId)
            if (file?.previewKind != null) {
              update { state ->
                if (file.path in state.previews) {
                  state.copy(previews = state.previews - file.path)
                } else {
                  val composer = state.composer?.takeUnless { it.range.filePath == file.path }
                  state.copy(previews = state.previews + file.path, fileExpansion = state.fileExpansion + (file.path to true), composer = composer)
                }
              }
            }
          }
          is WorkspaceEvent.SetPreviewLayout -> update { it.copy(previewLayouts = it.previewLayouts + (event.fileId to event.layout)) }
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
      menuWorktreePlan = view.sessionMenu?.let { sessionService.planSubSession(it, SubSessionLocation.NewWorktree) },
      pendingMessage = if (view.previewOpen) selectedId?.let(reviewService::pendingMessage) else null,
      homeDirectory = homeDirectory.path,
      previews = previewLoads,
    )
    return WorkspaceModelMapper.map(input, eventSink)
  }

  /**
   * Reads both sides of every file whose preview is open, again whenever [generation] moves or
   * the diff's base changes. Content that didn't change keeps its instance, so it isn't redrawn.
   */
  @Composable
  private fun LoadPreviews(
    sessionId: SessionId?,
    review: Review?,
    open: Set<String>,
    generation: Int,
    loads: SnapshotStateMap<String, PreviewLoad>,
  ) {
    val snapshot = (review?.diff as? DiffState.Ready)?.snapshot
    LaunchedEffect(open) { (loads.keys - open).forEach { loads.remove(it) } }
    open.forEach { path ->
      key(path) {
        val file = snapshot?.files?.firstOrNull { it.path == path }
        LaunchedEffect(sessionId, snapshot?.mergeBase, file?.change, file?.previousPath, file != null, generation) {
          if (sessionId == null || file == null) return@LaunchedEffect
          val before = async { reviewService.fileContent(sessionId, path, FileVersion.Base) }
          val after = async { reviewService.fileContent(sessionId, path, FileVersion.WorkingTree) }
          val beforeResult = before.await()
          val afterResult = after.await()
          val previous = loads[path] ?: PreviewLoad()
          loads[path] = if (beforeResult is LoupeResult.Success && afterResult is LoupeResult.Success) {
            PreviewLoad(before = keepIfSame(previous.before, beforeResult.value), after = keepIfSame(previous.after, afterResult.value))
          } else {
            val error = (beforeResult as? LoupeResult.Failure)?.error ?: (afterResult as LoupeResult.Failure).error
            previous.copy(error = ErrorMessages.of(error))
          }
        }
      }
    }
  }

  private fun keepIfSame(previous: FileContent?, next: FileContent): FileContent = if (previous is FileContent.Bytes && next is FileContent.Bytes && previous.bytes.contentEquals(next.bytes)) previous else next

  /** An agent finishing its work usually means files changed, so the diff is re-read. */
  @Composable
  private fun RefreshWhenTerminalSettles(selected: Session?, onRefresh: () -> Unit) {
    var previous by remember { mutableStateOf<Pair<SessionId, SessionStatus>?>(null) }
    val id = selected?.id
    val status = selected?.status
    LaunchedEffect(id, status) {
      val before = previous
      if (id != null && before?.first == id && before.second == SessionStatus.Working && status != SessionStatus.Working) {
        onRefresh()
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
  previews = emptySet(),
  previewLayouts = emptyMap(),
  sessionMenu = null,
)

private fun WorkspaceViewState.closingPopovers(): WorkspaceViewState = copy(settingsOpen = false, subSessionMenu = null, sessionMenu = null)

/** How long a toast stays up, as in the design. */
internal const val ToastMillis: Long = 2_200

/** Hides the toast after [ToastMillis]; a newer toast starts the wait again. */
private class ToastTimer {
  private var job: Job? = null

  fun restart(scope: CoroutineScope, hide: () -> Unit) {
    job?.cancel()
    job = scope.launch {
      delay(ToastMillis)
      hide()
    }
  }
}
