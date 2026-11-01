package dev.lemonadecode.settings.impl

import dev.lemonadecode.domain.session.Session
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SessionOrigin
import dev.lemonadecode.domain.session.SessionPlacement
import dev.lemonadecode.domain.session.SessionStatus
import dev.lemonadecode.domain.settings.AppSettings
import dev.lemonadecode.platform.power.KeepAwake
import dev.lemonadecode.sessions.fake.FakeSessionService
import dev.lemonadecode.settings.fake.FakeSettingsService
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
