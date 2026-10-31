package dev.loupe.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.teya.lemonade.core.LemonadeButtonSize
import com.teya.lemonade.core.LemonadeButtonType
import com.teya.lemonade.core.LemonadeButtonVariant
import com.teya.lemonade.core.LemonadeIcons
import com.teya.lemonade.core.LemonadeLinkSize
import com.teya.lemonade.core.LemonadeShadow
import com.teya.lemonade.lemonadeShadow
import dev.loupe.platform.designsystem.LoupeTheme
import dev.loupe.platform.designsystem.LoupeThemeMode
import dev.loupe.platform.localization.LoupeString
import dev.loupe.platform.localization.resolve
import dev.loupe.workspace.strings.Res
import dev.loupe.workspace.strings.action_dismiss
import dev.loupe.workspace.strings.action_new_terminal
import dev.loupe.workspace.strings.no_sessions_detail
import dev.loupe.workspace.strings.no_sessions_title
import dev.loupe.workspace.uimodel.ThemeOption
import dev.loupe.workspace.uimodel.WorkspaceContentModel
import dev.loupe.workspace.uimodel.WorkspaceEvent
import dev.loupe.workspace.uimodel.WorkspaceModel
import org.jetbrains.compose.resources.stringResource

/** Below this width the sessions sidebar turns into a drawer and toolbars wrap. */
public val NarrowLayoutBreakpoint: Dp = 960.dp

private val SidebarWidth = 232.dp
private val RailWidth = 60.dp
private val DrawerMaxWidth = 300.dp

/** Draws one session's terminal. The app passes the live terminal; previews pass a transcript. */
public typealias TerminalSlot = @Composable (sessionId: String, modifier: Modifier) -> Unit

public fun ThemeOption.toThemeMode(): LoupeThemeMode = when (this) {
  ThemeOption.Dark -> LoupeThemeMode.Dark
  ThemeOption.Light -> LoupeThemeMode.Light
  ThemeOption.System -> LoupeThemeMode.System
}

/**
 * The whole Loupe window: top bar, sessions column and the main panel with the terminal or the
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
    val drawerWidth = min(DrawerMaxWidth, maxWidth - 16.dp)
    val panel = model.sessions
    val sessionsVisible = if (narrow) panel.drawerOpen else panel.expanded

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
          horizontalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing200),
          modifier = Modifier
            .fillMaxSize()
            .padding(
              start = LemonadeTheme.spaces.spacing200,
              end = LemonadeTheme.spaces.spacing200,
              bottom = LemonadeTheme.spaces.spacing200,
            ),
        ) {
          if (!narrow) {
            if (panel.expanded) {
              SessionsSidebar(
                navigation = panel.navigation,
                onEvent = onEvent,
                modifier = Modifier.width(SidebarWidth).fillMaxHeight(),
              )
            } else {
              SessionRail(
                tiles = panel.tiles,
                onEvent = onEvent,
                modifier = Modifier.width(RailWidth).fillMaxHeight(),
              )
            }
          }
          MainPanel(
            model = model,
            terminal = terminal,
            onEvent = onEvent,
            modifier = Modifier.weight(1f).fillMaxHeight(),
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
      }
    }
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
      .background(LoupeTheme.scrim)
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = { onEvent(WorkspaceEvent.CloseDrawer) },
      ),
  )
  SessionsSidebar(
    navigation = model.sessions.navigation,
    onEvent = onEvent,
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
      is WorkspaceContentModel.Message -> MessageState(content = content, modifier = fill)
      is WorkspaceContentModel.Diff -> DiffPane(review = content.review, onEvent = onEvent, modifier = fill)
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
  message: LoupeString,
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
  key == Key.Escape && model.settings.open -> WorkspaceEvent.DismissSettings
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
