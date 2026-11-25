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
 * for the user's. Screen recordings are kept instead, in [recordings].
 */
@ContributesBinding(AppScope::class)
@Inject
internal class TempDeviceFiles(
  private val dispatchers: LemonadeCodeCoroutineDispatchers,
  private val root: File = File(System.getProperty("java.io.tmpdir"), "lemonade-code"),
  private val recordings: File = recordingsFolder(),
) : DeviceFiles {
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
    val folder = folder("screenshots")
    val file = newFile(folder, screenshotName(deviceName, LocalDateTime.now().format(Timestamp)), "png")
    file.writeBytes(png)
    file.absolutePath
  }

  override suspend fun recordingPath(deviceName: String): String = withContext(dispatchers.io) {
    val _ = recordings.mkdirs()
    val file = newFile(recordings, screenshotName(deviceName, LocalDateTime.now().format(Timestamp)), "mp4")
    // Taken now, so a second recording in the same second doesn't pick the same name.
    val _ = file.createNewFile()
    file.absolutePath
  }

  /** [name].[extension] in [folder], or, when that's taken, the first free of [name]-2, -3… */
  private fun newFile(folder: File, name: String, extension: String): File = generateSequence(1) { it + 1 }
    .map { File(folder, if (it == 1) "$name.$extension" else "$name-$it.$extension") }
    .first { !it.exists() }

  private fun folder(name: String): File {
    val folder = File(root, name)
    val _ = folder.mkdirs()
    return folder
  }

  private companion object {
    val Timestamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
  }
}

/** Where recordings are kept: `~/Movies/Lemonade Code` on a Mac, `~/Videos/Lemonade Code` elsewhere, as each system names its videos folder. */
private fun recordingsFolder(): File {
  val home = File(System.getProperty("user.home"))
  val videos = if (System.getProperty("os.name").orEmpty().startsWith("Mac")) "Movies" else "Videos"
  return File(File(home, videos), "Lemonade Code")
}
