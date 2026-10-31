package dev.loupe.settings.impl

import dev.loupe.domain.session.Session
import dev.loupe.domain.session.SessionId
import dev.loupe.domain.session.SessionOrigin
import dev.loupe.domain.session.SessionPlacement
import dev.loupe.domain.session.SessionStatus
import dev.loupe.domain.settings.AppSettings
import dev.loupe.platform.power.KeepAwake
import dev.loupe.sessions.fake.FakeSessionService
import dev.loupe.settings.fake.FakeSettingsService
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KeepAwakeCoordinatorTest {
  private val id = SessionId("a")
  private val sessions = FakeSessionService(
    listOf(
      Session(id, "a", "zsh", "/tmp", null, null, null, SessionPlacement.Root, SessionOrigin.Opened, SessionStatus.Idle),
    ),
  )
  private val settings = FakeSettingsService(AppSettings(keepAwake = true))
  private val keepAwake = RecordingKeepAwake()

  @Test
  fun `holds only while a session is working and the setting is on`() = runTest {
    backgroundScope.launch { KeepAwakeCoordinator(settings, sessions, keepAwake).run() }
    runCurrent()
    assertEquals(listOf(false), keepAwake.calls)

    sessions.setStatus(id, SessionStatus.Working)
    runCurrent()
    assertEquals(true, keepAwake.isHeld)

    settings.update { it.copy(keepAwake = false) }
    runCurrent()
    assertEquals(false, keepAwake.isHeld)
  }

  private class RecordingKeepAwake : KeepAwake {
    val calls = mutableListOf<Boolean>()
    override var isHeld: Boolean = false
      private set

    override fun setHeld(held: Boolean) {
      calls += held
      isHeld = held
    }
  }
}
