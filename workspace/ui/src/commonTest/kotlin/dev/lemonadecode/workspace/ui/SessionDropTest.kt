package dev.lemonadecode.workspace.ui

import dev.lemonadecode.platform.localization.asLemonadeCodeString
import dev.lemonadecode.workspace.uimodel.SessionStatus
import dev.lemonadecode.workspace.uimodel.SessionTreeRowModel
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionDropTest {
  /** api, with review-1 (and its own atomic) and review-2 under it, then checkout and scratch. */
  private val depths = listOf("api" to 0, "review-1" to 1, "atomic" to 2, "review-2" to 1, "checkout" to 0, "scratch" to 0)

  /** Rows 40 px tall, with a 4 px gap above every top-level one but the first. */
  private val places: List<RowPlace> = buildList {
    var y = 0f
    depths.forEachIndexed { index, (_, depth) ->
      if (depth == 0 && index > 0) y += 4f
      add(RowPlace(depth, y, y + 40f))
      y += 40f
    }
  }

  @Test
  fun `in its own place it stays where it is`() {
    assertEquals(1, dropIndex(places, dragged = 4, y = 184f))
    assertEquals(0, dropIndex(places, dragged = 1, y = 60f))
    assertEquals(1, dropIndex(places, dragged = 3, y = 140f))
  }

  @Test
  fun `it passes a sibling once its middle passes the middle of that sibling's rows, nested ones included`() {
    // api and what's under it run from 0 to 160: their middle is at 80.
    assertEquals(1, dropIndex(places, dragged = 4, y = 81f))
    assertEquals(0, dropIndex(places, dragged = 4, y = 79f))
    assertEquals(2, dropIndex(places, dragged = 4, y = 229f))
    assertEquals(0, dropIndex(places, dragged = 5, y = 10f))
  }

  @Test
  fun `only siblings count, however far it goes`() {
    // review-1 and its atomic run from 40 to 120; checkout and scratch aren't review-2's siblings.
    assertEquals(0, dropIndex(places, dragged = 3, y = 79f))
    assertEquals(1, dropIndex(places, dragged = 3, y = 1_000f))
    assertEquals(1, dropIndex(places, dragged = 1, y = 141f))
    assertEquals(0, dropIndex(places, dragged = 2, y = -500f))
  }

  @Test
  fun `the rows nested under a dragged one move with it`() {
    val rows = depths.map { (id, depth) ->
      SessionTreeRowModel(id, id, "zsh".asLemonadeCodeString(), SessionStatus.Shell, false, depth, emptyList(), null, null)
    }
    assertEquals(setOf("review-1", "atomic"), movingRows(rows, "review-1"))
    assertEquals(setOf("api", "review-1", "atomic", "review-2"), movingRows(rows, "api"))
    assertEquals(setOf("scratch"), movingRows(rows, "scratch"))
    assertEquals(emptySet(), movingRows(rows, null))
  }
}
