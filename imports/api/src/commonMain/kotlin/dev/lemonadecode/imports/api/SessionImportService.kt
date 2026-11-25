package dev.lemonadecode.imports.api

import dev.lemonadecode.domain.session.ImportResult
import dev.lemonadecode.domain.session.ImportSource
import dev.lemonadecode.domain.session.ImportableSession
import dev.lemonadecode.domain.session.SourceScan

/**
 * Brings sessions over from other tools that run coding agents in terminals, like herdr. Importing
 * copies: the other tool keeps its own sessions, and nothing of it is changed.
 */
public interface SessionImportService {
  /** The tools Lemonade Code can import from, in the order they're shown. */
  public val sources: List<ImportSource>

  /**
   * What each tool has now, marking the sessions already imported and those whose folder is gone. A
   * Claude Code conversation is only offered when Claude still has it to resume.
   */
  public suspend fun scan(): List<SourceScan>

  /**
   * Opens [sessions] as Lemonade Code sessions, each in its folder with its name, under its parent
   * when that comes along too or came before, resuming its Claude Code conversation. Each is
   * remembered, so it's never imported twice; one imported before, or blocked, is skipped.
   */
  public suspend fun import(sessions: List<ImportableSession>): ImportResult
}
