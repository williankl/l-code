package dev.lemonadecode.sessions.impl

import dev.lemonadecode.platform.architecture.testing.FakeLemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.preferences.PreferencesFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FileSessionStoreTest {
  private val folder = Files.createTempDirectory("lemonade-code-store").toFile()
  private val store = FileSessionStore(PreferencesFile(File(folder, "preferences.json")), FakeLemonadeCodeCoroutineDispatchers(Dispatchers.IO))

  @Test
  fun `a file saved before archiving loads as it was, with nothing archived`() = runBlocking<Unit> {
    File(folder, "sessions.json").writeText(
      """
      {
        "sessions": [
          { "id": "a", "name": "storefront", "workingDirectory": "/code/storefront", "placement": "Root", "origin": "Opened", "resume": "claude --resume 42" },
          { "id": "b", "name": "sub-1", "workingDirectory": "/code/storefront", "parentId": "a", "placement": "SameFolder", "origin": "Split", "customName": "tests" }
        ]
      }
      """.trimIndent(),
    )
    val loaded = store.load()
    assertEquals(listOf("a", "b"), loaded.sessions.map { it.id })
    assertEquals("claude --resume 42", loaded.sessions[0].resume)
    assertEquals("tests", loaded.sessions[1].customName)
    assertEquals(emptyList(), loaded.archived)
  }

  @Test
  fun `archived sessions are saved and load back`() = runBlocking<Unit> {
    val saved = SavedSessions(
      sessions = listOf(SavedSession(id = "a", name = "storefront", workingDirectory = "/code/storefront")),
      archived = listOf(
        SavedArchive(
          sessions = listOf(
            SavedSession(id = "b", name = "sub-1", workingDirectory = "/code/storefront.worktrees/sub-1", parentId = "a", branch = "feat/x", repositoryRoot = "/code/storefront.worktrees/sub-1", agent = "claude", resume = "claude --resume 7"),
            SavedSession(id = "c", name = "sub-2", workingDirectory = "/code/storefront.worktrees/sub-1", parentId = "b", output = listOf("$ make test")),
          ),
          after = null,
          before = "d",
        ),
      ),
    )
    store.save(saved)
    assertEquals(saved, store.load())
  }
}
