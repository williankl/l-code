package dev.lemonadecode.sessions.impl

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

/**
 * Where a Claude Code conversation says it works, read from the end of its transcript as it grows.
 * Each line the conversation writes carries the folder Claude was in (`cwd`), which follows a `cd`
 * its shell tool carries forward though its process never moves. Lines of its sub-agents
 * (`isSidechain`) carry their own folders and are passed over, and so are lines from before [since],
 * written by an earlier run of a resumed conversation.
 *
 * Transcripts reach hundreds of megabytes and single lines megabytes, so it reads only what was added
 * since it last looked, and only once the file has grown: at most [maxRead] bytes, and the last
 * [firstLook] the first time, since what's before them was written before this run began. A line
 * longer than [maxLine] is a tool's output, passed over unread: the next line names the same folder.
 */
internal class TranscriptTail(
  val file: File,
  /** When this run of the conversation began, in milliseconds since the epoch. */
  private val since: Long,
  private val firstLook: Long = 1L shl 20,
  private val maxRead: Long = 4L shl 20,
  private val maxLine: Int = 1 shl 20,
) {
  /** Where to read from next: just past the last whole line read, or -1 before the first look. */
  private var offset = -1L

  /** How long the file was at the last look. */
  private var seen = -1L

  /** What the newest line read named. */
  private var folder: String? = null

  /** The folder the newest line written since [since] names, if it's still there; null before there's one. */
  @Synchronized
  fun workingDirectory(): String? {
    val size = file.length()
    if (size != seen) {
      // Shorter than before: written again from the start.
      if (size < seen) reset()
      seen = size
      if (runCatching { read(size) }.isFailure) reset()
    }
    return folder?.takeIf { File(it).isDirectory }
  }

  private fun reset() {
    offset = -1
    seen = -1
    folder = null
  }

  private fun read(size: Long) {
    val start = if (offset < 0) maxOf(0, size - firstLook) else maxOf(offset, size - maxRead)
    if (start >= size) return
    // From the byte before, to tell whether the first line read is whole.
    val from = maxOf(0, start - 1)
    val bytes = ByteArray((size - from).toInt())
    RandomAccessFile(file, "r").use { input ->
      input.seek(from)
      input.readFully(bytes)
    }
    val last = bytes.lastIndexOf(Newline)
    if (last < 0) {
      // Not one line has ended yet: read it again once it has.
      offset = start
      return
    }
    // A line cut off at the start of what was read isn't whole: up to the first line break goes.
    val first = if (from == start) 0 else bytes.indexOf(Newline) + 1
    newestFolder(bytes, first, last + 1)?.let { folder = it }
    offset = from + last + 1
  }

  /** The folder named by the newest line in [bytes] from [from] to [to], whole lines each ending in a line break. */
  private fun newestFolder(bytes: ByteArray, from: Int, to: Int): String? {
    var end = to - 1
    while (end >= from) {
      var start = end
      while (start > from && bytes[start - 1] != Newline) start--
      if (end - start <= maxLine) folderOf(String(bytes, start, end - start, Charsets.UTF_8))?.let { return it }
      end = start - 1
    }
    return null
  }

  private fun folderOf(line: String): String? {
    val entry = runCatching { json.decodeFromString(Line.serializer(), line) }.getOrNull() ?: return null
    if (entry.isSidechain == true) return null
    val written = entry.timestamp?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return null
    return entry.cwd?.takeIf { it.isNotEmpty() && written >= since }
  }

  /** What a transcript line says about itself; it holds much more. */
  @Serializable
  private data class Line(val cwd: String? = null, val isSidechain: Boolean? = null, val timestamp: String? = null)

  private companion object {
    const val Newline = '\n'.code.toByte()
    val json = Json { ignoreUnknownKeys = true }
  }
}
