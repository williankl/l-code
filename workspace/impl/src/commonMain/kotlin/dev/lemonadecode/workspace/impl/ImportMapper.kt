package dev.lemonadecode.workspace.impl

import dev.lemonadecode.domain.session.ImportBlock
import dev.lemonadecode.domain.session.ImportableSession
import dev.lemonadecode.domain.session.SourceScan
import dev.lemonadecode.platform.localization.LemonadeCodeString
import dev.lemonadecode.platform.localization.asLemonadeCodeString
import dev.lemonadecode.platform.process.abbreviateHome
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.import_already_imported
import dev.lemonadecode.workspace.strings.import_folder_missing
import dev.lemonadecode.workspace.strings.import_found
import dev.lemonadecode.workspace.strings.import_none
import dev.lemonadecode.workspace.strings.import_not_installed
import dev.lemonadecode.workspace.strings.import_note_conversation
import dev.lemonadecode.workspace.strings.import_note_worktree
import dev.lemonadecode.workspace.strings.import_note_worktree_conversation
import dev.lemonadecode.workspace.strings.import_read_failed
import dev.lemonadecode.workspace.strings.import_running
import dev.lemonadecode.workspace.strings.import_skipped
import dev.lemonadecode.workspace.strings.import_unknown_format
import dev.lemonadecode.workspace.uimodel.ImportRowModel
import dev.lemonadecode.workspace.uimodel.ImportSessionsModel
import dev.lemonadecode.workspace.uimodel.ImportSourceModel

/** Settings › Import sessions, from what the tools have and what's ticked. */
internal object ImportMapper {
  fun model(sourceNames: List<String>, state: ImportViewState?, home: String): ImportSessionsModel {
    val names = sourceNames.joinToString(", ")
    val scans = state?.scans ?: return ImportSessionsModel(sourceNames = names, loading = state != null, importing = state?.importing == true)
    return ImportSessionsModel(
      sourceNames = names,
      sources = scans.map { source(it, state.ticked, home) },
      tickedCount = state.picked.size,
      importing = state.importing,
    )
  }

  private fun source(scan: SourceScan, ticked: Set<String>, home: String): ImportSourceModel {
    val id = scan.source.id
    val name = scan.source.name
    return when (scan) {
      is SourceScan.Found -> {
        val listed = scan.sessions.map { it.key }.toSet()
        val importable = scan.sessions.filter { it.blocked == null }
        ImportSourceModel(
          id = id,
          name = name,
          status = if (scan.sessions.isEmpty()) {
            Res.string.import_none.asLemonadeCodeString()
          } else {
            Res.plurals.import_found.asLemonadeCodeString(scan.sessions.size)
          },
          notes = listOfNotNull(
            Res.string.import_running.asLemonadeCodeString(name).takeIf { scan.running && importable.isNotEmpty() },
            Res.plurals.import_skipped.asLemonadeCodeString(scan.skipped).takeIf { scan.skipped > 0 },
          ),
          rows = scan.sessions.map { row(it, nested = it.parentKey in listed, ticked = it.key in ticked, home = home) },
          canTick = importable.isNotEmpty(),
          allTicked = importable.isNotEmpty() && importable.all { it.key in ticked },
        )
      }
      is SourceScan.NotInstalled -> ImportSourceModel(id, name, Res.string.import_not_installed.asLemonadeCodeString())
      is SourceScan.UnknownFormat -> ImportSourceModel(id, name, Res.string.import_unknown_format.asLemonadeCodeString(), problem = true)
      is SourceScan.Failed -> ImportSourceModel(id, name, Res.string.import_read_failed.asLemonadeCodeString(), problem = true, detail = scan.message)
    }
  }

  private fun row(session: ImportableSession, nested: Boolean, ticked: Boolean, home: String) = ImportRowModel(
    key = session.key,
    name = session.name,
    folder = abbreviateHome(session.folder, home),
    nested = nested,
    ticked = ticked && session.blocked == null,
    enabled = session.blocked == null,
    note = note(session),
    noteIsProblem = session.blocked == ImportBlock.FolderMissing,
  )

  private fun note(session: ImportableSession): LemonadeCodeString? = when {
    session.blocked == ImportBlock.AlreadyImported -> Res.string.import_already_imported
    session.blocked == ImportBlock.FolderMissing -> Res.string.import_folder_missing
    session.worktree && session.conversation != null -> Res.string.import_note_worktree_conversation
    session.worktree -> Res.string.import_note_worktree
    session.conversation != null -> Res.string.import_note_conversation
    else -> null
  }?.asLemonadeCodeString()
}
