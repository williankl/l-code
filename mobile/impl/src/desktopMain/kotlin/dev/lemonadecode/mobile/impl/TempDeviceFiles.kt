package dev.lemonadecode.mobile.impl

import dev.lemonadecode.platform.architecture.LemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Files under `<java.io.tmpdir>/lemonade-code`: `captures` for the tools' pictures, `screenshots`
 * for the user's. Screen recordings are kept instead, in [recordings], by default `Lemonade Code`
 * in the system's videos folder.
 */
@ContributesBinding(AppScope::class)
@Inject
internal class TempDeviceFiles(
  private val dispatchers: LemonadeCodeCoroutineDispatchers,
  private val root: File = File(System.getProperty("java.io.tmpdir"), "lemonade-code"),
  private val recordings: File? = null,
) : DeviceFiles {
  /** Looked for when the first recording is saved, as on Linux that reads a file. */
  private val recordingsFolder: File by lazy { recordings ?: defaultRecordingsFolder() }

  override suspend fun capturePath(): String = withContext(dispatchers.io) {
    File.createTempFile("capture-", ".png", folder("captures")).absolutePath
  }

  override suspend fun take(path: String): ByteArray? = withContext(dispatchers.io) {
    val file = File(path)
    try {
      file.takeIf { it.isFile && it.length() > 0 }?.readBytes()
    } finally {
      val _ = file.delete()
    }
  }

  override suspend fun saveScreenshot(deviceName: String, png: ByteArray): String = withContext(dispatchers.io) {
    val file = reserveFile(folder("screenshots"), screenshotName(deviceName, LocalDateTime.now().format(Timestamp)), "png")
    file.writeBytes(png)
    file.absolutePath
  }

  override suspend fun recordingPath(deviceName: String): String = withContext(dispatchers.io) {
    val folder = recordingsFolder
    val _ = folder.mkdirs()
    reserveFile(folder, screenshotName(deviceName, LocalDateTime.now().format(Timestamp)), "mp4").absolutePath
  }

  private fun folder(name: String): File {
    val folder = File(root, name)
    val _ = folder.mkdirs()
    return folder
  }

  private companion object {
    val Timestamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
  }
}

/**
 * A new, empty [name].[extension] in [folder], or, when that's taken, the first of [name]-2, -3…
 * that [create] makes: made at once, so two asking at the same moment never get the same file.
 */
internal fun reserveFile(folder: File, name: String, extension: String, create: (File) -> Boolean = File::createNewFile): File = generateSequence(1) { it + 1 }
  .map { File(folder, if (it == 1) "$name.$extension" else "$name-$it.$extension") }
  .first { !it.exists() && create(it) }

/**
 * Where recordings are kept: `Lemonade Code` in `~/Movies` on a Mac, in the videos folder the
 * desktop names on Linux, which follows the user's language, and in `~/Videos` elsewhere.
 */
private fun defaultRecordingsFolder(): File {
  val home = System.getProperty("user.home")
  val os = System.getProperty("os.name").orEmpty()
  val videos = when {
    os.startsWith("Mac") -> File(home, "Movies")
    os.startsWith("Linux") -> {
      val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config"
      val userDirs = runCatching { File(config, "user-dirs.dirs").takeIf { it.isFile }?.readText() }.getOrNull()
      xdgVideosFolder(home, System.getenv("XDG_VIDEOS_DIR"), userDirs)?.let(::File) ?: File(home, "Videos")
    }
    else -> File(home, "Videos")
  }
  return File(videos, "Lemonade Code")
}

/**
 * The videos folder of a Linux desktop, as `xdg-user-dir VIDEOS` finds it: [environment]'s
 * `XDG_VIDEOS_DIR`, else the one in [userDirs], the text of `user-dirs.dirs`, with `$HOME` filled
 * in as [home]. Null when neither names one, or it's the home folder itself, which means none.
 */
internal fun xdgVideosFolder(home: String, environment: String?, userDirs: String?): String? {
  val named = environment?.takeIf { it.isNotBlank() }
    ?: userDirs?.lineSequence()
      ?.map { it.trim() }
      ?.firstOrNull { it.startsWith("XDG_VIDEOS_DIR=") }
      ?.substringAfter('=')
      ?.trim()
      ?.removeSurrounding("\"")
    ?: return null
  val path = named.replace("\${HOME}", home).replace("\$HOME", home).trimEnd('/')
  return path.takeIf { it.startsWith("/") && it != home.trimEnd('/') }
}
