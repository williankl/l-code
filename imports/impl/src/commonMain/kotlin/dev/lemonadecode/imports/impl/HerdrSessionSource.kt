package dev.lemonadecode.imports.impl

import dev.lemonadecode.domain.session.ImportSource
import dev.lemonadecode.domain.session.SourceScan
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

  data class Failed(val message: String) : HerdrOutput
}

/**
 * herdr's workspaces as sessions to import: herdr's own command lists its persistent sessions and
 * where each keeps its state, and each one's saved layout says what it has open (see
 * [HerdrFormat]). Nothing in herdr is started, attached to or written.
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
      is HerdrOutput.Printed -> HerdrFormat.sessionList(output.text)
        ?: return SourceScan.UnknownFormat(info, "herdr session list --json printed something else")
    }
    val sessions = listed.flatMap { session ->
      val layout = herdr.readText(joinPath(session.sessionDir, LayoutFile)) ?: return@flatMap emptyList()
      when (val read = HerdrFormat.sessions(layout, session.name, home.path)) {
        is HerdrFormat.Layout.Read -> read.sessions
        is HerdrFormat.Layout.Unknown -> return SourceScan.UnknownFormat(info, read.detail)
      }
    }
    return SourceScan.Found(info, sessions, running = listed.any { it.running })
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
