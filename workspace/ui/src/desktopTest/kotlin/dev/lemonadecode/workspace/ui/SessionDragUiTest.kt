package dev.lemonadecode.workspace.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.platform.localization.asLemonadeCodeString
import dev.lemonadecode.workspace.uimodel.SessionNavigationModel
import dev.lemonadecode.workspace.uimodel.SessionStatus
import dev.lemonadecode.workspace.uimodel.SessionTreeRowModel
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A row picked up follows the pointer, and once let go glides back into its row's place; with
 * animations off it's there at once. The clock is stepped by hand to look just after letting go.
 */
@OptIn(ExperimentalTestApi::class)
class SessionDragUiTest {
  private val rows = listOf("api" to 0, "review" to 1, "checkout" to 0).map { (id, depth) ->
    SessionTreeRowModel(id, id, "zsh".asLemonadeCodeString(), SessionStatus.Shell, false, depth, emptyList(), null, null)
  }

  @Test
  fun `a dropped row glides into place with animations on`() = dropping(animations = true)

  @Test
  fun `a dropped row is in place at once with animations off`() = dropping(animations = false)

  private fun dropping(animations: Boolean) = runDesktopComposeUiTest(width = 300, height = 400) {
    val events = mutableListOf<WorkspaceEvent>()
    lateinit var drag: SessionDragState
    setContent {
      LemonadeCodeTheme(animations = animations) {
        drag = rememberSessionDragState()
        SessionTreeDragArea(tree = SessionNavigationModel.Tree(rows), state = drag) {
          Column {
            rows.forEach { row ->
              BasicText(row.name, Modifier.fillMaxWidth().height(40.dp).sessionDragSource(row.id, drag) { events += it })
            }
          }
        }
      }
    }
    waitForIdle()

    // Up past api and the review under it: before them both.
    onNodeWithText("checkout").performMouseInput {
      moveTo(center)
      press()
      moveBy(Offset(0f, -100f), delayMillis = 50)
    }
    waitForIdle()
    assertEquals("checkout", drag.draggedId)
    assertEquals(-20f, drag.lifted("checkout")?.top, "it goes no further than halfway past the top of the tree")

    mainClock.autoAdvance = false
    onNodeWithText("checkout").performMouseInput { release() }
    mainClock.advanceTimeBy(32)
    assertEquals(if (animations) "checkout" else null, drag.draggedId, "just after letting go")
    mainClock.advanceTimeBy(500)
    assertEquals(null, drag.draggedId, "once it's in place")
    assertEquals(
      listOf(WorkspaceEvent.StartSessionDrag("checkout"), WorkspaceEvent.DragSession("checkout", 0), WorkspaceEvent.DropSession("checkout")),
      events,
    )
  }
}
