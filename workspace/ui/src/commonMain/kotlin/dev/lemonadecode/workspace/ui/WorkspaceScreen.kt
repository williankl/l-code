package dev.lemonadecode.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.teya.lemonade.Button
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.Link
import com.teya.lemonade.Text
import com.teya.lemonade.Toast
import com.teya.lemonade.ToastVoice
import com.teya.lemonade.core.LemonadeButtonSize
import com.teya.lemonade.core.LemonadeButtonType
import com.teya.lemonade.core.LemonadeButtonVariant
import com.teya.lemonade.core.LemonadeIcons
import com.teya.lemonade.core.LemonadeLinkSize
import com.teya.lemonade.core.LemonadeShadow
import com.teya.lemonade.lemonadeShadow
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.platform.designsystem.LemonadeCodeThemeMode
import dev.lemonadecode.platform.localization.LemonadeCodeString
import dev.lemonadecode.platform.localization.resolve
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.action_dismiss
import dev.lemonadecode.workspace.strings.action_new_terminal
import dev.lemonadecode.workspace.strings.devices_resize
import dev.lemonadecode.workspace.strings.no_sessions_detail
import dev.lemonadecode.workspace.strings.no_sessions_title
import dev.lemonadecode.workspace.uimodel.DevicesPanelModel
import dev.lemonadecode.workspace.uimodel.DevicesWindow
import dev.lemonadecode.workspace.uimodel.SessionsPanelModel
import dev.lemonadecode.workspace.uimodel.ThemeOption
import dev.lemonadecode.workspace.uimodel.WorkspaceContentModel
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import dev.lemonadecode.workspace.uimodel.WorkspaceModel
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** Below this width the sessions sidebar turns into a drawer and toolbars wrap. */
public val NarrowLayoutBreakpoint: Dp = 960.dp

private val RailWidth = 60.dp
private val DrawerMaxWidth = 300.dp

/** The sidebar can't be dragged so wide that the main panel gets narrower than this. */
private val MainPanelMinWidth = 480.dp

/** Draws one session's terminal. The app passes the live terminal; previews pass a transcript. */
public typealias TerminalSlot = @Composable (sessionId: String, modifier: Modifier) -> Unit

public fun ThemeOption.toThemeMode(): LemonadeCodeThemeMode = when (this) {
  ThemeOption.Dark -> LemonadeCodeThemeMode.Dark
  ThemeOption.Light -> LemonadeCodeThemeMode.Light
  ThemeOption.System -> LemonadeCodeThemeMode.System
}

/**
 * The whole Lemonade Code window: top bar, sessions column and the main panel with the terminal or the
 * diff review. Pure rendering: it draws [model] and reports intents through its `eventSink`.
 */
