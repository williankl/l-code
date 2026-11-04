package dev.lemonadecode.settings.impl

import dev.lemonadecode.domain.session.Session
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SessionOrigin
import dev.lemonadecode.domain.session.SessionPlacement
import dev.lemonadecode.domain.session.SessionStatus
import dev.lemonadecode.platform.sound.Chime
import dev.lemonadecode.platform.sound.testing.FakeSoundPlayer
import dev.lemonadecode.sessions.fake.FakeSessionService
import dev.lemonadecode.settings.fake.FakeSettingsService
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class StatusSoundsCoordinatorTest {
  private val a = SessionId("a")
  private val b = SessionId("b")
  private val sessions = FakeSessionService(listOf(session(a, SessionStatus.Working), session(b, SessionStatus.Working)))
  private val settings = FakeSettingsService()
  private val player = FakeSoundPlayer()

  @Test
  fun `an agent finishing its work plays one ding`() = runTest {
    start()

    change(a, SessionStatus.Idle)

    assertEquals(listOf(Chime.Ding), player.played)
  }

  @Test
  fun `an agent needing input plays a double ding, whatever it was doing before`() = runTest {
    start()

    change(a, SessionStatus.NeedsInput)
    change(b, SessionStatus.Idle)
    change(b, SessionStatus.NeedsInput)
    // Answered, it works again, then finishes.
    change(a, SessionStatus.Working)
    change(a, SessionStatus.Idle)

    assertEquals(listOf(Chime.DoubleDing, Chime.Ding, Chime.DoubleDing, Chime.Ding), player.played)
  }

  @Test
  fun `sessions seen for the first time play nothing, opened or restored`() = runTest {
    sessions.setSessions(listOf(session(a, SessionStatus.NeedsInput)))
    start()

    sessions.setSessions(sessions.sessions.value + session(b, SessionStatus.Idle))
    settle()
    sessions.saved = listOf(session(SessionId("c"), SessionStatus.NeedsInput))
    assertEquals(1, sessions.restore())
    settle()

    assertEquals(emptyList(), player.played)
  }

  @Test
  fun `plain shells, agents quitting and exited sessions play nothing`() = runTest {
    sessions.setSessions(listOf(session(a, SessionStatus.Shell), session(b, SessionStatus.Working)))
    start()

    // The agent starts and idles at its prompt, then quits back to the shell.
    change(a, SessionStatus.Idle)
    change(a, SessionStatus.Shell)
    change(b, SessionStatus.Exited)

    assertEquals(emptyList(), player.played)
  }

  @Test
  fun `nothing plays while status sounds are off`() = runTest {
    settings.update { it.copy(statusSounds = false) }
    start()

    change(a, SessionStatus.Idle)
    change(b, SessionStatus.NeedsInput)
    assertEquals(emptyList(), player.played)

    settings.update { it.copy(statusSounds = true) }
    change(a, SessionStatus.NeedsInput)
    assertEquals(listOf(Chime.DoubleDing), player.played)
  }

  @Test
  fun `changes close together play one sound, a double ding if any needs input`() = runTest {
    sessions.setSessions(listOf(session(a, SessionStatus.Working), session(b, SessionStatus.Working), session(SessionId("c"), SessionStatus.Working)))
    start()

    set(a, SessionStatus.Idle)
    advanceTimeBy(100.milliseconds)
    set(b, SessionStatus.NeedsInput)
    advanceTimeBy(100.milliseconds)
    set(SessionId("c"), SessionStatus.Idle)
    // Not yet: the sound waits for the burst to settle.
    assertEquals(emptyList(), player.played)
    settle()
    assertEquals(listOf(Chime.DoubleDing), player.played)

    // Two finishing together ding once.
    set(a, SessionStatus.Working)
    set(b, SessionStatus.Working)
    settle()
    set(a, SessionStatus.Idle)
    set(b, SessionStatus.Idle)
    settle()
    assertEquals(listOf(Chime.DoubleDing, Chime.Ding), player.played)
  }

  @Test
  fun `a session's own changes elsewhere, like its folder, play nothing`() = runTest {
    sessions.setSessions(listOf(session(a, SessionStatus.Idle)))
    start()

    sessions.setSessions(listOf(session(a, SessionStatus.Idle).copy(workingDirectory = "/tmp/elsewhere", branch = "main")))
    settle()

    assertEquals(emptyList(), player.played)
  }

  private fun TestScope.start() {
    backgroundScope.launch { StatusSoundsCoordinator(settings, sessions, player).run() }
    runCurrent()
  }

  /** Sets [id]'s status, and lets the sound for it play. */
  private fun TestScope.change(id: SessionId, status: SessionStatus) {
    set(id, status)
    settle()
  }

  private fun TestScope.set(id: SessionId, status: SessionStatus) {
    sessions.setStatus(id, status)
    runCurrent()
  }

  private fun TestScope.settle() {
    advanceTimeBy(StatusSoundsCoordinator.CoalesceWindow)
    runCurrent()
  }

  private fun session(id: SessionId, status: SessionStatus) = Session(id, id.value, "zsh", "/tmp", null, null, null, SessionPlacement.Root, SessionOrigin.Opened, status)
}
