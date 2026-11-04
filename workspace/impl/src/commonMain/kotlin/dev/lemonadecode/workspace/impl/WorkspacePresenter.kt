package dev.lemonadecode.workspace.impl

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
import dev.lemonadecode.domain.mobile.CablePairing
import dev.lemonadecode.domain.mobile.DeviceButton
import dev.lemonadecode.domain.mobile.DeviceId
import dev.lemonadecode.domain.mobile.DeviceKey
import dev.lemonadecode.domain.mobile.DeviceLogLine
import dev.lemonadecode.domain.mobile.LogLevel
import dev.lemonadecode.domain.mobile.LogPackages
import dev.lemonadecode.domain.mobile.MobileDevice
import dev.lemonadecode.domain.mobile.MobileError
import dev.lemonadecode.domain.mobile.NearbyKind
import dev.lemonadecode.domain.mobile.QrPairing
import dev.lemonadecode.domain.review.CommentId
import dev.lemonadecode.domain.review.DiffState
import dev.lemonadecode.domain.review.FileContent
import dev.lemonadecode.domain.review.FileDiff
import dev.lemonadecode.domain.review.FileVersion
import dev.lemonadecode.domain.review.NewComment
import dev.lemonadecode.domain.review.Review
import dev.lemonadecode.domain.session.LinkedWorktree
import dev.lemonadecode.domain.session.Session
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SessionStatus
import dev.lemonadecode.domain.session.SubSessionLocation
import dev.lemonadecode.domain.settings.DevicesLayout
import dev.lemonadecode.mobile.api.MobileService
import dev.lemonadecode.platform.architecture.WallClock
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.localization.LemonadeCodeString
import dev.lemonadecode.platform.localization.asLemonadeCodeString
import dev.lemonadecode.platform.localization.systemLanguageTag
import dev.lemonadecode.platform.presenter.LemonadeCodePresenter
import dev.lemonadecode.platform.process.HomeDirectory
import dev.lemonadecode.platform.process.abbreviateHome
import dev.lemonadecode.platform.sound.Chime
import dev.lemonadecode.platform.sound.SoundPlayer
import dev.lemonadecode.review.api.ReviewService
import dev.lemonadecode.sessions.api.SessionError
import dev.lemonadecode.sessions.api.SessionService
import dev.lemonadecode.settings.api.SettingsService
import dev.lemonadecode.updates.api.UpdateService
import dev.lemonadecode.workspace.impl.DevicesMapper.toDomain
import dev.lemonadecode.workspace.impl.SettingsMapper.toDomain
import dev.lemonadecode.workspace.impl.WorkspaceModelMapper.toDomain
import dev.lemonadecode.workspace.impl.WorkspaceModelMapper.toPreference
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.devices_disconnected
import dev.lemonadecode.workspace.strings.file_path_copied
import dev.lemonadecode.workspace.strings.logs_errors_sent
import dev.lemonadecode.workspace.strings.screenshot_attached
import dev.lemonadecode.workspace.strings.screenshot_failed
import dev.lemonadecode.workspace.strings.session_path_copied
import dev.lemonadecode.workspace.strings.worktree_deleted
import dev.lemonadecode.workspace.strings.worktrees_deleted
import dev.lemonadecode.workspace.uimodel.DeviceKeyModel
import dev.lemonadecode.workspace.uimodel.DeviceTab
import dev.lemonadecode.workspace.uimodel.DevicesLayoutOption
import dev.lemonadecode.workspace.uimodel.DevicesWindow
import dev.lemonadecode.workspace.uimodel.PairingMethod
import dev.lemonadecode.workspace.uimodel.PreviewAssetLoader
import dev.lemonadecode.workspace.uimodel.SettingsPage
import dev.lemonadecode.workspace.uimodel.StatusSound
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import dev.lemonadecode.workspace.uimodel.WorkspaceModel
import dev.lemonadecode.workspace.uimodel.WorkspaceView
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The Lemonade Code workspace: sessions, their terminals and the diff review. Owns only UI state;
 * sessions, reviews and settings live in their services.
 */
