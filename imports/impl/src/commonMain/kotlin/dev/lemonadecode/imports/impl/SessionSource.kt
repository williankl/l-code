package dev.lemonadecode.imports.impl

import dev.lemonadecode.domain.session.ImportSource
import dev.lemonadecode.domain.session.SourceScan

/** A tool Lemonade Code can bring sessions over from, contributed into a set. */
internal interface SessionSource {
  val info: ImportSource

  /**
   * What the tool has, read without changing anything of it: [SourceScan.Found] with its sessions
   * as the tool keeps them, parents before their sub-sessions.
   */
  suspend fun read(): SourceScan
}

/** What importing needs to know about this computer. An interface so tests can answer for it. */
internal interface ImportEnvironment {
  fun isFolder(path: String): Boolean

  /** Claude Code still has [conversation]'s transcript for [folder], so `claude --resume` there picks it up. */
  fun hasConversation(conversation: String, folder: String): Boolean
}
