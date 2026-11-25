package dev.lemonadecode.imports.impl

import dev.lemonadecode.domain.session.ImportSource
import dev.lemonadecode.domain.session.SourceScan

/**
 * A tool Lemonade Code can bring sessions over from. Each one is contributed into a set, so another
 * tool is another implementation; herdr is the first.
 */
internal interface SessionSource {
  val info: ImportSource

  /**
   * What the tool has, read without changing anything of it: [SourceScan.Found] with its sessions
   * as the tool keeps them, parents before their sub-sessions. Whether each can be imported is
   * decided after, by [SessionImportServiceImpl].
   */
  suspend fun read(): SourceScan
}

/** What importing needs to know about this computer. An interface so tests can answer for it. */
internal interface ImportEnvironment {
  fun isFolder(path: String): Boolean

  /** Claude Code still has [conversation]'s transcript for [folder], so `claude --resume` there picks it up. */
  fun hasConversation(conversation: String, folder: String): Boolean
}
