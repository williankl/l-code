package dev.loupe.workspace.impl

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import com.teya.lemonade.LemonadeTheme
import dev.loupe.domain.session.SessionId
import dev.loupe.platform.designsystem.LoupeTheme
import dev.loupe.platform.di.AppScope
import dev.loupe.platform.presenter.presenterAsState
import dev.loupe.platform.terminal.TerminalColors
import dev.loupe.platform.terminal.TerminalView
import dev.loupe.sessions.api.SessionTerminals
import dev.loupe.workspace.ui.WorkspaceContent
import dev.loupe.workspace.ui.toThemeMode
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject

/** The workspace window content: the presenter's Model drawn with live terminals. */
@Inject
public class WorkspaceScreen internal constructor(
  private val presenterProvider: () -> WorkspacePresenter,
  private val terminals: SessionTerminals,
) {
  /** [onBackground] gets the colour behind everything, for the window to paint while it resizes. */
  @Composable
  public fun Content(modifier: Modifier = Modifier, onBackground: (Color) -> Unit = {}) {
    val model by presenterAsState { presenterProvider() }
    LoupeTheme(mode = model.settings.theme.toThemeMode()) {
      val background = LemonadeTheme.colors.background.bgSubtle
      LaunchedEffect(background) { onBackground(background) }
      WorkspaceContent(
        model = model,
        terminal = { sessionId, slotModifier -> LiveTerminal(sessionId, slotModifier) },
        modifier = modifier,
      )
    }
  }

  @Composable
  private fun LiveTerminal(sessionId: String, modifier: Modifier) {
    val session = terminals.terminal(SessionId(sessionId)) ?: return
    val focusRequester = remember(sessionId) { FocusRequester() }
    TerminalView(
      session = session,
      colors = terminalColors(),
      textStyle = LoupeTheme.typography.code,
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
