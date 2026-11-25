package dev.lemonadecode.imports.impl

import dev.lemonadecode.domain.session.ClaudeConversation
import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.platform.process.HomeDirectory
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import java.io.File

/** Folders on disk, and the transcripts in Claude Code's own folder. */
@ContributesBinding(AppScope::class)
@Inject
internal class DesktopImportEnvironment(home: HomeDirectory) : ImportEnvironment {
  private val claude = File(ClaudeConversation.configFolder(home.path, System.getenv("CLAUDE_CONFIG_DIR")))

  override fun isFolder(path: String): Boolean = path.isNotEmpty() && File(path).isDirectory

  override fun hasConversation(conversation: String, folder: String): Boolean = ClaudeConversation.isId(conversation) && File(claude, ClaudeConversation.transcript(conversation, folder)).isFile
}
