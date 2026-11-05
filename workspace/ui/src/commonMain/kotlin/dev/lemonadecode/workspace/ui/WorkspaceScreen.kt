package dev.lemonadecode.workspace.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.draw.clipToBounds
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
import dev.lemonadecode.platform.designsystem.AnimatedPresence
import dev.lemonadecode.platform.designsystem.AnimationEdge
import dev.lemonadecode.platform.designsystem.LemonadeCodeAnimation
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
import dev.lemonadecode.workspace.uimodel.DevicePageEvent
import dev.lemonadecode.workspace.uimodel.DeviceWindowModel
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
            // The column glides between sidebar and rail as they cross-fade; the sidebar keeps its
            // full width inside and is cut off, rather than squeezed, as the column narrows.
            val columnWidth = glideOnChange(
              key = panel.expanded,
              target = if (panel.expanded) sidebarWidth + gap else RailWidth + gap,
            )
            val animate = LemonadeCodeTheme.animations
            AnimatedContent(
              targetState = panel.expanded,
              transitionSpec = {
                if (animate) {
                  (fadeIn(tween(LemonadeCodeAnimation.PanelMillis)) togetherWith fadeOut(tween(LemonadeCodeAnimation.FadeMillis))).using(null)
                } else {
                  (EnterTransition.None togetherWith ExitTransition.None).using(null)
                }
              },
              contentAlignment = Alignment.TopStart,
              label = "sessions column",
              modifier = Modifier.width(columnWidth).fillMaxHeight().clipToBounds(),
            ) { expanded ->
              Row(modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).fillMaxHeight()) {
                if (expanded) {
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
            }
          }
          MainPanel(
            model = model,
            terminal = terminal,
            narrowLayout = narrow,
            onEvent = onEvent,
            modifier = Modifier.weight(1f).fillMaxHeight(),
          )
          // Slides in from the window's edge as it widens, pushing the session over.
          AnimatedPresence(
            value = docked?.takeIf { !narrow },
            enter = LemonadeCodeAnimation.expandSideways(from = Alignment.Start),
            exit = LemonadeCodeAnimation.shrinkSideways(towards = Alignment.Start),
            modifier = Modifier.fillMaxHeight(),
          ) { devices ->
            // Glides as a device's screen and logs trade the narrow width for the wide one; a drag
            // or the window moves it at once.
            val devicesWidth = (draggedDevicesWidth ?: devices.width.dp).coerceIn(minDevicesWidth, maxDevicesWidth)
            val width = glideOnChange(key = devices.width, target = devicesWidth)
            Row(modifier = Modifier.fillMaxHeight()) {
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
                panel = devices,
                onEvent = onEvent,
                modifier = Modifier
                  .width(width)
                  .fillMaxHeight()
                  .clip(LemonadeTheme.shapes.semantic.radiusContainerDefault),
              )
            }
          }
        }
        // Below the breakpoint the panel floats over the session, at the right.
        AnimatedPresence(
          value = docked?.takeIf { narrow },
          enter = LemonadeCodeAnimation.slideIn(AnimationEdge.End),
          exit = LemonadeCodeAnimation.slideOut(AnimationEdge.End),
          modifier = Modifier.align(Alignment.TopEnd).padding(end = gap, bottom = gap).fillMaxHeight(),
        ) { devices ->
          val shape = LemonadeTheme.shapes.semantic.radiusContainerDefault
          // Glides between a device's narrow and wide layouts; follows the window at once.
          val width = glideOnChange(key = devices.width, target = min(devices.width.dp, windowWidth - gap * 2))
          DevicesPanel(
            panel = devices,
            onEvent = onEvent,
            modifier = Modifier
              .width(width)
              .fillMaxHeight()
              .lemonadeShadow(shadow = LemonadeShadow.Xlarge, shape = shape)
              .clip(shape),
          )
        }
        SessionsDrawer(visible = narrow && panel.drawerOpen, model = model, width = drawerWidth, onEvent = onEvent)
        AnimatedPresence(
          value = model.error,
          enter = LemonadeCodeAnimation.slideIn(AnimationEdge.Bottom),
          exit = LemonadeCodeAnimation.fadeOut(),
          modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = LemonadeTheme.spaces.spacing800),
        ) { message ->
          ErrorBanner(message = message, onDismiss = { onEvent(WorkspaceEvent.DismissError) })
        }
        // A problem report outranks a confirmation; the toast waits under it.
        AnimatedPresence(
          value = model.toast?.takeIf { model.error == null },
          enter = LemonadeCodeAnimation.slideIn(AnimationEdge.Bottom),
          exit = LemonadeCodeAnimation.fadeOut(),
          modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = LemonadeTheme.spaces.spacing800),
        ) { message ->
          LemonadeUi.Toast(label = message.resolve(), voice = ToastVoice.Success)
        }
      }
    }
    model.closeSession?.let { CloseSessionDialog(dialog = it, onEvent = onEvent) }
  }

  LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/** The scrim fades while the sessions slide in from the left over it. */
