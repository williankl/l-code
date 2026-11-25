package dev.lemonadecode.workspace.impl

import dev.lemonadecode.domain.session.ImportBlock
import dev.lemonadecode.domain.session.ImportSource
import dev.lemonadecode.domain.session.ImportableSession
import dev.lemonadecode.domain.session.SourceScan
import dev.lemonadecode.platform.localization.asLemonadeCodeString
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
import dev.lemonadecode.workspace.strings.import_unknown_format
import dev.lemonadecode.workspace.uimodel.ImportRowModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportMapperTest {
  private val herdr = ImportSource("herdr", "herdr")
  private val home = "/Users/you"

  private fun session(key: String, folder: String, parentKey: String? = null, worktree: Boolean = false, conversation: String? = null, blocked: ImportBlock? = null) = ImportableSession(key, key, null, folder, parentKey, worktree, conversation, blocked)

  @Test
  fun `before anything is found it's looking, and the settings list names the tools`() {
    val looking = ImportMapper.model(listOf("herdr"), ImportViewState(), home)
    assertTrue(looking.loading)
    assertEquals("herdr", looking.sourceNames)
    assertEquals(false, ImportMapper.model(listOf("herdr"), null, home).loading)
  }

  @Test
  fun `each session says what comes with it, or why it can't come`() {
    val sessions = listOf(
      session("app", "$home/code/app", conversation = "c1"),
      session("app-wt", "$home/code/app.worktrees/wt", parentKey = "app", worktree = true, conversation = "c2"),
      session("app-wt2", "$home/code/app.worktrees/wt2", parentKey = "app", worktree = true),
      session("notes", "$home/notes"),
      session("old", "$home/code/old", blocked = ImportBlock.AlreadyImported),
      session("gone", "$home/code/gone", blocked = ImportBlock.FolderMissing),
    )
    val model = ImportMapper.model(listOf("herdr"), ImportViewState(scans = listOf(SourceScan.Found(herdr, sessions)), ticked = setOf("app", "old")), home)
    val source = model.sources.single()
    assertEquals(Res.plurals.import_found.asLemonadeCodeString(6), source.status)
    assertEquals(null, source.warning)
    assertEquals(1, model.tickedCount)
    assertEquals(
      listOf(
        ImportRowModel("app", "app", "~/code/app", nested = false, ticked = true, enabled = true, note = Res.string.import_note_conversation.asLemonadeCodeString()),
        ImportRowModel("app-wt", "app-wt", "~/code/app.worktrees/wt", nested = true, ticked = false, enabled = true, note = Res.string.import_note_worktree_conversation.asLemonadeCodeString()),
        ImportRowModel("app-wt2", "app-wt2", "~/code/app.worktrees/wt2", nested = true, ticked = false, enabled = true, note = Res.string.import_note_worktree.asLemonadeCodeString()),
        ImportRowModel("notes", "notes", "~/notes", nested = false, ticked = false, enabled = true, note = null),
        ImportRowModel("old", "old", "~/code/old", nested = false, ticked = false, enabled = false, note = Res.string.import_already_imported.asLemonadeCodeString()),
        ImportRowModel("gone", "gone", "~/code/gone", nested = false, ticked = false, enabled = false, note = Res.string.import_folder_missing.asLemonadeCodeString(), noteIsProblem = true),
      ),
      source.rows,
    )
  }

  @Test
  fun `a tool still running is warned about, and one with nothing, or nothing to read, says so`() {
    val running = ImportMapper.model(listOf("herdr"), ImportViewState(scans = listOf(SourceScan.Found(herdr, listOf(session("a", "/a")), running = true))), home)
    assertEquals(Res.string.import_running.asLemonadeCodeString("herdr"), running.sources.single().warning)

    fun status(scan: SourceScan) = ImportMapper.model(listOf("herdr"), ImportViewState(scans = listOf(scan)), home).sources.single().let { it.status to it.problem }
    assertEquals(Res.string.import_none.asLemonadeCodeString() to false, status(SourceScan.Found(herdr, emptyList(), running = true)))
    assertEquals(Res.string.import_not_installed.asLemonadeCodeString() to false, status(SourceScan.NotInstalled(herdr)))
    assertEquals(Res.string.import_unknown_format.asLemonadeCodeString("session.json version 4") to true, status(SourceScan.UnknownFormat(herdr, "session.json version 4")))
    assertEquals(Res.string.import_read_failed.asLemonadeCodeString("denied") to true, status(SourceScan.Failed(herdr, "denied")))
  }
}
