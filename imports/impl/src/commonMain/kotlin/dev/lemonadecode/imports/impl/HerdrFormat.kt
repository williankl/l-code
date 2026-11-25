package dev.lemonadecode.imports.impl

import dev.lemonadecode.domain.session.ClaudeConversation
import dev.lemonadecode.domain.session.ImportableSession
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * What herdr writes, as of herdr 0.9.5, read-only.
 *
 * `herdr session list --json` lists its persistent sessions (the default one and any named with
 * `--session`) with the folder each keeps its state in. In that folder `session.json` is the layout
 * herdr itself restores after a restart: its workspaces, each a folder with tabs of panes, linked
 * git worktrees nested under the workspace of their repository, and, per pane, the Claude Code
 * conversation the official integration reported. `version` is herdr's own format number; herdr
 * refuses a newer one, and so does this.
 */
internal object HerdrFormat {
  /** The newest `session.json` version this knows. */
  const val NewestVersion: Int = 3

  private val json = Json { ignoreUnknownKeys = true }

  /** The sessions in `herdr session list --json`'s output; null when it isn't that. */
  fun sessionList(output: String): List<HerdrSession>? = runCatching { json.decodeFromString(HerdrSessionList.serializer(), output).sessions }.getOrNull()

  /**
   * The workspaces in a `session.json`, as sessions to import. [session] is the herdr session it
   * belongs to, [home] the user's home folder, which herdr calls `~`.
   */
  fun sessions(content: String, session: String, home: String): Layout {
    val file = try {
      json.decodeFromString(LayoutFile.serializer(), content)
    } catch (error: IllegalArgumentException) {
      return Layout.Unknown("session.json: ${error.message?.lineSequence()?.firstOrNull().orEmpty()}")
    }
    if (file.version > NewestVersion) return Layout.Unknown("session.json version ${file.version}, newer than $NewestVersion")
    val workspaces = file.workspaces.mapIndexed { index, raw ->
      val workspace = runCatching { json.decodeFromJsonElement(Workspace.serializer(), raw) }.getOrNull()
        ?.let(::current)
        ?: return Layout.Unknown("session.json: workspace ${index + 1} is in neither herdr's current nor its earlier format")
      // herdr keeps a workspace's id across restarts, but rewrites its folder on every save from where
      // its first pane is now. Only a layout from before ids falls back to its place and folder.
      Entry(workspace, key = workspace.id?.let { "herdr:$session:$it" } ?: "herdr:$session:#$index:${workspace.identityCwd}")
    }
    return Layout.Read(arrange(workspaces, home))
  }

  sealed interface Layout {
    data class Read(val sessions: List<ImportableSession>) : Layout

    data class Unknown(val detail: String) : Layout
  }

  private data class Entry(val workspace: Workspace, val key: String)

  /** Parents first, each followed by its linked worktrees; one whose repository isn't open in herdr stays at the top. */
  private fun arrange(entries: List<Entry>, home: String): List<ImportableSession> {
    val parents = entries.associate { entry -> entry.key to parentOf(entry, entries)?.key }
    fun session(entry: Entry): ImportableSession {
      val workspace = entry.workspace
      val conversation = conversationOf(workspace)
      return ImportableSession(
        key = entry.key,
        name = workspace.customName?.trim()?.takeIf { it.isNotEmpty() } ?: automaticName(workspace, home),
        customName = workspace.customName?.trim()?.takeIf { it.isNotEmpty() },
        // Where herdr would resume the conversation: the pane it ran in.
        folder = conversation?.second?.takeIf { it.isNotBlank() } ?: workspace.identityCwd.orEmpty(),
        parentKey = parents[entry.key],
        worktree = parents[entry.key] != null,
        conversation = conversation?.first,
      )
    }
    return entries.filter { parents[it.key] == null }.flatMap { root ->
      listOf(session(root)) + entries.filter { parents[it.key] == root.key }.map(::session)
    }
  }

  /** The workspace of the main checkout a linked worktree's workspace belongs to, as herdr nests them. */
  private fun parentOf(entry: Entry, entries: List<Entry>): Entry? {
    val space = entry.workspace.worktreeSpace?.takeIf { it.isLinkedWorktree } ?: return null
    val others = entries.filter { it.key != entry.key }
    return others.firstOrNull { other -> other.workspace.worktreeSpace?.let { it.key == space.key && !it.isLinkedWorktree } == true }
      ?: space.repoRoot?.let { root -> others.firstOrNull { samePath(it.workspace.identityCwd, root) } }
  }

