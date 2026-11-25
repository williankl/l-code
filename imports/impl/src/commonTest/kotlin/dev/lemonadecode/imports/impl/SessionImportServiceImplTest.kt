package dev.lemonadecode.imports.impl

import dev.lemonadecode.domain.session.ImportBlock
import dev.lemonadecode.domain.session.ImportSource
import dev.lemonadecode.domain.session.ImportableSession
import dev.lemonadecode.domain.session.SessionPlacement
import dev.lemonadecode.domain.session.SourceScan
import dev.lemonadecode.platform.architecture.testing.FakeLemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.preferences.PreferenceStore
import dev.lemonadecode.platform.preferences.testing.FakePreferenceStore
import dev.lemonadecode.sessions.api.ImportedSession
import dev.lemonadecode.sessions.fake.FakeSessionService
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionImportServiceImplTest {
  private val tool = ImportSource("tool", "Tool")
  private val storefront = ImportableSession("k1", "storefront", null, "/code/storefront", conversation = "chat-1")
  private val rateLimit = ImportableSession("k2", "rate limit", "rate limit", "/code/storefront.worktrees/rate-limit", parentKey = "k1", worktree = true, conversation = "chat-2")
  private val gone = ImportableSession("k3", "spike", null, "/code/spike")

  private var found: SourceScan = SourceScan.Found(tool, listOf(storefront, rateLimit, gone))
  private val source = object : SessionSource {
    override val info: ImportSource = tool

    override suspend fun read(): SourceScan = found
  }
  private val folders = mutableSetOf("/code/storefront", "/code/storefront.worktrees/rate-limit")

  /** Conversations Claude has a transcript of, with the folder it's for. */
  private val conversations = mutableSetOf("chat-1" to "/code/storefront")
  private val environment = object : ImportEnvironment {
    override fun isFolder(path: String): Boolean = path in folders

    override fun hasConversation(conversation: String, folder: String): Boolean = conversation to folder in conversations
  }
  private val sessions = FakeSessionService()
  private val store = FakePreferenceStore()

  private class DiskFull : Exception("No space left on device")

  private fun service(sources: Set<SessionSource> = setOf(source)) = SessionImportServiceImpl(sources, sessions, store, environment, FakeLemonadeCodeCoroutineDispatchers())

  @Test
  fun `a scan marks a missing folder, and offers only conversations Claude can resume`() = runTest {
    val scanned = assertIs<SourceScan.Found>(service().scan().single())
    assertEquals(listOf(null, null, ImportBlock.FolderMissing), scanned.sessions.map { it.blocked })
    assertEquals(listOf("chat-1", null, null), scanned.sessions.map { it.conversation })
  }

  @Test
  fun `importing opens parents first, nests worktrees and resumes the conversation`() = runTest {
    val service = service()
    val scanned = assertIs<SourceScan.Found>(service.scan().single()).sessions
    // Picked child first: the parent still opens first, so the child has somewhere to go.
    val result = service.import(listOf(scanned[1], scanned[0]))

    assertEquals(2, result.opened.size)
    assertEquals(emptyList(), result.failed)
    val (parentId, childId) = result.opened
    assertEquals(
      listOf(
        ImportedSession("/code/storefront", "storefront", null, parentId = null, placement = SessionPlacement.SameFolder, claudeConversation = "chat-1"),
        ImportedSession("/code/storefront.worktrees/rate-limit", "rate limit", "rate limit", parentId = parentId, placement = SessionPlacement.Worktree, claudeConversation = null),
      ),
      sessions.imported,
    )
    val child = sessions.sessions.value.single { it.id == childId }
    assertEquals(parentId, child.parentId)
    assertEquals(SessionPlacement.Worktree, child.placement)
  }

  @Test
  fun `an imported session is never imported twice`() = runTest {
    val service = service()
    val first = assertIs<SourceScan.Found>(service.scan().single()).sessions
    assertEquals(1, service.import(listOf(first[0])).opened.size)

    // Even asked again from the list shown before.
    assertEquals(emptyList(), service.import(listOf(first[0])).opened)
    val again = assertIs<SourceScan.Found>(service.scan().single()).sessions
    assertEquals(ImportBlock.AlreadyImported, again[0].blocked)
    assertEquals(1, sessions.imported.size)

    // Remembered across launches: a new service over the same preferences.
    assertEquals(ImportBlock.AlreadyImported, assertIs<SourceScan.Found>(service().scan().single()).sessions[0].blocked)
  }

  @Test
  fun `each import is remembered as soon as it opens, so a store that fails loses at most that one`() = runTest {
    // The first save works; the second finds the disk full.
    val failing = object : PreferenceStore by store {
      var saves = 0

      override suspend fun update(transform: (Map<String, JsonElement>) -> Map<String, JsonElement>) {
        if (++saves == 2) throw DiskFull()
        store.update(transform)
      }
    }
    val service = SessionImportServiceImpl(setOf(source), sessions, failing, environment, FakeLemonadeCodeCoroutineDispatchers())
    val scanned = assertIs<SourceScan.Found>(service.scan().single()).sessions
    assertFailsWith<DiskFull> { service.import(listOf(scanned[0], scanned[1])) }
    assertEquals(2, sessions.imported.size)
    assertEquals(ImportBlock.AlreadyImported, assertIs<SourceScan.Found>(service.scan().single()).sessions[0].blocked)
  }

  @Test
  fun `a worktree imported later goes under its parent while that's open`() = runTest {
    val service = service()
    val scanned = assertIs<SourceScan.Found>(service.scan().single()).sessions
    val parentId = service.import(listOf(scanned[0])).opened.single()
    service.import(listOf(scanned[1]))
    assertEquals(parentId, sessions.imported.last().parentId)
  }

  @Test
  fun `with its parent closed, a worktree goes to the top`() = runTest {
    val service = service()
    val scanned = assertIs<SourceScan.Found>(service.scan().single()).sessions
    sessions.close(service.import(listOf(scanned[0])).opened.single())
    service.import(listOf(scanned[1]))
    assertEquals(null, sessions.imported.last().parentId)
  }

  @Test
  fun `blocked sessions are skipped, and one that fails to open is reported and can be tried again`() = runTest {
    val service = service()
    val scanned = assertIs<SourceScan.Found>(service.scan().single()).sessions
    sessions.missingFolders = setOf("/code/storefront")
    val result = service.import(scanned)
    assertEquals(listOf(storefront.key), result.failed.map { it.key })
    // The worktree opens on its own, at the top; the missing folder wasn't even tried.
    assertEquals(listOf("/code/storefront", "/code/storefront.worktrees/rate-limit"), sessions.imported.map { it.directory })
    assertEquals(null, sessions.imported.last().parentId)

    sessions.missingFolders = emptySet()
    assertEquals(1, service.import(listOf(scanned[0])).opened.size)
  }

  @Test
  fun `a source that fails says why, and the others are still read`() = runTest {
    val broken = object : SessionSource {
      override val info: ImportSource = ImportSource("broken", "Broken")

      override suspend fun read(): SourceScan = error("disk on fire")
    }
    val scans = service(setOf(source, broken)).scan()
    assertEquals(listOf("Broken", "Tool"), scans.map { it.source.name })
    assertEquals("disk on fire", assertIs<SourceScan.Failed>(scans[0]).message)
    assertTrue(scans[1] is SourceScan.Found)
  }

  @Test
  fun `parents come first however they were picked`() {
    val a = ImportableSession("a", "a", null, "/a")
    val b = ImportableSession("b", "b", null, "/b", parentKey = "a")
    val c = ImportableSession("c", "c", null, "/c", parentKey = "b")
    val orphan = ImportableSession("d", "d", null, "/d", parentKey = "not-picked")
    assertEquals(listOf("a", "d", "b", "c"), parentsFirst(listOf(c, b, a, orphan)).map { it.key })
  }
}
