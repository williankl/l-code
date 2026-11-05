package dev.lemonadecode.workspace.impl

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.teya.lemonade.LemonadeTheme
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.platform.designsystem.isDark
import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.platform.localization.ProvideAppLanguage
import dev.lemonadecode.platform.presenter.presenterAsState
import dev.lemonadecode.platform.terminal.TerminalColors
import dev.lemonadecode.platform.terminal.TerminalView
import dev.lemonadecode.platform.terminal.openInBrowser
import dev.lemonadecode.sessions.api.SessionTerminals
import dev.lemonadecode.workspace.ui.DeviceWindowContent
import dev.lemonadecode.workspace.ui.DevicesWindowContent
import dev.lemonadecode.workspace.ui.WorkspaceContent
import dev.lemonadecode.workspace.ui.toThemeMode
import dev.lemonadecode.workspace.uimodel.DevicesWindow
import dev.lemonadecode.workspace.uimodel.WorkspaceModel
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject

/** The workspace window content: the presenter's Model drawn with live terminals. */
@Inject
public class WorkspaceScreen internal constructor(
  private val presenterProvider: () -> WorkspacePresenter,
  private val terminals: SessionTerminals,
) {
  /** The workspace's Model, for the main window and the Devices window to share. Call it once. */
  @Composable
  public fun rememberModel(): State<WorkspaceModel> = presenterAsState { presenterProvider() }

  /**
   * [onBackground] gets the colour behind everything, for the window to paint while it resizes, and
   * [onDarkTheme] whether the app is drawn dark, for the window's title bar to match.
   */
  @Composable
  public fun Content(modifier: Modifier = Modifier, onBackground: (Color) -> Unit = {}, onDarkTheme: (Boolean) -> Unit = {}) {
    val model by rememberModel()
    Content(model = model, modifier = modifier, onBackground = onBackground, onDarkTheme = onDarkTheme)
  }

  @Composable
  public fun Content(
    model: WorkspaceModel,
    modifier: Modifier = Modifier,
    onBackground: (Color) -> Unit = {},
    onDarkTheme: (Boolean) -> Unit = {},
  ) {
    Themed(model, onBackground, onDarkTheme) {
      WorkspaceContent(
        model = model,
        terminal = { sessionId, slotModifier -> LiveTerminal(sessionId, slotModifier) },
        modifier = modifier,
      )
    }
  }

  /** The Devices panel for its own window; draws nothing while it's docked or closed. */
  @Composable
  public fun Devices(
    model: WorkspaceModel,
    modifier: Modifier = Modifier,
    onBackground: (Color) -> Unit = {},
    onDarkTheme: (Boolean) -> Unit = {},
  ) {
    val panel = model.devices?.takeIf { it.window == DevicesWindow.Separate } ?: return
    Themed(model, onBackground, onDarkTheme) {
      DevicesWindowContent(panel = panel, onEvent = model.eventSink, modifier = modifier)
    }
  }

  /** The device [deviceId] mirrored in a window of its own; draws nothing once that window closes. */
  @Composable
  public fun DeviceWindow(
    model: WorkspaceModel,
    deviceId: String,
    modifier: Modifier = Modifier,
    onBackground: (Color) -> Unit = {},
    onDarkTheme: (Boolean) -> Unit = {},
  ) {
    val window = model.deviceWindows.firstOrNull { it.deviceId == deviceId } ?: return
    Themed(model, onBackground, onDarkTheme) {
      DeviceWindowContent(window = window, onEvent = model.eventSink, modifier = modifier)
    }
  }

  /** The theme and language the settings pick, and links that open the way terminal links do. */
  @Composable
  private fun Themed(
    model: WorkspaceModel,
    onBackground: (Color) -> Unit,
    onDarkTheme: (Boolean) -> Unit,
    content: @Composable () -> Unit,
  ) {
    ProvideAppLanguage(model.settings.language.tag) {
      val mode = model.settings.theme.toThemeMode()
      val dark = mode.isDark()
      LaunchedEffect(dark) { onDarkTheme(dark) }
      LemonadeCodeTheme(mode = mode) {
        val background = LemonadeTheme.colors.background.bgSubtle
        LaunchedEffect(background) { onBackground(background) }
        // Links in the UI open the way terminal links do, which works on Linux desktops too.
        CompositionLocalProvider(LocalUriHandler provides BrowserUriHandler, content = content)
      }
    }
  }

  @Composable
  private fun LiveTerminal(sessionId: String, modifier: Modifier) {
    val session = terminals.terminal(SessionId(sessionId)) ?: return
    val focusRequester = remember(sessionId) { FocusRequester() }
    TerminalView(
      session = session,
      colors = terminalColors(),
      textStyle = LemonadeCodeTheme.typography.code,
      focusRequester = focusRequester,
      modifier = modifier.padding(
        start = LemonadeTheme.spaces.spacing600,
        end = LemonadeTheme.spaces.spacing600,
        top = LemonadeTheme.spaces.spacing400,
        bottom = LemonadeTheme.spaces.spacing400,
      ),
    )
    LaunchedEffect(sessionId) { focusRequester.requestFocus() }
  }
}

private object BrowserUriHandler : UriHandler {
  override fun openUri(uri: String) = openInBrowser(uri)
}

/** The 16 ANSI colours taken from Lemonade's themed hues, so program output matches the app. */
@Composable
private fun terminalColors(): TerminalColors {
  val content = LemonadeTheme.colors.content
  val background = LemonadeTheme.colors.background
  val themed = LemonadeTheme.themed
  val normal = listOf(
    content.contentTertiary,
    themed.red.content,
    themed.greenLime.content,
    themed.amber.content,
    themed.blue.content,
    themed.violet.content,
    themed.cyan.content,
    content.contentSecondary,
  )
  val bright = listOf(
    content.contentTertiary,
    themed.rose.content,
    themed.green.content,
    themed.yellow.content,
    themed.indigo.content,
    themed.fuchsia.content,
    themed.teal.content,
    content.contentPrimary,
  )
  return TerminalColors(
    foreground = content.contentPrimary,
    background = background.bgDefault,
    cursor = background.bgBrand,
    cursorText = content.contentOnBrandHigh,
    ansi = normal + bright,
    selection = themed.blue.content.copy(alpha = SelectionAlpha),
  )
}

private const val SelectionAlpha = 0.35f

@ContributesTo(AppScope::class)
public interface WorkspaceGraph {
  public val workspaceScreen: WorkspaceScreen
}
