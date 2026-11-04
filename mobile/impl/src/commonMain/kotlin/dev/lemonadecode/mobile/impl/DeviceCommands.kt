package dev.lemonadecode.mobile.impl

import dev.lemonadecode.domain.mobile.MobileTools
import dev.lemonadecode.domain.mobile.QrMatrix
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.process.BinaryProcessOutput
import dev.lemonadecode.platform.process.ProcessOutput
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Runs adb and libimobiledevice. An interface so the service can be tested without them. */
internal interface DeviceCommands {
  /** Runs [command] to the end and collects what it printed. */
  suspend fun run(command: List<String>, timeout: Duration = CommandTimeout): LemonadeCodeResult<ProcessOutput, Throwable>

  /** Like [run], but keeps standard output as bytes, e.g. a PNG. */
  suspend fun runForBytes(command: List<String>, timeout: Duration = CommandTimeout): LemonadeCodeResult<BinaryProcessOutput, Throwable>

  /**
   * Starts [command] and emits each line of its standard output, for programs that keep running,
   * like `adb logcat`. Ends when the program exits, with [CommandExitException] if it failed.
   * The program is stopped when collection stops.
   */
  fun lines(command: List<String>): Flow<String>

  /**
   * Starts [command] and emits its standard output in chunks as it comes, for programs that keep
   * writing bytes, like a screen recording. Ends and stops the program like [lines].
   */
  fun bytes(command: List<String>): Flow<ByteArray>
}

/** A program started by [DeviceCommands.lines] exited with [exitCode]; the message is what it printed on standard error. */
internal class CommandExitException(val exitCode: Int, message: String) : Exception(message)

/** How long a one-shot command may take. */
internal val CommandTimeout: Duration = 10.seconds

/** Finds adb and libimobiledevice on this computer. */
internal interface MobileToolFinder {
  /** Looks for both tools. Can take a few seconds: it may ask the login shell for its PATH. */
  suspend fun find(): MobileTools

  /** The path of libimobiledevice's [tool], e.g. `idevice_id`, in [folder]. */
  fun ideviceTool(folder: String, tool: String): String
}

/** The files the tools write pictures to, and the screenshots saved for the user. */
internal interface DeviceFiles {
  /** A path in a temporary folder for a tool to write a picture to. */
  suspend fun capturePath(): String

  /** Reads [path] and deletes it; null when nothing was written there. */
  suspend fun take(path: String): ByteArray?

  /**
   * Saves [png] as `<temporary folder>/lemonade-code/screenshots/<device>-<yyyyMMdd-HHmmss>.png`
   * and returns its absolute path.
   */
  suspend fun saveScreenshot(deviceName: String, png: ByteArray): String
}

/** Draws text as a QR code. */
internal fun interface QrEncoder {
  fun encode(text: String): QrMatrix
}

/** `Pixel-8-20261102-101403` for `Pixel 8` at [timestamp] `20261102-101403`: the device's name made safe for a file name. */
internal fun screenshotName(deviceName: String, timestamp: String): String {
  val name = deviceName.replace(UnsafeFileCharacters, "-").trim('-', '.').ifEmpty { "device" }
  return "$name-$timestamp"
}

private val UnsafeFileCharacters = Regex("""[^A-Za-z0-9._-]+""")
