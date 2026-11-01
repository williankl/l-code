package dev.lemonadecode.sessions.impl

import dev.lemonadecode.platform.architecture.LemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.platform.preferences.PreferencesFile
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where the sessions are kept between launches. An interface so tests keep them in memory. */
internal interface SessionStore {
  suspend fun load(): SavedSessions

  suspend fun save(saved: SavedSessions)
}

/** `sessions.json`, next to the preferences in the user's application data folder. */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
internal class FileSessionStore(
  preferences: PreferencesFile,
  private val dispatchers: LemonadeCodeCoroutineDispatchers,
) : SessionStore {
  private val file = File(preferences.file.parentFile, "sessions.json")
  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
  }
  private val mutex = Mutex()

  override suspend fun load(): SavedSessions = withContext(dispatchers.io) {
    if (!file.exists()) return@withContext SavedSessions()
    runCatching { json.decodeFromString(SavedSessions.serializer(), file.readText()) }.getOrDefault(SavedSessions())
  }

  override suspend fun save(saved: SavedSessions): Unit = mutex.withLock {
    withContext(dispatchers.io) {
      file.parentFile?.mkdirs()
      val temp = File(file.parentFile, "${file.name}.tmp")
      temp.writeText(json.encodeToString(SavedSessions.serializer(), saved))
      val _ = Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
  }
}
