package dev.lemonadecode.mobile.impl

import dev.lemonadecode.domain.mobile.LogLevel

/**
 * The UDIDs `idevice_id -l` or `-n` lists, one per line. Listing both at once adds ` (USB)` or
 * ` (Network)`, which is dropped.
 */
internal fun parseUdids(output: String): List<String> = output.lineSequence()
  .map { it.trim().substringBefore(' ') }
  .filter { UdidPattern.matches(it) }
  .distinct()
  .toList()

/** `00008110-001A2C3E0E12801E`, or 40 hex digits on older devices. */
private val UdidPattern = Regex("""[0-9A-Fa-f]{8}-[0-9A-Fa-f]{16}|[0-9A-Fa-f]{40}""")

/** The top-level `Key: value` lines of `ideviceinfo`; nested ones are indented and left out. */
internal fun parseIdeviceInfo(output: String): Map<String, String> = output.lineSequence()
  .filter { it.isNotEmpty() && !it.first().isWhitespace() }
  .mapNotNull { line ->
    val key = line.substringBefore(": ", missingDelimiterValue = "")
    if (key.isEmpty() || key.any(Char::isWhitespace)) null else key to line.substringAfter(": ").trim()
  }
  .toMap()

/** Whether a libimobiledevice error means the device doesn't trust this computer yet, or is locked. */
internal fun isTrustError(output: String): Boolean {
  val text = output.lowercase()
  return TrustErrors.any { it in text }
}

private val TrustErrors = listOf("pair", "trust", "password protected", "passcode", "invalid hostid", "denied")

/** What `idevicepair pair` said. */
internal enum class PairAttempt {
  Paired,

  /** The trust dialog is showing, the device is locked, or the dialog was dismissed: ask again. */
  WaitingForTrust,

  /** The device isn't plugged in any more. */
  NoDevice,
  Failed,
}

internal fun parsePairAttempt(output: String): PairAttempt {
  val text = output.lowercase()
  return when {
    "success: paired" in text -> PairAttempt.Paired
    "no device found" in text || "not found" in text -> PairAttempt.NoDevice
    "trust" in text || "passcode" in text || "locked" in text || "pending" in text -> PairAttempt.WaitingForTrust
    else -> PairAttempt.Failed
  }
}

/** Whether `idevicescreenshot` failed because this device can't be captured, e.g. no developer disk image. */
internal fun isScreenshotUnsupported(output: String): Boolean {
  val text = output.lowercase()
  return "developer disk image" in text || "screenshotr" in text || "not supported" in text || "invalid service" in text
}

/**
 * Reads `idevicesyslog` line by line: `Nov  2 10:14:03 iPhone storefront(CFNetwork)[1234] <Notice>: message`.
 * Colour codes are dropped. A line in another format keeps the previous line's tag and counts as Info.
 */
internal class SyslogParser {
  private var previous: LogEntry? = null

  fun parse(line: String): LogEntry {
    val text = AnsiPattern.replace(line, "").trimEnd('\r', '\n')
    val match = SyslogPattern.matchEntire(text)
    val entry = if (match != null) {
      val (time, process, level, message) = match.destructured
      LogEntry(time, syslogLevel(level), process.trim(), message)
    } else {
      LogEntry(previous?.time.orEmpty(), LogLevel.Info, previous?.tag.orEmpty(), text)
    }
    previous = entry
    return entry
  }

  private fun syslogLevel(level: String): LogLevel = when (level.lowercase()) {
    "debug" -> LogLevel.Debug
    "info", "notice" -> LogLevel.Info
    "warning" -> LogLevel.Warning
    "error" -> LogLevel.Error
    "fault", "critical", "alert", "emergency" -> LogLevel.Fatal
    else -> LogLevel.Info
  }

  private companion object {
    val SyslogPattern = Regex("""^[A-Z][a-z]{2}\s+\d{1,2}\s+(\d\d:\d\d:\d\d(?:\.\d+)?)\s+\S+\s+(.+?)\[\d+]\s+<(\w+)>:\s?(.*)$""")
    val AnsiPattern = Regex("""\u001B\[[0-9;]*[A-Za-z]""")
  }
}
