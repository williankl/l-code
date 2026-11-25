package dev.lemonadecode.imports.impl

import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.platform.process.HomeDirectory
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import java.io.File

/**
 * Folders on disk, and Claude Code's transcripts: Claude writes a conversation to
 * `~/.claude/projects/<folder>/<id>.jsonl` once something is said in it, which is what
 * `claude --resume <id>` opens in that folder. One without a transcript has nothing to resume.
 */
@ContributesBinding(AppScope::class)
@Inject
internal class DesktopImportEnvironment(home: HomeDirectory) : ImportEnvironment {
  private val projects = File(System.getenv("CLAUDE_CONFIG_DIR") ?: File(home.path, ".claude").path, "projects")

  override fun isFolder(path: String): Boolean = path.isNotEmpty() && File(path).isDirectory

  override fun hasConversation(conversation: String, folder: String): Boolean = claudeTranscript(projects, conversation, folder).isFile
}

/** Where Claude Code keeps [conversation] when it ran in [folder]: the folder's path, every character but letters and digits made a dash. */
internal fun claudeTranscript(projects: File, conversation: String, folder: String): File = File(File(projects, folder.replace(NotAlphanumeric, "-")), "${conversation.replace(NotIdCharacter, "")}.jsonl")

private val NotAlphanumeric = Regex("[^A-Za-z0-9]")
private val NotIdCharacter = Regex("[^A-Za-z0-9_-]")
