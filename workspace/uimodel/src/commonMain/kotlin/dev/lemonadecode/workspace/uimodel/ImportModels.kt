package dev.lemonadecode.workspace.uimodel

import androidx.compose.runtime.Immutable
import dev.lemonadecode.platform.localization.LemonadeCodeString

/** Settings › Import sessions: what other tools have, ticked to bring over. */
@Immutable
public data class ImportSessionsModel(
  /** The tools it can import from, for the settings list, e.g. `herdr`. */
  val sourceNames: String,
  /** Still looking at what the tools have. */
  val loading: Boolean = false,
  val sources: List<ImportSourceModel> = emptyList(),
  /** How many sessions are ticked. */
  val tickedCount: Int = 0,
  /** The ticked sessions are being opened. */
  val importing: Boolean = false,
) {
  val canImport: Boolean get() = tickedCount > 0 && !importing && !loading

  public companion object {
    public val Empty: ImportSessionsModel = ImportSessionsModel(sourceNames = "")
  }
}

/** One tool: what it has, or why nothing can come from it. */
@Immutable
public data class ImportSourceModel(
  val id: String,
  val name: String,
  /** "4 sessions found", or why there are none: "Not installed on this computer"… */
  val status: LemonadeCodeString,
  /** [status] says something went wrong. */
  val problem: Boolean = false,
  /** Said when the tool still has its sessions open; null when it doesn't. */
  val warning: LemonadeCodeString? = null,
  val rows: List<ImportRowModel> = emptyList(),
  /** Some row can be ticked. */
  val canTick: Boolean = false,
  /** Every row that can be ticked is. */
  val allTicked: Boolean = false,
)

/** A session the tool has: its name, its folder and what comes with it. */
@Immutable
public data class ImportRowModel(
  val key: String,
  val name: String,
  /** With `~` for home. */
  val folder: String,
  /** Sits under the row before it, its parent. */
  val nested: Boolean,
  val ticked: Boolean,
  /** False when it can't come: imported before, or its folder is gone. */
  val enabled: Boolean,
  /** "Worktree · Claude Code conversation", or why it can't come; null when there's nothing to say. */
  val note: LemonadeCodeString?,
  /** [note] says why it can't come because something's wrong, like a missing folder. */
  val noteIsProblem: Boolean = false,
)
