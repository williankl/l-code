package dev.lemonadecode.mobile.impl

import dev.lemonadecode.domain.mobile.DeviceAvailability
import dev.lemonadecode.domain.mobile.DeviceButton
import dev.lemonadecode.domain.mobile.DeviceConnection
import dev.lemonadecode.domain.mobile.DeviceKey
import dev.lemonadecode.domain.mobile.LogLevel

/** One line of `adb devices -l`. */
internal data class AdbEntry(
  val serial: String,
  val availability: DeviceAvailability,
  val connection: DeviceConnection,
  /** `model:` with underscores as spaces, e.g. `Pixel 8`; adb leaves it out for unauthorized devices. */
  val model: String?,
)

/**
 * Reads `adb devices -l`:
 *
 * ```
 * List of devices attached
 * 38021FDH2000AB         device usb:1-1 product:shiba model:Pixel_8 device:shiba transport_id:1
 * 192.168.1.42:41235     device product:shiba model:Pixel_8 device:shiba transport_id:2
 * R58M123ABC             unauthorized usb:1-2 transport_id:4
 * ```
 */
internal fun parseAdbDevices(output: String): List<AdbEntry> = output.lineSequence()
  .map { it.trim() }
  .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") }
  .mapNotNull { line ->
    val parts = line.split(Whitespace)
    if (parts.size < 2) return@mapNotNull null
    val serial = parts[0]
    val attributes = parts.drop(2).mapNotNull { part ->
      val key = part.substringBefore(':', missingDelimiterValue = "")
      if (key.isEmpty()) null else key to part.substringAfter(':')
    }.toMap()
    val connection = when {
      "usb" in attributes -> DeviceConnection.Usb
      isNetworkSerial(serial) -> DeviceConnection.Wifi
      else -> DeviceConnection.Usb
    }
    AdbEntry(
      serial = serial,
      availability = when (parts[1]) {
        "device" -> DeviceAvailability.Ready
        // "no permissions (missing udev rules?)" on Linux: listed, but adb can't use it either.
        "unauthorized", "authorizing", "no" -> DeviceAvailability.Unauthorized
        else -> DeviceAvailability.Offline
      },
      connection = connection,
      model = attributes["model"]?.replace('_', ' ')?.takeIf { it.isNotBlank() },
    )
  }
  .toList()

/** `192.168.1.42:41235`, or a name adb found on the network: `adb-38021FDH-x1y2z3._adb-tls-connect._tcp`. */
internal fun isNetworkSerial(serial: String): Boolean = mdnsInstanceOf(serial) != null ||
  (':' in serial && serial.substringAfterLast(':').let { port -> port.isNotEmpty() && port.all(Char::isDigit) })

/** The service's instance name when adb connected to [serial] through mdns, e.g. `adb-38021FDH-x1y2z3`. */
internal fun mdnsInstanceOf(serial: String): String? = serial.indexOf(ConnectServiceSuffix).takeIf { it > 0 }?.let { serial.substring(0, it) }

private const val ConnectServiceSuffix = "._adb-tls-connect._tcp"

/** `192.168.1.42` for `192.168.1.42:41235`; `fe80::1` for `[fe80::1]:41235`. */
internal fun hostOf(address: String): String = address.substringBeforeLast(':').removeSurrounding("[", "]")

internal fun isHostAndPort(address: String): Boolean {
  val host = address.substringBeforeLast(':', missingDelimiterValue = "")
  val port = address.substringAfterLast(':').toIntOrNull()
  return host.isNotEmpty() && host.none(Char::isWhitespace) && port != null && port in 1..65535
}

/** The screen from `wm size`, preferring the override someone set over the physical size, turned to portrait. */
internal fun parseWmSize(output: String): Pair<Int, Int>? {
  fun size(label: String) = output.lineSequence().firstNotNullOfOrNull { line ->
    if (!line.trim().startsWith(label)) return@firstNotNullOfOrNull null
    val match = SizePattern.find(line) ?: return@firstNotNullOfOrNull null
    match.groupValues[1].toInt() to match.groupValues[2].toInt()
  }
  val (width, height) = size("Override size") ?: size("Physical size") ?: return null
  return minOf(width, height) to maxOf(width, height)
}

private val SizePattern = Regex("""(\d+)x(\d+)""")

internal enum class MdnsKind { Connect, Pairing, Other }

/** A service from `adb mdns services`. */
internal data class MdnsService(val instance: String, val kind: MdnsKind, val address: String)

/**
 * Reads `adb mdns services`:
 *
 * ```
 * List of discovered mdns services
 * adb-38021FDH-x1y2z3	_adb-tls-connect._tcp	192.168.1.42:41235
 * adb-38021FDH-x1y2z3	_adb-tls-pairing._tcp	192.168.1.42:37199
 * ```
 */
internal fun parseMdnsServices(output: String): List<MdnsService> = output.lineSequence()
  .map { it.trim() }
  .filter { it.isNotEmpty() }
  .mapNotNull { line ->
    val parts = line.split(Whitespace)
    if (parts.size < 3) return@mapNotNull null
    val address = parts.last()
    if (!isHostAndPort(address)) return@mapNotNull null
    val kind = when (parts[parts.size - 2].trimEnd('.')) {
      "_adb-tls-connect._tcp" -> MdnsKind.Connect
      "_adb-tls-pairing._tcp" -> MdnsKind.Pairing
      else -> MdnsKind.Other
    }
    MdnsService(instance = parts.dropLast(2).joinToString(" "), kind = kind, address = address)
  }
  .distinct()
  .toList()

/** The serial in a wireless debugging instance name: `38021FDH` in `adb-38021FDH-x1y2z3`. */
internal fun serialInInstance(instance: String): String? = instance.removePrefix("adb-")
  .takeIf { it != instance && '-' in it }
  ?.substringBeforeLast('-')