@Composable
public fun WorkspaceContent(
  model: WorkspaceModel,
  terminal: TerminalSlot,
  modifier: Modifier = Modifier,
) {
  val currentModel by rememberUpdatedState(model)
  val onEvent = model.eventSink
  val focusRequester = remember { FocusRequester() }

  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .background(LemonadeTheme.colors.background.bgSubtle)
      .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val shortcut = shortcutFor(
          model = currentModel,
          key = event.key,
          command = event.isMetaPressed || event.isCtrlPressed,
        )
        shortcut?.let(currentModel.eventSink)
        shortcut != null
      }
      .focusRequester(focusRequester)
      .focusable(),
  ) {
    val narrow = maxWidth < NarrowLayoutBreakpoint
    val windowWidth = maxWidth
    val drawerWidth = min(DrawerMaxWidth, maxWidth - 16.dp)
    val panel = model.sessions
    val sessionsVisible = if (narrow) panel.drawerOpen else panel.expanded
    val gap = LemonadeTheme.spaces.spacing200
    val minSidebarWidth = SessionsPanelModel.MinSidebarWidth.dp
    val maxSidebarWidth = min(SessionsPanelModel.MaxSidebarWidth.dp, maxWidth - gap * 3 - MainPanelMinWidth)
      .coerceAtLeast(minSidebarWidth)
    // While dragging, and after until the saved width comes back, the edge follows the pointer.
    var draggedWidth by remember(panel.sidebarWidth) { mutableStateOf<Dp?>(null) }
    val sidebarWidth = (draggedWidth ?: panel.sidebarWidth.dp).coerceIn(minSidebarWidth, maxSidebarWidth)
    val docked = model.devices?.takeIf { it.window == DevicesWindow.Docked }
    // The Devices panel can't be dragged so wide that the main panel gets narrower than its least.
    val besideDevices = if (panel.expanded) sidebarWidth else RailWidth
    val minDevicesWidth = DevicesPanelModel.MinWidth.dp
    val maxDevicesWidth = min(DevicesPanelModel.MaxWidth.dp, maxWidth - besideDevices - gap * 3 - MainPanelMinWidth)
      .coerceAtLeast(minDevicesWidth)
    var draggedDevicesWidth by remember(docked?.width) { mutableStateOf<Dp?>(null) }
    val devicesWidth = (draggedDevicesWidth ?: (docked?.width ?: DevicesPanelModel.DefaultWidth).dp).coerceIn(minDevicesWidth, maxDevicesWidth)

    Column(modifier = Modifier.fillMaxSize()) {
      TopBar(
        topBar = model.topBar,
        settings = model.settings,
        sessionsVisible = sessionsVisible,
        narrowLayout = narrow,
        showAttentionDot = narrow && !panel.drawerOpen && model.topBar.anySessionNeedsInput,
        onEvent = onEvent,
      )
      Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        Row(
          modifier = Modifier
            .fillMaxSize()
            .padding(start = gap, end = gap, bottom = gap),
        ) {
          if (!narrow) {
            if (panel.expanded) {
              SessionsSidebar(
                navigation = panel.navigation,
                onEvent = onEvent,
                modifier = Modifier.width(sidebarWidth).fillMaxHeight(),
                menu = panel.menu,
              )
              SidebarResizeHandle(
                sidebarWidth = sidebarWidth,
                minWidth = minSidebarWidth,
                maxWidth = maxSidebarWidth,
                onResize = { draggedWidth = it },
                onResizeFinished = { onEvent(WorkspaceEvent.ResizeSessionsSidebar(it.value.roundToInt())) },
                modifier = Modifier.width(gap),
              )
            } else {
              SessionRail(
                tiles = panel.tiles,
                onEvent = onEvent,
                modifier = Modifier.width(RailWidth).fillMaxHeight(),
                menu = panel.menu,
              )
              Spacer(modifier = Modifier.width(gap))
            }
          }
          MainPanel(
            model = model,
            terminal = terminal,
            narrowLayout = narrow,
            onEvent = onEvent,
            modifier = Modifier.weight(1f).fillMaxHeight(),
          )
          if (docked != null && !narrow) {
            SidebarResizeHandle(
              sidebarWidth = devicesWidth,
              minWidth = minDevicesWidth,
              maxWidth = maxDevicesWidth,
              onResize = { draggedDevicesWidth = it },
              onResizeFinished = { onEvent(WorkspaceEvent.ResizeDevicesPanel(it.value.roundToInt())) },
              atEnd = true,
              label = stringResource(Res.string.devices_resize),
              modifier = Modifier.width(gap),
            )
            DevicesPanel(
              panel = docked,
              onEvent = onEvent,
              modifier = Modifier
                .width(devicesWidth)
                .fillMaxHeight()
                .clip(LemonadeTheme.shapes.semantic.radiusContainerDefault),
            )
          }
        }
        // Below the breakpoint the panel floats over the session, at the right.
        model.devices?.takeIf { it.window == DevicesWindow.Docked && narrow }?.let { docked ->
          val shape = LemonadeTheme.shapes.semantic.radiusContainerDefault
          DevicesPanel(
            panel = docked,
            onEvent = onEvent,
            modifier = Modifier
              .align(Alignment.TopEnd)
              .padding(end = gap, bottom = gap)
              .width(min(docked.width.dp, windowWidth - gap * 2))
              .fillMaxHeight()
              .lemonadeShadow(shadow = LemonadeShadow.Xlarge, shape = shape)
              .clip(shape),
          )
        }
        if (narrow && panel.drawerOpen) {
          SessionsDrawer(model = model, width = drawerWidth, onEvent = onEvent)
        }
        model.error?.let { message ->
          ErrorBanner(
            message = message,
            onDismiss = { onEvent(WorkspaceEvent.DismissError) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = LemonadeTheme.spaces.spacing800),
          )
        }
        // A problem report outranks a confirmation; the toast waits under it.
        if (model.error == null) {
          model.toast?.let { message ->
            LemonadeUi.Toast(
              label = message.resolve(),
              voice = ToastVoice.Success,
              modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = LemonadeTheme.spaces.spacing800),
            )
          }
        }
      }
    }
    model.closeSession?.let { CloseSessionDialog(dialog = it, onEvent = onEvent) }
  }

  LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun SessionsDrawer(
  model: WorkspaceModel,
  width: Dp,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val shape = LemonadeTheme.shapes.semantic.radiusContainerDefault
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(LemonadeCodeTheme.scrim)
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = { onEvent(WorkspaceEvent.CloseDrawer) },
      ),
  )
  SessionsSidebar(
    navigation = model.sessions.navigation,
    onEvent = onEvent,
    menu = model.sessions.menu,
    modifier = Modifier
      .padding(start = LemonadeTheme.spaces.spacing200, bottom = LemonadeTheme.spaces.spacing200)
      .width(width)
      .fillMaxHeight()
      .lemonadeShadow(shadow = LemonadeShadow.Xlarge, shape = shape)
      .clip(shape)
      .background(LemonadeTheme.colors.background.bgDefault)
      .padding(LemonadeTheme.spaces.spacing200),
  )
}

