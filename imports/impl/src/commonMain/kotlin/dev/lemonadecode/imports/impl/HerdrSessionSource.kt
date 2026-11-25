package dev.lemonadecode.imports.impl

import dev.lemonadecode.domain.session.ImportSource
import dev.lemonadecode.domain.session.ImportableSession
import dev.lemonadecode.domain.session.SourceScan
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.core.safeSuspendRunCatching
import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.platform.process.HomeDirectory
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject

/** How Lemonade Code reaches herdr, read-only. An interface so tests hand it herdr's output. */
internal interface HerdrAccess {
  /** What `herdr session list --json` printed, or why it couldn't run. */
  suspend fun listSessions(): HerdrOutput

  /** The text of the file at [path]; null when there's no such file. */
  suspend fun readText(path: String): String?
}

internal sealed interface HerdrOutput {
  /** No `herdr` program on this computer. */
  data object NotInstalled : HerdrOutput

  data class Printed(val text: String) : HerdrOutput

  /** [message] is what herdr or the system said, if anything. */
  data class Failed(val message: String?) : HerdrOutput
}

/**
 * herdr's workspaces as sessions to import: herdr's own command lists its persistent sessions and
 * where each keeps its state, and each one's saved layout says what it has open. Nothing in herdr
 * is started, attached to or written.
 */
@ContributesIntoSet(AppScope::class)
@Inject
internal class HerdrSessionSource(
  private val herdr: HerdrAccess,
  private val home: HomeDirectory,
) : SessionSource {
  override val info: ImportSource = Info

  override suspend fun read(): SourceScan {
    val listed = when (val output = herdr.listSessions()) {
      HerdrOutput.NotInstalled -> return SourceScan.NotInstalled(info)
      is HerdrOutput.Failed -> return SourceScan.Failed(info, output.message)
      is HerdrOutput.Printed -> HerdrFormat.sessionList(output.text) ?: return SourceScan.UnknownFormat(info)
    }
    // A layout that can't be read leaves out its own herdr session, not the others.
    var read = 0
    var unknown = 0
    val failures = mutableListOf<Throwable>()
    val sessions = listed.flatMap { session ->
      val layout = when (val text = safeSuspendRunCatching { herdr.readText(joinPath(session.sessionDir, LayoutFile)) }) {
        is LemonadeCodeResult.Success -> text.value ?: return@flatMap emptyList()
        is LemonadeCodeResult.Failure -> {
          failures += text.error
          return@flatMap emptyList()
        }
      }
      when (val parsed = HerdrFormat.sessions(layout, session.name, home.path)) {
        is HerdrFormat.Layout.Read -> parsed.sessions.also { read++ }
        is HerdrFormat.Layout.Unknown -> emptyList<ImportableSession>().also { unknown++ }
      }
    }
    val skipped = unknown + failures.size
    return when {
      read > 0 || skipped == 0 -> SourceScan.Found(info, sessions, running = listed.any { it.running }, skipped = skipped)
      unknown > 0 -> SourceScan.UnknownFormat(info)
      else -> SourceScan.Failed(info, failures.first().message)
    }
  }

  private fun joinPath(folder: String, name: String): String {
    val separator = if ('\\' in folder && '/' !in folder) '\\' else '/'
    return folder.trimEnd('/', '\\') + separator + name
  }

  companion object {
    val Info: ImportSource = ImportSource(id = "herdr", name = "herdr")
    const val LayoutFile: String = "session.json"
  }
}
