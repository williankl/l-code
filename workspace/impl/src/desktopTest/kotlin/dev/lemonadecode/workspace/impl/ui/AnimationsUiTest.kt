package dev.lemonadecode.workspace.impl.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.workspace.impl.showcase.WorkspaceShowcase
import dev.lemonadecode.workspace.ui.TerminalTranscript
import dev.lemonadecode.workspace.ui.WorkspaceContent
import dev.lemonadecode.workspace.uimodel.SessionNavigationModel
import dev.lemonadecode.workspace.uimodel.WorkspaceModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * With animations on, what closes stays a moment as it fades; with them off it's gone at once.
 * The clock is stepped by hand to look halfway through.
 */
@OptIn(ExperimentalTestApi::class)
class AnimationsUiTest {
  @Test
  fun `a closed settings popover fades out with animations on`() = closing(
    showcase = WorkspaceShowcase.SettingsList,
    text = "Keep awake while sessions work",
    animations = true,
    close = { it.copy(settings = it.settings.copy(open = false)) },
  )

  @Test
  fun `a closed settings popover is gone at once with animations off`() = closing(
    showcase = WorkspaceShowcase.SettingsList,
    text = "Keep awake while sessions work",
    animations = false,
    close = { it.copy(settings = it.settings.copy(open = false)) },
  )

  @Test
  fun `a closed session's row closes up with animations on`() = closing(
    showcase = WorkspaceShowcase.AgentFinishes,
    text = "checkout-flake",
    animations = true,
    close = { it.withoutSession("cf") },
  )

  @Test
  fun `a closed session's row is gone at once with animations off`() = closing(
    showcase = WorkspaceShowcase.AgentFinishes,
    text = "checkout-flake",
    animations = false,
    close = { it.withoutSession("cf") },
  )

  /** Shows [showcase], applies [close], and checks [text] is still there 50 ms later only with [animations]. */
  private fun closing(
    showcase: WorkspaceShowcase,
    text: String,
    animations: Boolean,
    close: (WorkspaceModel) -> WorkspaceModel,
  ) = runDesktopComposeUiTest(width = showcase.widthDp, height = showcase.heightDp) {
    var closed by mutableStateOf(false)
    setContent {
      LemonadeCodeTheme(animations = animations) {
        val model = showcase.model {}
        WorkspaceContent(
          model = if (closed) close(model) else model,
          terminal = { id, modifier -> TerminalTranscript(showcase.transcript(id), modifier) },
        )
      }
    }
    waitForIdle()
    assertEquals(1, count(text), "$text should show before it closes")

    mainClock.autoAdvance = false
    runOnUiThread { closed = true }
    mainClock.advanceTimeBy(50)
    assertEquals(if (animations) 1 else 0, count(text), "50 ms after closing")

    mainClock.advanceTimeBy(500)
    assertEquals(0, count(text), "once it has closed")
  }

  private fun DesktopComposeUiTest.count(text: String): Int = onAllNodesWithText(text).fetchSemanticsNodes().size

  private fun WorkspaceModel.withoutSession(id: String): WorkspaceModel = copy(
    sessions = sessions.copy(
      navigation = when (val navigation = sessions.navigation) {
        is SessionNavigationModel.Flat -> navigation.copy(rows = navigation.rows.filterNot { it.id == id })
        is SessionNavigationModel.Tree -> navigation.copy(rows = navigation.rows.filterNot { it.id == id })
      },
      tiles = sessions.tiles.filterNot { it.id == id },
    ),
  )
}
