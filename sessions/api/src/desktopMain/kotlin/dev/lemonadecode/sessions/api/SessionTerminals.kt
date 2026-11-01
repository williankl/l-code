package dev.lemonadecode.sessions.api

import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.platform.terminal.TerminalSession

/** The live terminal behind each session, for the UI to draw and type into. */
public interface SessionTerminals {
  public fun terminal(id: SessionId): TerminalSession?
}