@Inject
public class WorkspacePresenter internal constructor(
  private val sessionService: SessionService,
  private val reviewService: ReviewService,
  private val settingsService: SettingsService,
  private val homeDirectory: HomeDirectory,
  private val updateService: UpdateService,
  private val mobileService: MobileService,
  private val soundPlayer: SoundPlayer,
  private val clock: WallClock,
) : LemonadeCodePresenter<WorkspaceModel>() {
  @Composable
  override fun present(): WorkspaceModel {
    val sessions by sessionService.sessions.collectAsState()
    val settings by settingsService.settings.collectAsState()
    val updateState by updateService.state.collectAsState()
    val updateChecks by updateService.checks.collectAsState()
    val mobileTools by mobileService.tools.collectAsState()
    val mobileDevices by mobileService.devices.collectAsState()
    var view by remember { mutableStateOf(WorkspaceViewState()) }
    val scope = rememberCoroutineScope()
    val toastTimer = remember { ToastTimer() }
    // Sessions whose worktrees are being looked up before they close: a second ⌘W meanwhile asks nothing twice.
    val lookingUp = remember { mutableSetOf<SessionId>() }

    val selected = WorkspaceModelMapper.selectedSession(sessions, view)
    val selectedId = selected?.id
    val review by remember(selectedId) { selectedId?.let(reviewService::review) ?: flowOf(null) }.collectAsState(initial = null)

    // Bumped whenever the diff is re-read, so open previews re-read their files too: a changed
    // image still diffs as "Binary files differ", so the diff alone can't tell it changed.
    var previewGeneration by remember { mutableIntStateOf(0) }
    val previewLoads = remember(selectedId) { mutableStateMapOf<String, PreviewLoad>() }
    LoadPreviews(selectedId, review, DiffMapper.openPreviews(review, view), previewGeneration, previewLoads)
    // A new reader each time the diff is re-read, so a README's pictures are read again too.
    val previewAssets = remember(selectedId, previewGeneration) {
      selectedId?.let { ReviewPreviewAssets(reviewService, it) } ?: PreviewAssetLoader.None
    }

    RefreshWhenTerminalSettles(selected, onRefresh = { previewGeneration++ })

    val diffRows = remember { DiffRowCache() }
    // The Devices panel reads only what its step shows.
    val devicesView = view.devices?.takeIf { settings.mobilePlugin }
    val openDevice = devicesView?.takeIf { it.step == DevicesStep.Device }?.deviceId
    val screenShown = openDevice != null && (settings.devicesLayout == DevicesLayout.SideBySide || devicesView.tab == DeviceTab.Screen)
    val logPackages = devicesView?.logPackages ?: LogPackages.AppInFront
    val deviceLogs by remember(openDevice, logPackages) { openDevice?.let { mobileService.logs(it, logPackages) } ?: flowOf(null) }.collectAsState(initial = null)
    val appsShown = openDevice != null && devicesView?.logAppsOpen == true
    val deviceApps by remember(openDevice, appsShown) { if (appsShown && openDevice != null) mobileService.apps(openDevice) else flowOf(null) }.collectAsState(initial = null)
    val deviceScreen by remember(openDevice, screenShown) { if (screenShown && openDevice != null) mobileService.screen(openDevice) else flowOf(null) }.collectAsState(initial = null)
    val nearbyPlatform = devicesView?.takeIf { it.step == DevicesStep.PairMethod || it.step == DevicesStep.PairNearby }?.pairPlatform
    val nearby by remember(nearbyPlatform) { nearbyPlatform?.let(mobileService::nearby) ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())
    val pairingAttempt = devicesView?.attempt ?: 0
    val qrShown = devicesView?.step == DevicesStep.PairQr
    val qrPairing by remember(qrShown, pairingAttempt) { if (qrShown) mobileService.pairWithQr() else flowOf(null) }.collectAsState(initial = null)
    val cableShown = devicesView?.step == DevicesStep.PairCable
    val cablePairing by remember(cableShown, pairingAttempt) { if (cableShown) mobileService.pairOverCable() else flowOf(null) }.collectAsState(initial = null)
    LaunchedEffect(qrPairing, cablePairing) {
      val added = (qrPairing as? QrPairing.Paired)?.device ?: (cablePairing as? CablePairing.Paired)?.device ?: return@LaunchedEffect
      val panel = view.devices ?: return@LaunchedEffect
      if (panel.step == DevicesStep.PairQr || panel.step == DevicesStep.PairCable) {
        view = view.copy(devices = panel.copy(step = DevicesStep.Paired, paired = added.id))
      }
    }

    val eventSink: (WorkspaceEvent) -> Unit = remember {
      { event ->
        val current = WorkspaceModelMapper.selectedSession(sessionService.sessions.value, view)
        fun update(transform: (WorkspaceViewState) -> WorkspaceViewState) {
          view = transform(view)
        }
        fun fail(message: LemonadeCodeString) = update { it.copy(error = message) }
        fun updateDevices(transform: (DevicesViewState) -> DevicesViewState) = update { state -> state.devices?.let { state.copy(devices = transform(it)) } ?: state }
        fun showToast(message: LemonadeCodeString) {
          update { it.copy(toast = message) }
          toastTimer.restart(scope) { update { state -> if (state.toast === message) state.copy(toast = null) else state } }
        }
        fun onDevice(action: suspend (DeviceId) -> LemonadeCodeResult<Unit, *>) {
          val device = view.devices?.deviceId ?: return
          scope.launch { val _ = action(device) }
        }
        fun fileOf(path: String?): FileDiff? = (review?.diff as? DiffState.Ready)?.snapshot?.files?.firstOrNull { it.path == path }
        fun commented(path: String): Boolean = review?.comments.orEmpty().any { it.filePath == path }
        fun sessionOf(id: String): Session? = sessionService.sessions.value.firstOrNull { it.id.value == id }
        fun closeMenusOf(closing: Set<SessionId>) = update { state ->
          state.copy(
            sessionMenu = state.sessionMenu?.takeUnless { it in closing },
            renaming = state.renaming?.takeUnless { it in closing },
            namingWorktree = state.namingWorktree?.takeUnless { it in closing },
          )
        }
        fun close(id: SessionId) {
          val closing = SessionsMapper.subtree(sessionService.sessions.value, id)
          sessionService.close(id)
          closeMenusOf(closing)
        }

        /**
         * Closes [id] and its sub-sessions, but first asks whether to delete the worktrees that
         * leaves with no session in them, if there are any. Outside git there are none to ask about.
         */
        fun requestClose(id: SessionId) {
          val all = sessionService.sessions.value
          val session = all.firstOrNull { it.id == id } ?: return
          val closing = SessionsMapper.subtree(all, id)
          val doomed = all.filter { it.id in closing }
          if (doomed.none { it.repositoryRoot != null }) return close(id)
          if (!lookingUp.add(id)) return
          closeMenusOf(closing)
          scope.launch {
            val found = try {
              doomed.map { async { sessionService.worktreeOf(it.id) } }.awaitAll()
            } finally {
              lookingUp.remove(id)
            }
            val now = sessionService.sessions.value
            if (now.none { it.id == id }) return@launch
            val unused = ClosingSessions.leftUnused(found, staying = now.filterNot { it.id in closing })
            if (unused.isEmpty()) {
              close(id)
            } else {
              update { it.closingPopovers().copy(closing = ClosingSession(id, session.displayName, closing.size - 1, unused)) }
            }
          }
        }

        /**
         * Deletes [worktrees], once the sessions in them have closed. Only those the dialog said had
         * changes are forced; any that changed since stay, and say why.
         */
        fun deleteWorktrees(worktrees: List<LinkedWorktree>) {
          // Again: a session may have moved into one while the dialog was open.
          val unused = ClosingSessions.leftUnused(worktrees, staying = sessionService.sessions.value)
          if (unused.isEmpty()) return
          scope.launch {
            val deleted = unused.filter { worktree ->
              when (val removed = sessionService.removeWorktree(worktree, force = worktree.hasChanges)) {
                is LemonadeCodeResult.Success -> true
                is LemonadeCodeResult.Failure -> {
                  fail(ErrorMessages.of(removed.error, homeDirectory.path))
                  false
                }
              }
            }
            when {
              deleted.size == 1 -> showToast(Res.string.worktree_deleted.asLemonadeCodeString(abbreviateHome(deleted.single().path, homeDirectory.path)))
              deleted.size > 1 -> showToast(Res.plurals.worktrees_deleted.asLemonadeCodeString(deleted.size, deleted.size))
            }
          }
        }

        /**
         * Starts a sub-session of [parentId], a new worktree on [branch] when given, and selects it.
         * Nothing happens while another is on its way, or for a branch that can't be used.
         */
        fun startSubSession(parentId: SessionId, location: SubSessionLocation, branch: String?) {
          if (view.startingSubSession || (branch != null && !WorkspaceModelMapper.canUseWorktreeBranch(branch, view.takenBranch))) return
          update { it.copy(startingSubSession = true) }
          scope.launch {
            settingsService.update { it.copy(subSessionLocation = location) }
            val started = sessionService.startSubSession(parentId, location, branch = branch)
            update { it.copy(startingSubSession = false) }
            when (started) {
              is LemonadeCodeResult.Success -> update {
                it.selecting(started.value).copy(subSessionMenu = null, worktreeBranch = null, namingWorktree = null, view = WorkspaceView.Terminal)
              }
              is LemonadeCodeResult.Failure -> when (val error = started.error) {
                // Said under the field, which stays open for another name.
                is SessionError.BranchExists -> update { it.copy(takenBranch = error.branch) }
                else -> fail(ErrorMessages.of(error))
              }
            }
          }
        }

        when (event) {
          is WorkspaceEvent.ToggleSessionsPanel -> if (event.narrowLayout) {
            update { it.copy(drawerOpen = !it.drawerOpen) }
          } else {
            scope.launch { settingsService.update { it.copy(sidebarExpanded = !it.sidebarExpanded) } }
          }
          WorkspaceEvent.CloseDrawer -> update { it.copy(drawerOpen = false) }
          is WorkspaceEvent.ResizeSessionsSidebar -> {
            val width = WorkspaceModelMapper.clampSidebarWidth(event.width)
            scope.launch { settingsService.update { it.copy(sidebarWidth = width) } }
          }
          is WorkspaceEvent.ResizeDevicesPanel -> {
            val width = DevicesMapper.clampPanelWidth(event.width)
            val wide = DevicesMapper.isWide(view, mobileDevices, settings.devicesLayout)
            scope.launch { settingsService.update { if (wide) it.copy(devicesPanelWideWidth = width) else it.copy(devicesPanelWidth = width) } }
          }
          is WorkspaceEvent.SelectSession -> update { it.selecting(SessionId(event.sessionId)) }
          is WorkspaceEvent.ToggleSessionFold -> update {
            val id = SessionId(event.sessionId)
            it.copy(folded = if (id in it.folded) it.folded - id else it.folded + id)
          }
          WorkspaceEvent.CloseSelectedSession -> current?.let { requestClose(it.id) }
          is WorkspaceEvent.CloseSession -> requestClose(SessionId(event.sessionId))
          is WorkspaceEvent.ConfirmCloseSession -> view.closing?.let { closing ->
            update { it.copy(closing = null) }
            // The sessions go first, so no shell is left in a folder about to be deleted.
            close(closing.sessionId)
            if (event.deleteWorktrees) deleteWorktrees(closing.worktrees)
          }
          WorkspaceEvent.CancelCloseSession -> update { it.copy(closing = null) }

          is WorkspaceEvent.OpenSessionMenu -> sessionOf(event.sessionId)?.let { session ->
            update { it.closingPopovers().copy(sessionMenu = session.id) }
          }
          WorkspaceEvent.DismissSessionMenu -> update { it.copy(sessionMenu = null, renaming = null, namingWorktree = null, worktreeBranch = null) }
          is WorkspaceEvent.CreateSubSessionFrom -> sessionOf(event.sessionId)?.let { parent ->
            val location = event.location.toDomain()
            val naming = view.namingWorktree == parent.id && location == SubSessionLocation.NewWorktree
            // The branch field stays open until the worktree exists, to say what was wrong with the name.
            if (!naming) update { it.copy(sessionMenu = null) }
            startSubSession(parent.id, location, branch = view.worktreeBranch.takeIf { naming })
          }
          // The menu stays open around the branch field, where it was.
          is WorkspaceEvent.StartNamingWorktree -> sessionOf(event.sessionId)?.takeIf { it.repositoryRoot != null }?.let { session ->
            update { it.closingPopovers().copy(sessionMenu = session.id, namingWorktree = session.id, worktreeBranch = sessionService.suggestWorktreeBranch()) }
          }
          is WorkspaceEvent.SetWorktreeBranch -> update { if (it.worktreeBranch != null) it.copy(worktreeBranch = event.name) else it }
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
            val message = Res.string.session_path_copied.asLemonadeCodeString(event.path)
            update { it.copy(sessionMenu = null, renaming = null, toast = message) }
            toastTimer.restart(scope) { update { state -> if (state.toast === message) state.copy(toast = null) else state } }
          }

          WorkspaceEvent.NewSession -> scope.launch {
            when (val opened = sessionService.openTerminal()) {
              is LemonadeCodeResult.Success -> update { it.selecting(opened.value).copy(view = WorkspaceView.Terminal) }
              is LemonadeCodeResult.Failure -> fail(ErrorMessages.of(opened.error))
            }
          }

          WorkspaceEvent.ToggleSettings -> update {
            if (it.settingsOpen) it.copy(settingsOpen = false) else it.closingPopovers().copy(settingsOpen = true, settingsPage = SettingsPage.List)
          }
          WorkspaceEvent.DismissSettings -> update { it.copy(settingsOpen = false) }
          is WorkspaceEvent.OpenSettingsPage -> update { it.copy(settingsOpen = true, settingsPage = event.page) }
          WorkspaceEvent.SettingsBack -> update { if (it.settingsPage != SettingsPage.List) it.copy(settingsPage = SettingsPage.List) else it.copy(settingsOpen = false) }
          is WorkspaceEvent.SetTheme -> scope.launch { settingsService.update { it.copy(theme = event.theme.toPreference()) } }
          is WorkspaceEvent.SetLanguage -> scope.launch { settingsService.update { it.copy(language = event.language.toDomain()) } }
          is WorkspaceEvent.SetKeepAwake -> scope.launch { settingsService.update { it.copy(keepAwake = event.enabled) } }
          is WorkspaceEvent.SetStatusSounds -> scope.launch { settingsService.update { it.copy(statusSounds = event.enabled) } }
          is WorkspaceEvent.PlayStatusSound -> soundPlayer.play(
            when (event.sound) {
              StatusSound.Finished -> Chime.Ding
              StatusSound.NeedsInput -> Chime.DoubleDing
            },
          )
          is WorkspaceEvent.SetMobilePlugin -> {
            if (!event.enabled) update { it.copy(devices = null) }
            scope.launch { settingsService.update { it.copy(mobilePlugin = event.enabled) } }
          }

          WorkspaceEvent.CheckForUpdates -> scope.launch { updateService.check() }
          // The Updates page stays open to show the download's progress.
          WorkspaceEvent.InstallUpdate -> updateService.install()
          WorkspaceEvent.RestartToUpdate -> updateService.restart()

          WorkspaceEvent.ToggleDevices -> update { state ->
            when {
              state.devices == null -> state.closingPopovers().copy(devices = DevicesViewState())
              state.devicesWindow == DevicesWindow.Separate -> state.copy(devicesFocusRequest = state.devicesFocusRequest + 1)
              else -> state.copy(devices = null)
            }
          }
          WorkspaceEvent.OpenDevices -> update { state ->
            val panel = (state.devices ?: DevicesViewState()).copy(step = DevicesStep.List)
            state.closingPopovers().copy(devices = panel, devicesFocusRequest = state.devicesFocusRequest + 1)
          }
          WorkspaceEvent.CloseDevices -> update { it.copy(devices = null) }
          WorkspaceEvent.DevicesBack -> updateDevices { panel ->
            val back = when (panel.step) {
              DevicesStep.List, DevicesStep.Device, DevicesStep.PairPlatform, DevicesStep.Paired -> DevicesStep.List
              DevicesStep.PairMethod -> DevicesStep.PairPlatform
              DevicesStep.PairQr, DevicesStep.PairCode, DevicesStep.PairNearby, DevicesStep.PairCable -> DevicesStep.PairMethod
            }
            panel.copy(step = back, pairError = null, pairing = false, connecting = null, keyboard = false)
          }
          is WorkspaceEvent.MoveDevices -> update { it.copy(devicesWindow = event.window) }
          is WorkspaceEvent.SetDevicesLayout -> scope.launch {
            settingsService.update { it.copy(devicesLayout = if (event.layout == DevicesLayoutOption.Tabs) DevicesLayout.Tabs else DevicesLayout.SideBySide) }
          }
          is WorkspaceEvent.SelectDevice -> updateDevices {
            it.copy(
              step = DevicesStep.Device,
              deviceId = DeviceId(event.deviceId),
              keyboard = false,
              logSearch = false,
              logQuery = "",
              pausedAt = null,
              logsCleared = false,
              logPackages = LogPackages.AppInFront,
              logAppsOpen = false,
            )
          }
          is WorkspaceEvent.SetDeviceTab -> updateDevices { it.copy(tab = event.tab) }
          is WorkspaceEvent.DisconnectDevice -> {
            val device = mobileService.devices.value.firstOrNull { it.id.value == event.deviceId }
            if (device != null) {
              scope.launch {
                when (val dropped = mobileService.disconnect(device.id)) {
                  is LemonadeCodeResult.Success -> {
                    updateDevices { it.copy(step = DevicesStep.List) }
                    showToast(Res.string.devices_disconnected.asLemonadeCodeString(device.name))
                  }
                  is LemonadeCodeResult.Failure -> fail(DevicesMapper.errorText(dropped.error))
                }
              }
            }
          }
          is WorkspaceEvent.TapDevice -> onDevice { mobileService.tap(it, event.x, event.y) }
          is WorkspaceEvent.SwipeDevice -> onDevice { mobileService.swipe(it, event.fromX, event.fromY, event.toX, event.toY, event.durationMillis) }
          WorkspaceEvent.PressDeviceBack -> onDevice { mobileService.press(it, DeviceButton.Back) }
          WorkspaceEvent.PressDeviceHome -> onDevice { mobileService.press(it, DeviceButton.Home) }
          WorkspaceEvent.RotateDevice -> onDevice { mobileService.rotate(it) }
          is WorkspaceEvent.TypeOnDevice -> onDevice { mobileService.type(it, event.text) }
          is WorkspaceEvent.PressDeviceKey -> onDevice { mobileService.key(it, event.key.toDomain()) }
          WorkspaceEvent.ToggleDeviceKeyboard -> updateDevices { it.copy(keyboard = !it.keyboard) }
          WorkspaceEvent.ScreenshotToSession -> {
            val device = view.devices?.deviceId
            if (device != null && current != null) {
              scope.launch {
                when (val shot = mobileService.screenshot(device)) {
                  is LemonadeCodeResult.Success -> when (val pasted = sessionService.paste(current.id, quotedPath(shot.value) + " ")) {
                    is LemonadeCodeResult.Success -> showToast(Res.string.screenshot_attached.asLemonadeCodeString(current.displayName))
                    is LemonadeCodeResult.Failure -> fail(ErrorMessages.of(pasted.error))
                  }
                  is LemonadeCodeResult.Failure -> fail(Res.string.screenshot_failed.asLemonadeCodeString(DevicesMapper.errorText(shot.error)))
                }
              }
            }
          }
          is WorkspaceEvent.SetLogFilter -> updateDevices { it.copy(logFilter = event.filter) }
          WorkspaceEvent.ToggleLogSearch -> updateDevices { it.copy(logSearch = !it.logSearch, logQuery = if (it.logSearch) "" else it.logQuery) }
          is WorkspaceEvent.SetLogQuery -> updateDevices { it.copy(logQuery = event.query) }
          WorkspaceEvent.ClearLogs -> view.devices?.deviceId?.let { device ->
            mobileService.clearLogs(device)
            updateDevices { it.copy(logsCleared = true, pausedAt = null) }
          }
          WorkspaceEvent.ToggleLogsPaused -> updateDevices { panel ->
            if (panel.pausedAt != null) panel.copy(pausedAt = null) else panel.copy(pausedAt = deviceLogs?.lines?.lastOrNull()?.sequence ?: -1L)
          }
          WorkspaceEvent.SendLogErrors -> {
            val panel = view.devices
            val device = panel?.deviceId?.let { id -> mobileService.devices.value.firstOrNull { it.id == id } }
            val errors = panel?.let { DevicesMapper.shownErrors(deviceLogs, it) }.orEmpty()
            if (device != null && current != null && errors.isNotEmpty()) {
              when (val pasted = sessionService.paste(current.id, errorReport(device.name, deviceLogs?.source.orEmpty(), errors))) {
                is LemonadeCodeResult.Success -> showToast(Res.plurals.logs_errors_sent.asLemonadeCodeString(errors.size, errors.size, current.displayName))
                is LemonadeCodeResult.Failure -> fail(ErrorMessages.of(pasted.error))
              }
            }
          }
          WorkspaceEvent.ToggleLogApps -> updateDevices { it.copy(logAppsOpen = !it.logAppsOpen) }
          WorkspaceEvent.DismissLogApps -> updateDevices { it.copy(logAppsOpen = false) }
          WorkspaceEvent.FollowAppInFront -> updateDevices { it.showingLogsOf(LogPackages.AppInFront).copy(logAppsOpen = false) }
          WorkspaceEvent.ShowAllApps -> updateDevices { it.showingLogsOf(LogPackages.All).copy(logAppsOpen = false) }
          is WorkspaceEvent.ToggleLogApp -> updateDevices { panel ->
            val ticked = (panel.logPackages as? LogPackages.Only)?.packages.orEmpty()
            panel.showingLogsOf(LogPackages.Only(if (event.packageName in ticked) ticked - event.packageName else ticked + event.packageName))
          }
          WorkspaceEvent.StartPairing -> updateDevices { DevicesViewState(step = DevicesStep.PairPlatform, attempt = it.attempt) }
          is WorkspaceEvent.ChoosePairingPlatform -> updateDevices { it.copy(step = DevicesStep.PairMethod, pairPlatform = event.platform.toDomain(), pairError = null) }
          is WorkspaceEvent.ChoosePairingMethod -> updateDevices { panel ->
            val step = when (event.method) {
              PairingMethod.QrCode -> DevicesStep.PairQr
              PairingMethod.PairingCode -> DevicesStep.PairCode
              PairingMethod.Nearby -> DevicesStep.PairNearby
              PairingMethod.Cable -> DevicesStep.PairCable
            }
            panel.copy(step = step, attempt = panel.attempt + 1, pairError = null, pairing = false, connecting = null)
          }
          is WorkspaceEvent.SetPairingAddress -> updateDevices { it.copy(pairAddress = event.address, pairError = null) }
          is WorkspaceEvent.SetPairingCode -> updateDevices { it.copy(pairCode = event.code.filter(Char::isDigit).take(6), pairError = null) }
          WorkspaceEvent.PairWithCode -> {
            val panel = view.devices
            if (panel != null && !panel.pairing && DevicesMapper.canPairWithCode(panel.pairAddress, panel.pairCode)) {
              updateDevices { it.copy(pairing = true, pairError = null) }
              scope.launch {
                val paired = mobileService.pairWithCode(panel.pairAddress.trim(), panel.pairCode.trim())
                updateDevices { it.afterPairing(paired) }
              }
            }
          }
          is WorkspaceEvent.ConnectNearby -> {
            val found = nearby.firstOrNull { it.address == event.address }
            when {
              found == null -> Unit
              found.kind == NearbyKind.AwaitingCode -> updateDevices { it.copy(step = DevicesStep.PairCode, pairAddress = found.address, pairCode = "", pairError = null) }
              else -> {
                updateDevices { it.copy(connecting = found.address, pairError = null) }
                scope.launch {
                  val connected = mobileService.connect(found)
                  updateDevices { it.afterPairing(connected).copy(connecting = null) }
                }
              }
            }
          }
          WorkspaceEvent.OpenPairedDevice -> updateDevices { panel ->
            panel.paired?.let { panel.copy(step = DevicesStep.Device, deviceId = it, tab = DeviceTab.Screen) } ?: panel.copy(step = DevicesStep.List)
          }

          WorkspaceEvent.OpenParentSession -> current?.parentId?.let { parent -> update { it.selecting(parent) } }
          WorkspaceEvent.ToggleNewSubSessionMenu -> update {
            if (it.subSessionMenu != null) {
              it.copy(subSessionMenu = null, worktreeBranch = null)
            } else {
              val remembered = settingsService.settings.value.subSessionLocation
              val location = if (current?.repositoryRoot == null) SubSessionLocation.SameFolder else remembered
              it.closingPopovers().copy(subSessionMenu = location, worktreeBranch = sessionService.suggestWorktreeBranch())
            }
          }
          WorkspaceEvent.DismissNewSubSessionMenu -> update { it.copy(subSessionMenu = null, worktreeBranch = null) }
          is WorkspaceEvent.SetSubSessionLocation -> update { if (it.subSessionMenu != null) it.copy(subSessionMenu = event.location.toDomain()) else it }
          WorkspaceEvent.CreateSubSession -> {
            val location = view.subSessionMenu
            if (current != null && location != null) {
              startSubSession(current.id, location, branch = view.worktreeBranch.takeIf { location == SubSessionLocation.NewWorktree })
            }
          }
          is WorkspaceEvent.SelectView -> {
            val refresh = event.view == WorkspaceView.Diff && current != null
            // Before the view changes: the other order can read every open preview twice.
            if (refresh) previewGeneration++
            update { it.copy(view = event.view, previewOpen = false, dragging = null) }
            if (refresh && current != null) scope.launch { reviewService.refresh(current.id) }
          }

          WorkspaceEvent.RefreshDiff -> current?.let {
            previewGeneration++
            scope.launch { reviewService.refresh(it.id) }
          }
          WorkspaceEvent.ToggleDiffBasePicker -> update { if (it.basePickerOpen) it.copy(basePickerOpen = false) else it.closingPopovers().copy(basePickerOpen = true) }
          WorkspaceEvent.DismissDiffBasePicker -> update { it.copy(basePickerOpen = false) }
          is WorkspaceEvent.ShowFileList -> update { it.copy(fileList = event.shown) }
          is WorkspaceEvent.RevealFile -> update { it.copy(fileExpansion = it.fileExpansion + (event.fileId to true)) }
          is WorkspaceEvent.CompareWith -> {
            update { it.copy(basePickerOpen = false, dragging = null) }
            current?.let {
              previewGeneration++
              scope.launch { reviewService.compareWith(it.id, event.branch) }
            }
          }
          is WorkspaceEvent.ToggleFileExpanded -> {
            val file = fileOf(event.fileId)
            if (file != null) {
              update { state ->
                val expand = !DiffMapper.isBodyVisible(file, state, commented(file.path))
                // A binary file has nothing to show but its preview, so opening it shows that.
                val choices = if (expand && file.isBinary && file.previewKind != null) state.previewChoices + (file.path to true) else state.previewChoices
                state.copy(fileExpansion = state.fileExpansion + (file.path to expand), previewChoices = choices)
              }
            }
          }
          is WorkspaceEvent.ToggleFilePreview -> {
            val file = fileOf(event.fileId)
            if (file?.previewKind != null) {
              update { state ->
                if (DiffMapper.isShowingPreview(file, state, commented(file.path))) {
                  state.copy(previewChoices = state.previewChoices + (file.path to false))
                } else {
                  val composer = state.composer?.takeUnless { it.range.filePath == file.path }
                  state.copy(
                    previewChoices = state.previewChoices + (file.path to true),
                    fileExpansion = state.fileExpansion + (file.path to true),
                    composer = composer,
                  )
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
          is WorkspaceEvent.CopiedFilePath -> showToast(Res.string.file_path_copied.asLemonadeCodeString(event.path))

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
                is LemonadeCodeResult.Success -> update { it.copy(view = WorkspaceView.Terminal, previewOpen = false, composer = null) }
                is LemonadeCodeResult.Failure -> ErrorMessages.of(sent.error)?.let(::fail)
              }
            }
          }
          WorkspaceEvent.SendToNewSubSession -> current?.let { session ->
            scope.launch {
              when (val started = reviewService.sendToNewSubSession(session.id)) {
                is LemonadeCodeResult.Success -> update { it.selecting(started.value).copy(view = WorkspaceView.Terminal) }
                is LemonadeCodeResult.Failure -> ErrorMessages.of(started.error)?.let(::fail)
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
      subSessionPlan = selectedId?.let { sessionService.planSubSession(it, subSessionLocation, branch = view.worktreeBranch) },
      menuWorktreePlan = view.namingWorktree?.let { sessionService.planSubSession(it, SubSessionLocation.NewWorktree, branch = view.worktreeBranch) },
      pendingMessage = if (view.previewOpen) selectedId?.let(reviewService::pendingMessage) else null,
      homeDirectory = homeDirectory.path,
      previews = previewLoads,
      previewAssets = previewAssets,
      update = updateState,
      diffRows = diffRows,
      installation = updateService.installation,
      updateChecks = updateChecks,
      mobile = MobileInput(
        enabled = settings.mobilePlugin,
        tools = mobileTools,
        devices = mobileDevices,
        logs = deviceLogs,
        apps = deviceApps,
        screen = deviceScreen,
        nearby = nearby,
        qr = qrPairing,
        cable = cablePairing,
      ),
      nowMillis = clock.nowMillis(),
      systemLanguage = SettingsMapper.languageFor(systemLanguageTag()),
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
          loads[path] = if (beforeResult is LemonadeCodeResult.Success && afterResult is LemonadeCodeResult.Success) {
            PreviewLoad(before = keepIfSame(previous.before, beforeResult.value), after = keepIfSame(previous.after, afterResult.value))
          } else {
            val error = (beforeResult as? LemonadeCodeResult.Failure)?.error ?: (afterResult as LemonadeCodeResult.Failure).error
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
  previewChoices = emptyMap(),
  previewLayouts = emptyMap(),
  sessionMenu = null,
  basePickerOpen = false,
)

private fun WorkspaceViewState.closingPopovers(): WorkspaceViewState = copy(
  settingsOpen = false,
  subSessionMenu = null,
  sessionMenu = null,
  basePickerOpen = false,
  namingWorktree = null,
  worktreeBranch = null,
  takenBranch = null,
)

/** The log showing [packages]' lines; a new choice starts it over, so it's live and not cleared by hand. */
private fun DevicesViewState.showingLogsOf(packages: LogPackages): DevicesViewState = if (packages == logPackages) this else copy(logPackages = packages, pausedAt = null, logsCleared = false)

/** Where a pairing or connection leaves the panel: on the device it added, or back with what went wrong. */
private fun DevicesViewState.afterPairing(result: LemonadeCodeResult<MobileDevice, MobileError>): DevicesViewState = when (result) {
  is LemonadeCodeResult.Success -> copy(step = DevicesStep.Paired, paired = result.value.id, pairing = false, pairError = null, pairAddress = "", pairCode = "")
  is LemonadeCodeResult.Failure -> copy(pairing = false, pairError = result.error)
}

/** A path as a terminal pastes a dropped file: spaces and quotes escaped with a backslash. */
internal fun quotedPath(path: String): String = path.replace(Regex("([\\\\ '\"()&;])"), "\\\\$1")

/** The error lines handed to an agent: which device and log they're from, then the lines as shown. */
internal fun errorReport(device: String, source: String, errors: List<DeviceLogLine>): String = buildString {
  append("Errors from ").append(device)
  if (source.isNotEmpty()) append(" (").append(source).append(')')
  append(":\n")
  errors.forEach { line -> append(line.time).append(' ').append(line.level.letter).append(' ').append(line.tag).append(": ").append(line.message).append('\n') }
}

private val LogLevel.letter: String
  get() = when (this) {
    LogLevel.Verbose -> "V"
    LogLevel.Debug -> "D"
    LogLevel.Info -> "I"
    LogLevel.Warning -> "W"
    LogLevel.Error -> "E"
    LogLevel.Fatal -> "F"
  }

private fun DeviceKeyModel.toDomain(): DeviceKey = when (this) {
  DeviceKeyModel.Enter -> DeviceKey.Enter
  DeviceKeyModel.Backspace -> DeviceKey.Backspace
  DeviceKeyModel.ForwardDelete -> DeviceKey.ForwardDelete
  DeviceKeyModel.Tab -> DeviceKey.Tab
  DeviceKeyModel.Escape -> DeviceKey.Escape
  DeviceKeyModel.Up -> DeviceKey.Up
  DeviceKeyModel.Down -> DeviceKey.Down
  DeviceKeyModel.Left -> DeviceKey.Left
  DeviceKeyModel.Right -> DeviceKey.Right
}

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
