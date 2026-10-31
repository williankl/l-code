package dev.loupe.sessions.impl

import dev.loupe.domain.session.SessionId
import dev.loupe.platform.di.AppScope
import dev.loupe.platform.terminal.TerminalSession
import dev.loupe.sessions.api.SessionTerminals
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.ConcurrentHashMap

/** The terminals behind the running sessions, written by the service and read by the UI. */
@SingleIn(AppScope::class)
@Inject
internal class SessionRegistry {
  private val terminals = ConcurrentHashMap<SessionId, TerminalSession>()

  fun terminal(id: SessionId): TerminalSession? = terminals[id]

  fun register(id: SessionId, terminal: TerminalSession) {
    terminals[id] = terminal
  }

  @IgnorableReturnValue
  fun remove(id: SessionId): TerminalSession? = terminals.remove(id)
}

@ContributesBinding(AppScope::class)
@Inject
internal class RegistrySessionTerminals(
  private val registry: SessionRegistry,
) : SessionTerminals {
  override fun terminal(id: SessionId): TerminalSession? = registry.terminal(id)
}
