package dev.loupe.sessions.api

import dev.loupe.domain.session.SessionId
import dev.loupe.platform.terminal.TerminalSession

/** The live terminal behind each session, for the UI to draw and type into. */
public interface SessionTerminals {
  public fun terminal(id: SessionId): TerminalSession?
}