@Composable
private fun SessionsDrawer(
  visible: Boolean,
  model: WorkspaceModel,
  width: Dp,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  AnimatedVisibility(
    visible = visible,
    enter = LemonadeCodeAnimation.fadeIn(),
    exit = LemonadeCodeAnimation.fadeOut(),
    modifier = Modifier.fillMaxSize(),
  ) {
    SessionsDrawerContent(model = model, width = width, onEvent = onEvent)
  }
}

@Composable
private fun AnimatedVisibilityScope.SessionsDrawerContent(
  model: WorkspaceModel,
  width: Dp,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val shape = LemonadeTheme.shapes.semantic.radiusContainerDefault
  // A Box, so the sidebar keeps its own width inside the full-size layer.
  Box(modifier = Modifier.fillMaxSize()) {
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
        .animateEnterExit(enter = LemonadeCodeAnimation.slideIn(AnimationEdge.Start), exit = LemonadeCodeAnimation.slideOut(AnimationEdge.Start))
        .padding(start = LemonadeTheme.spaces.spacing200, bottom = LemonadeTheme.spaces.spacing200)
        .width(width)
        .fillMaxHeight()
        .lemonadeShadow(shadow = LemonadeShadow.Xlarge, shape = shape)
        .clip(shape)
        .background(LemonadeTheme.colors.background.bgDefault)
        .padding(LemonadeTheme.spaces.spacing200),
    )
  }
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
    // Another session, or Terminal and Diff trading places, cross-fade; the same view of the same
    // session updates in place.
    val animate = LemonadeCodeTheme.animations
    AnimatedContent(
      targetState = MainPanelView(sessionId = model.sessions.tiles.firstOrNull { it.selected }?.id, content = model.content),
      contentKey = { it.key },
      transitionSpec = {
        if (animate) {
          (fadeIn(tween(LemonadeCodeAnimation.FadeMillis, delayMillis = 40)) togetherWith fadeOut(tween(LemonadeCodeAnimation.PopoverOutMillis))).using(null)
        } else {
          (EnterTransition.None togetherWith ExitTransition.None).using(null)
        }
      },
      label = "main panel",
      modifier = Modifier.weight(1f).fillMaxWidth(),
    ) { view ->
      val fill = Modifier.fillMaxSize()
      when (val content = view.content) {
        is WorkspaceContentModel.Terminal -> terminal(content.sessionId, fill)
        is WorkspaceContentModel.Message -> Column(modifier = fill) {
          content.diffToolbar?.let { DiffToolbar(toolbar = it, onEvent = onEvent) }
          MessageState(content = content, modifier = Modifier.weight(1f).fillMaxWidth())
        }
        is WorkspaceContentModel.Diff -> DiffPane(review = content.review, narrowLayout = narrowLayout, onEvent = onEvent, modifier = fill)
        WorkspaceContentModel.NoSessions -> NoSessions(onEvent = onEvent, modifier = fill)
      }
    }
  }
}

/** What the main panel shows: [content], for the session [sessionId]. */
private data class MainPanelView(
  val sessionId: String?,
  val content: WorkspaceContentModel,
) {
  val key: String get() = "${content::class.simpleName}:$sessionId"
}

/**
 * [target], except just after [key] changes, like the sidebar collapsing: then it glides there
 * from where it was. Any other change, like dragging the sidebar's edge or resizing the window,
 * lands at once.
 */
@Composable
private fun glideOnChange(key: Any, target: Dp): Dp {
  val spec = LemonadeCodeAnimation.spec<Dp>()
  val animatable = remember { Animatable(target, Dp.VectorConverter) }
  val last = remember { arrayOf(key) }
  val gliding = remember { mutableStateOf(false) }
  if (last[0] != key) {
    last[0] = key
    gliding.value = true
  }
  LaunchedEffect(key, target) {
    if (gliding.value) {
      animatable.animateTo(target, spec)
      gliding.value = false
    } else {
      animatable.snapTo(target)
    }
  }
  return if (gliding.value) animatable.value else target
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

/**
 * One device mirrored in a window of its own: its screen and logs, as on its page in the Devices
 * panel. What happens on the page is said to be in this window, so it reaches this device.
 */
@Composable
public fun DeviceWindowContent(
  window: DeviceWindowModel,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val deviceId = window.deviceId
  val inWindow = remember(deviceId, onEvent) {
    { event: WorkspaceEvent -> onEvent(if (event is DevicePageEvent) WorkspaceEvent.InDeviceWindow(deviceId, event) else event) }
  }
  DevicesPanel(panel = window.panel, onEvent = inWindow, modifier = modifier.fillMaxSize())
}
