package dev.lemonadecode.mobile.impl

import dev.lemonadecode.platform.architecture.LemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Files under `<java.io.tmpdir>/lemonade-code`: `captures` for the tools' pictures, `screenshots` for the user's. */
@ContributesBinding(AppScope::class)
@Inject
internal class TempDeviceFiles(
  private val dispatchers: LemonadeCodeCoroutineDispatchers,
  private val root: File = File(System.getProperty("java.io.tmpdir"), "lemonade-code"),
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
    val name = screenshotName(deviceName, LocalDateTime.now().format(Timestamp))
    // Two in the same second get -2, -3…
    val file = generateSequence(1) { it + 1 }.map { File(folder, if (it == 1) "$name.png" else "$name-$it.png") }.first { !it.exists() }
    file.writeBytes(png)
    file.absolutePath
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