@Composable
private fun MainPanel(
  model: WorkspaceModel,
  terminal: TerminalSlot,
  narrowLayout: Boolean,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .clip(LemonadeTheme.shapes.semantic.radiusContainerDefault)
      .background(LemonadeTheme.colors.background.bgDefault),
  ) {
    val header = model.header
    val switcher = model.viewSwitcher
    if (header != null && switcher != null) {
      SessionHeaderBar(
        header = header,
        viewSwitcher = switcher,
        newSubSession = model.newSubSession,
        onEvent = onEvent,
      )
    }
    val fill = Modifier.weight(1f).fillMaxWidth()
    when (val content = model.content) {
      is WorkspaceContentModel.Terminal -> terminal(content.sessionId, fill)
      is WorkspaceContentModel.Message -> {
        content.diffToolbar?.let { DiffToolbar(toolbar = it, onEvent = onEvent) }
        MessageState(content = content, modifier = fill)
      }
      is WorkspaceContentModel.Diff -> DiffPane(review = content.review, narrowLayout = narrowLayout, onEvent = onEvent, modifier = fill)
      WorkspaceContentModel.NoSessions -> NoSessions(onEvent = onEvent, modifier = fill)
    }
  }
}

@Composable
private fun NoSessions(
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing400, Alignment.CenterVertically),
    modifier = modifier.padding(LemonadeTheme.spaces.spacing800),
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing100),
    ) {
      LemonadeUi.Text(text = stringResource(Res.string.no_sessions_title), textStyle = LemonadeTheme.typography.headingXSmall)
      LemonadeUi.Text(
        text = stringResource(Res.string.no_sessions_detail),
        textStyle = LemonadeTheme.typography.bodySmallRegular,
        color = LemonadeTheme.colors.content.contentSecondary,
      )
    }
    LemonadeUi.Button(
      label = stringResource(Res.string.action_new_terminal),
      onClick = { onEvent(WorkspaceEvent.NewSession) },
      leadingIcon = LemonadeIcons.Plus,
      variant = LemonadeButtonVariant.Primary,
      type = LemonadeButtonType.Solid,
      size = LemonadeButtonSize.Small,
    )
  }
}

@Composable
private fun ErrorBanner(
  message: LemonadeCodeString,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val shape = LemonadeTheme.shapes.radiusFull
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing300),
    modifier = modifier
      .widthIn(max = 640.dp)
      .lemonadeShadow(shadow = LemonadeShadow.Large, shape = shape)
      .background(LemonadeTheme.colors.background.bgDefaultInverse, shape)
      .padding(horizontal = LemonadeTheme.spaces.spacing400, vertical = LemonadeTheme.spaces.spacing200),
  ) {
    LemonadeUi.Text(
      text = message.resolve(),
      textStyle = LemonadeTheme.typography.bodySmallMedium,
      color = LemonadeTheme.colors.content.contentPrimaryInverse,
      modifier = Modifier.weight(1f, fill = false),
    )
    LemonadeUi.Link(text = stringResource(Res.string.action_dismiss), onClick = onDismiss, size = LemonadeLinkSize.Small)
  }
}

private fun shortcutFor(model: WorkspaceModel, key: Key, command: Boolean): WorkspaceEvent? = when {
  key == Key.Escape && model.sessions.menu?.rename != null -> WorkspaceEvent.CancelRenamingSession
  key == Key.Escape && model.sessions.menu != null -> WorkspaceEvent.DismissSessionMenu
  key == Key.Escape && model.settings.open -> WorkspaceEvent.SettingsBack
  key == Key.Escape && model.newSubSession.open -> WorkspaceEvent.DismissNewSubSessionMenu
  key == Key.Escape && model.sessions.drawerOpen -> WorkspaceEvent.CloseDrawer
  command && key == Key.Enter -> {
    val diff = model.content as? WorkspaceContentModel.Diff
    if (diff?.review?.sendBar?.canSend == true) WorkspaceEvent.SendToSession else null
  }
  command && key == Key.T -> WorkspaceEvent.NewSession
  command && key == Key.W && model.header != null -> WorkspaceEvent.CloseSelectedSession
  else -> null
}

/**
 * The Devices panel in a window of its own, for another display. Closing that window closes the
 * panel; the ↙ button puts it back beside the session.
 */
@Composable
public fun DevicesWindowContent(
  panel: DevicesPanelModel,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  DevicesPanel(panel = panel, onEvent = onEvent, modifier = modifier.fillMaxSize())
}