/** Whether `adb connect` connected, or found it already connected. */
internal fun connectSucceeded(output: String): Boolean {
  val text = output.lowercase()
  return "connected to" in text && "failed" !in text && "unable" !in text && "cannot" !in text
}

/** `adb-38021FDH-x1y2z3` in `Successfully paired to 192.168.1.42:37199 [guid=adb-38021FDH-x1y2z3]`. */
internal fun pairedGuid(output: String): String? = GuidPattern.find(output)?.groupValues?.get(1)

private val GuidPattern = Regex("""guid=([^\]\s]+)""")

/**
 * The package of the app in front, from `dumpsys activity activities` (`topResumedActivity`,
 * `mResumedActivity`) or `dumpsys window` (`mCurrentFocus`, `mFocusedApp`), whichever is given.
 */
internal fun parseForegroundPackage(output: String): String? = ForegroundKeys.firstNotNullOfOrNull { key ->
  Regex("""\b$key[=:]\s*\w+\{[^}]*?\bu\d+\s+([A-Za-z][\w.]*)/""").find(output)?.groupValues?.get(1)
}

private val ForegroundKeys = listOf("topResumedActivity", "mResumedActivity", "ResumedActivity", "mCurrentFocus", "mFocusedApp")

/** Process ids from `pidof`: `1234 5678`. */
internal fun parsePids(output: String): Set<Int> = output.split(Whitespace).mapNotNull { it.toIntOrNull() }.toSet()

/** What `adb shell` runs to read a device's name, Android version, screen and serial number once, in one go. */
internal val AndroidInfoScript: String = listOf(
  "settings get global device_name",
  "getprop ro.product.model",
  "getprop ro.build.version.release",
  "wm size",
  "getprop ro.serialno",
).joinToString("; echo $InfoMarker; ")

/** Separates the answers in [AndroidInfoScript]'s output. */
internal const val InfoMarker: String = "@@lemonade-code@@"

internal data class AndroidInfo(
  /** The name the user gave the device, else its model. */
  val name: String?,
  val osVersion: String?,
  val screen: Pair<Int, Int>?,
  /** `ro.serialno`, which wireless debugging's instance names carry. */
  val serialNumber: String?,
)

internal fun parseAndroidInfo(output: String): AndroidInfo {
  val parts = output.split(InfoMarker).map { it.trim() }
  fun part(index: Int) = parts.getOrNull(index)?.takeIf { it.isNotEmpty() && it != "null" }
  return AndroidInfo(
    name = part(0) ?: part(1),
    osVersion = part(2),
    screen = part(3)?.let(::parseWmSize),
    serialNumber = part(4),
  )
}

/** A line the device logged: when, how important, who and what. */
internal data class LogEntry(val time: String, val level: LogLevel, val tag: String, val message: String)

/** A logcat line and the process that logged it. */
internal data class LogcatLine(val pid: Int, val entry: LogEntry)

/**
 * Reads `logcat -v threadtime` (`11-02 10:14:03.120  1234  1250 E Tag: message`) line by line.
 * A line in another format keeps the previous line's process and tag and counts as Info.
 */
internal class LogcatParser {
  private var previous: LogcatLine? = null

  /** The line, or null for logcat's `--------- beginning of main` dividers. */
  fun parse(line: String): LogcatLine? {
    val text = line.trimEnd('\r', '\n')
    if (text.startsWith("--------- ")) return null
    val match = ThreadtimePattern.matchEntire(text)
    val parsed = if (match != null) {
      val (time, pid, level, tag, message) = match.destructured
      LogcatLine(pid.toInt(), LogEntry(time, logcatLevel(level.single()), tag.trim(), message))
    } else {
      val last = previous
      LogcatLine(last?.pid ?: -1, LogEntry(last?.entry?.time.orEmpty(), LogLevel.Info, last?.entry?.tag.orEmpty(), text))
    }
    previous = parsed
    return parsed
  }

  private fun logcatLevel(letter: Char): LogLevel = when (letter) {
    'V' -> LogLevel.Verbose
    'D' -> LogLevel.Debug
    'I' -> LogLevel.Info
    'W' -> LogLevel.Warning
    'E' -> LogLevel.Error
    'F', 'A' -> LogLevel.Fatal
    else -> LogLevel.Verbose
  }

  private companion object {
    val ThreadtimePattern = Regex("""^\d\d-\d\d\s+(\d\d:\d\d:\d\d\.\d+)\s+(\d+)\s+\d+\s+([VDIWEFAS])\s+(.*?)\s*:(?: (.*))?$""")
  }
}

/**
 * [text] as one argument for the device's `input text`, through the device's shell: spaces become
 * `%s`, which `input` turns back into spaces, and the whole is single-quoted so nothing in it
 * reaches the shell. A literal `%s` in [text] types as a space; `input` has no escape for it.
 */
internal fun inputTextArgument(text: String): String = "'" + text.replace(" ", "%s").replace("'", "'\\''") + "'"

internal val DeviceButton.keyCode: Int
  get() = when (this) {
    DeviceButton.Back -> 4
    DeviceButton.Home -> 3
  }

internal val DeviceKey.keyCode: Int
  get() = when (this) {
    DeviceKey.Enter -> 66
    DeviceKey.Backspace -> 67
    DeviceKey.ForwardDelete -> 112
    DeviceKey.Tab -> 61
    DeviceKey.Escape -> 111
    DeviceKey.Up -> 19
    DeviceKey.Down -> 20
    DeviceKey.Left -> 21
    DeviceKey.Right -> 22
  }

internal val Whitespace = Regex("""\s+""")
