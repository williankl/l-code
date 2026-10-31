package dev.loupe.sessions.impl

import dev.loupe.domain.session.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentStatusTrackerTest {
  private val tracker = AgentStatusTracker()

  @Test
  fun `a plain shell has no status`() {
    assertEquals(SessionStatus.Shell, tracker.status)
    assertFalse(tracker.hasReports)
  }

  @Test
  fun `the agent's own state is shown`() {
    assertTrue(tracker.onReport(id = null, state = "working"))
    assertEquals(SessionStatus.Working, tracker.status)
    tracker.onReport(id = null, state = "idle")
    assertEquals(SessionStatus.Idle, tracker.status)
    tracker.onReport(id = null, state = "blocked")
    assertEquals(SessionStatus.NeedsInput, tracker.status)
    tracker.onReport(id = null, state = "done")
    assertEquals(SessionStatus.Idle, tracker.status)
  }

  @Test
  fun `a background agent keeps the session working while the main one is idle`() {
    tracker.onReport(id = null, state = "idle")
    tracker.onReport(id = "a1", state = "working")
    assertEquals(SessionStatus.Working, tracker.status)
    tracker.onReport(id = "a1", state = "clear")
    assertEquals(SessionStatus.Idle, tracker.status)
  }

  @Test
  fun `anything waiting on you wins over work`() {
    tracker.onReport(id = null, state = "working")
    tracker.onReport(id = "a1", state = "blocked")
    assertEquals(SessionStatus.NeedsInput, tracker.status)
    tracker.onReport(id = null, state = "error")
    tracker.onReport(id = "a1", state = "clear")
    assertEquals(SessionStatus.NeedsInput, tracker.status)
  }

  @Test
  fun `the agent quitting clears everything`() {
    tracker.onReport(id = null, state = "working")
    tracker.onReport(id = "a1", state = "working")
    tracker.onReport(id = null, state = "clear")
    assertEquals(SessionStatus.Shell, tracker.status)
  }

  @Test
  fun `reports left by an agent that died are dropped`() {
    tracker.onReport(id = null, state = "working")
    assertTrue(tracker.onProgramGone())
    assertEquals(SessionStatus.Shell, tracker.status)
    assertFalse(tracker.onProgramGone())
  }

  @Test
  fun `the shell ending is final`() {
    tracker.onReport(id = null, state = "working")
    tracker.onExited()
    tracker.onReport(id = null, state = "working")
    assertEquals(SessionStatus.Exited, tracker.status)
  }
}