  /**
   * The Claude Code conversation the workspace's panes hold, with the folder of its pane: the
   * focused pane of the active tab first, then the rest in layout order, then the other tabs.
   */
  private fun conversationOf(workspace: Workspace): Pair<String, String?>? {
    val active = workspace.activeTab.takeIf { it in workspace.tabs.indices } ?: 0
    val tabs = workspace.tabs.withIndex().sortedBy { (index, _) -> if (index == active) -1 else index }.map { it.value }
    return tabs.asSequence()
      .flatMap { tab -> (listOfNotNull(tab.focused) + panesInOrder(tab)).distinct().mapNotNull { tab.panes[it.toString()] } }
      .firstNotNullOfOrNull { pane -> pane.agentSession?.claudeConversation()?.let { it to pane.cwd } }
  }

  /** Only what herdr's own Claude Code integration reported, and only a Claude session id: it ends up in a command. */
  private fun AgentSession.claudeConversation(): String? = value.takeIf {
    source == "herdr:claude" && agent == "claude" && kind == "id" && ClaudeConversation.isId(it)
  }

  private fun panesInOrder(tab: Tab): List<Int> {
    val ordered = mutableListOf<Int>()
    fun visit(node: JsonElement?) {
      val layout = node as? JsonObject ?: return
      (layout["Pane"] as? JsonPrimitive)?.intOrNull?.let { ordered += it }
      (layout["Split"] as? JsonObject)?.let { split ->
        visit(split["first"])
        visit(split["second"])
      }
    }
    visit(tab.layout)
    return listOfNotNull(tab.rootPane) + ordered + tab.panes.keys.mapNotNull { it.toIntOrNull() }.sorted()
  }

  /** herdr's name for a workspace it wasn't given one: its git checkout's folder, else its own, `~` at home. */
  private fun automaticName(workspace: Workspace, home: String): String {
    val folder = workspace.worktreeSpace?.checkoutPath ?: workspace.identityCwd.orEmpty()
    if (samePath(folder, home)) return "~"
    return folder.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { folder }
  }

  /** A workspace in herdr's current format, or one from before tabs, which kept one tab's panes in itself. */
  private fun current(workspace: Workspace): Workspace? = when {
    workspace.identityCwd != null -> workspace
    workspace.layout != null && workspace.panes != null -> {
      val tab = Tab(panes = workspace.panes, focused = workspace.focused, rootPane = workspace.rootPane, layout = workspace.layout)
      val identity = panesInOrder(tab).firstNotNullOfOrNull { tab.panes[it.toString()]?.cwd }
      identity?.let { workspace.copy(identityCwd = it, tabs = listOf(tab), activeTab = 0) }
    }
    else -> null
  }

  private fun samePath(a: String?, b: String?): Boolean = a != null && b != null && a.trimEnd('/', '\\') == b.trimEnd('/', '\\')

  @Serializable
  private data class HerdrSessionList(val sessions: List<HerdrSession> = emptyList())

  @Serializable
  private data class LayoutFile(
    val version: Int = 0,
    val workspaces: List<JsonElement> = emptyList(),
  )

  @Serializable
  private data class Workspace(
    val id: String? = null,
    @SerialName("custom_name") val customName: String? = null,
    @SerialName("identity_cwd") val identityCwd: String? = null,
    @SerialName("worktree_space") val worktreeSpace: WorktreeSpace? = null,
    val tabs: List<Tab> = emptyList(),
    @SerialName("active_tab") val activeTab: Int = 0,
    // Before tabs, a workspace held its one layout itself.
    val layout: JsonElement? = null,
    val panes: Map<String, Pane>? = null,
    val focused: Int? = null,
    @SerialName("root_pane") val rootPane: Int? = null,
  )

  @Serializable
  private data class WorktreeSpace(
    val key: String,
    @SerialName("repo_root") val repoRoot: String? = null,
    @SerialName("checkout_path") val checkoutPath: String? = null,
    @SerialName("is_linked_worktree") val isLinkedWorktree: Boolean = false,
  )

  @Serializable
  private data class Tab(
    val panes: Map<String, Pane> = emptyMap(),
    val focused: Int? = null,
    @SerialName("root_pane") val rootPane: Int? = null,
    val layout: JsonElement? = null,
  )

  @Serializable
  private data class Pane(
    val cwd: String? = null,
    @SerialName("agent_session") val agentSession: AgentSession? = null,
  )

  @Serializable
  private data class AgentSession(
    val source: String = "",
    val agent: String = "",
    val kind: String = "",
    val value: String = "",
  )
}

/** One of herdr's persistent sessions, from `herdr session list --json`. */
@Serializable
internal data class HerdrSession(
  val name: String,
  val default: Boolean = false,
  val running: Boolean = false,
  @SerialName("session_dir") val sessionDir: String,
)
