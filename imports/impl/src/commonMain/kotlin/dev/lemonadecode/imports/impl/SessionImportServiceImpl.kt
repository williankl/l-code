package dev.lemonadecode.imports.impl

import dev.lemonadecode.domain.session.ImportBlock
import dev.lemonadecode.domain.session.ImportResult
import dev.lemonadecode.domain.session.ImportSource
import dev.lemonadecode.domain.session.ImportableSession
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SessionPlacement
import dev.lemonadecode.domain.session.SourceScan
import dev.lemonadecode.imports.api.SessionImportService
import dev.lemonadecode.platform.architecture.LemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.core.getOrElse
import dev.lemonadecode.platform.core.safeSuspendRunCatching
import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.platform.preferences.PreferenceStore
import dev.lemonadecode.sessions.api.ImportedSession
import dev.lemonadecode.sessions.api.SessionError
import dev.lemonadecode.sessions.api.SessionService
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Reads every [SessionSource], then marks what can't come over: sessions imported before, which are
 * remembered in the preferences by their key as each one opens, and those whose folder is gone.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
internal class SessionImportServiceImpl(
  sources: Set<SessionSource>,
  private val sessionService: SessionService,
  private val store: PreferenceStore,
  private val environment: ImportEnvironment,
  private val dispatchers: LemonadeCodeCoroutineDispatchers,
) : SessionImportService {
  private val readers = sources.sortedBy { it.info.name.lowercase() }
  private val imports = Mutex()
  private val json = Json { ignoreUnknownKeys = true }

  override val sources: List<ImportSource> = readers.map { it.info }

  override suspend fun scan(): List<SourceScan> {
    val imported = ledger().keys
    return readers.map { source ->
      val read = safeSuspendRunCatching { source.read() }.getOrElse { error -> SourceScan.Failed(source.info, error.message) }
      if (read is SourceScan.Found) read.copy(sessions = withContext(dispatchers.io) { read.sessions.map { it.checked(imported) } }) else read
    }
  }

  override suspend fun import(sessions: List<ImportableSession>): ImportResult = imports.withLock {
    val ledger = ledger().toMutableMap()
    val opened = mutableListOf<SessionId>()
    val failed = mutableListOf<ImportableSession>()
    for (session in parentsFirst(sessions.filter { it.blocked == null }.distinctBy { it.key })) {
      // Never twice, even when asked from a list shown before the last import.
      if (session.key in ledger) continue
      // Under its parent while that's open; one archived or closed since leaves it at the top.
      val open = sessionService.sessions.value
      val parent = session.parentKey?.let { ledger[it] }?.let(::SessionId)?.takeIf { id -> open.any { it.id == id } }
      val imported = ImportedSession(
        directory = session.folder,
        name = session.name,
        customName = session.customName,
        parentId = parent,
        placement = if (session.worktree) SessionPlacement.Worktree else SessionPlacement.SameFolder,
        claudeConversation = session.conversation,
      )
      val first = sessionService.openImported(imported)
      // Its parent was archived just as it started: it goes to the top instead.
      val parentWent = first is LemonadeCodeResult.Failure && first.error == SessionError.ParentArchived
      val result = if (parentWent) sessionService.openImported(imported.copy(parentId = null)) else first
      when (result) {
        is LemonadeCodeResult.Success -> {
          opened += result.value
          ledger[session.key] = result.value.value
          // Right away, so one that opened is never offered again, even if the import stops here.
          remember(session.key, result.value)
        }
        // What's left is its folder gone since the scan, or its shell not starting.
        is LemonadeCodeResult.Failure -> failed += session
      }
    }
    ImportResult(opened, failed)
  }

  private suspend fun remember(key: String, id: SessionId) {
    store.update { entries ->
      val saved = entries[LedgerKey]?.let { runCatching { json.decodeFromJsonElement(LedgerSerializer, it) }.getOrNull() }.orEmpty()
      entries + (LedgerKey to json.encodeToJsonElement(LedgerSerializer, saved + (key to id.value)))
    }
  }

  private fun ImportableSession.checked(imported: Set<String>): ImportableSession = copy(
    conversation = conversation?.takeIf { environment.hasConversation(it, folder) },
    blocked = when {
      key in imported -> ImportBlock.AlreadyImported
      !environment.isFolder(folder) -> ImportBlock.FolderMissing
      else -> null
    },
  )

  /** Each session imported so far, by its key, with the id of the session it became. */
  private suspend fun ledger(): Map<String, String> {
    val saved = store.snapshot()[LedgerKey] ?: return emptyMap()
    return runCatching { json.decodeFromJsonElement(LedgerSerializer, saved) }.getOrDefault(emptyMap())
  }

  private companion object {
    const val LedgerKey = "importedSessions"
    val LedgerSerializer = MapSerializer(String.serializer(), String.serializer())
  }
}

/** [sessions] in their order, except that each comes after the session it goes under when that's among them. */
internal fun parentsFirst(sessions: List<ImportableSession>): List<ImportableSession> {
  val keys = sessions.map { it.key }.toSet()
  val placed = mutableSetOf<String>()
  val ordered = mutableListOf<ImportableSession>()
  var pending = sessions
  while (pending.isNotEmpty()) {
    val (ready, waiting) = pending.partition { it.parentKey == null || it.parentKey !in keys || it.parentKey in placed }
    // A loop of parents can't be placed in order; they go as they are.
    val next = ready.ifEmpty { waiting }
    ordered += next
    placed += next.map { it.key }
    pending = if (ready.isEmpty()) emptyList() else waiting
  }
  return ordered
}
