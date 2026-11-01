package dev.lemonadecode.platform.preferences

import dev.lemonadecode.platform.architecture.LemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where preferences live on disk. */
public data class PreferencesFile(val file: File) {
  public companion object {
    /**
     * The platform's per-user application data folder for [appName]. The folder of the app's old
     * name, [formerName], is moved there the first time, so settings and sessions carry over.
     */
    public fun default(appName: String = "Lemonade Code", formerName: String? = "Loupe"): PreferencesFile {
      val folder = folderFor(appName)
      formerName?.let { moveFolder(from = folderFor(it), to = folder) }
      return PreferencesFile(File(folder, "preferences.json"))
    }

    private fun folderFor(appName: String): File {
      val home = System.getProperty("user.home")
      val os = System.getProperty("os.name").orEmpty()
      return when {
        os.startsWith("Mac") -> File(home, "Library/Application Support/$appName")
        os.startsWith("Windows") -> File(System.getenv("APPDATA") ?: home, appName)
        else -> File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", appName.lowercase().replace(' ', '-'))
      }
    }

    private fun moveFolder(from: File, to: File) {
      if (to.exists() || !from.isDirectory) return
      val _ = runCatching {
        to.parentFile?.mkdirs()
        Files.move(from.toPath(), to.toPath())
      }
    }
  }
}

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
internal class FilePreferenceStore(
  private val location: PreferencesFile,
  private val dispatchers: LemonadeCodeCoroutineDispatchers,
) : PreferenceStore {
  private val state = MutableStateFlow<Map<String, JsonElement>?>(null)
  private val mutex = Mutex()
  private val json = Json { prettyPrint = true }

  override val entries: Flow<Map<String, JsonElement>> = state
    .onStart { val _ = ensureLoaded() }
    .filterNotNull()

  override suspend fun snapshot(): Map<String, JsonElement> = ensureLoaded()

  override suspend fun update(transform: (Map<String, JsonElement>) -> Map<String, JsonElement>) {
    mutex.withLock {
      val updated = transform(loadLocked())
      write(updated)
      state.value = updated
    }
  }

  private suspend fun ensureLoaded(): Map<String, JsonElement> = mutex.withLock { loadLocked() }

  private suspend fun loadLocked(): Map<String, JsonElement> = state.value ?: read().also { state.value = it }

  private suspend fun read(): Map<String, JsonElement> = withContext(dispatchers.io) {
    val file = location.file
    if (!file.exists()) return@withContext emptyMap()
    runCatching { json.parseToJsonElement(file.readText()).jsonObject.toMap() }.getOrDefault(emptyMap())
  }

  private suspend fun write(values: Map<String, JsonElement>): Unit = withContext(dispatchers.io) {
    val file = location.file
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, "${file.name}.tmp")
    temp.writeText(json.encodeToString(JsonObject.serializer(), JsonObject(values)))
    val _ = Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }
}
