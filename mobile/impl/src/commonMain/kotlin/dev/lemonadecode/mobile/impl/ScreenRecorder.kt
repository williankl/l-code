package dev.lemonadecode.mobile.impl

import dev.lemonadecode.domain.mobile.DeviceId
import dev.lemonadecode.domain.mobile.RecordingFailure
import dev.lemonadecode.domain.mobile.RecordingStop
import dev.lemonadecode.domain.mobile.ScreenRecording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A video file being written one H.264 access unit at a time. Used by one writer at a time. */
internal interface VideoFile {
  /**
   * Adds [accessUnit], one picture in Annex B form as the stream gives it, shown from [atMicros]
   * after the file's start until the next one comes. Each one comes later than the one before.
   */
  fun write(accessUnit: ByteArray, atMicros: Long, keyFrame: Boolean)

  /** Takes back the picture written last, which turned out to be cut short: it isn't kept. */
  fun discardLast()

  /** Ends the file, its last picture shown until [atMicros]. */
  fun finish(atMicros: Long)

  /** Ends the file and deletes it, for one nothing was kept in. */
  fun delete()
}

/** Makes [VideoFile]s. */
internal fun interface VideoFiles {
  /**
   * A new MP4 at [path] for H.264 pictures [width] by [height], described by [parameterSets], the
   * stream's SPS and PPS in Annex B form. Throws when it can't be written, leaving no file behind.
   */
  fun mp4(path: String, width: Int, height: Int, parameterSets: ByteArray): VideoFile
}

/**
 * Records devices' screens from the video their mirror streams: [onPicture] gets each picture of
 * it, and while a device is recorded they go into an MP4 as they are, without being encoded
 * again. A file opens on the first picture a stream starts with, which carries what the rest need
 * to be decoded. A stream that starts again at the same size, as `screenrecord` does every few
 * minutes, carries on in the same file; one at another size, after the screen turned, goes on in
 * a new file, since players don't follow a video that changes size.
 *
 * Each picture shows from the moment the mirror's decoding reached it until the next one, so a
 * screen that holds still holds still in the video too. A picture the mirror handed over early,
 * when the stream went quiet, may turn out to have been cut short ([onCutShort]); it's taken back.
 */
internal class ScreenRecorder(
  private val scope: CoroutineScope,
  private val files: DeviceFiles,
  private val videos: VideoFiles,
  private val io: CoroutineDispatcher,
  private val timeSource: TimeSource.WithComparableMarks,
  /** Milliseconds since the epoch, for when a recording started. */
  private val clock: () -> Long,
) {
  private val mutableRecordings = MutableStateFlow<Map<DeviceId, ScreenRecording>>(emptyMap())
  val recordings: StateFlow<Map<DeviceId, ScreenRecording>> = mutableRecordings.asStateFlow()

  /** The recordings under way, by device; read on the thread the video is decoded on. */
  private val sessions = MutableStateFlow<Map<DeviceId, Session>>(emptyMap())
  private val lock = Mutex()

  private class Session(val messages: Channel<Message>)

  private sealed interface Message {
    class Picture(val accessUnit: ByteArray, val width: Int, val height: Int, val at: ComparableTimeMark) : Message

    /** The last picture was cut short. */
    data object CutShort : Message

    class Stop(val at: ComparableTimeMark, val stop: RecordingStop, val done: CompletableDeferred<ScreenRecording>) : Message
  }

  /** The devices being recorded now. */
  val recording: Set<DeviceId> get() = sessions.value.keys

  /** Starts recording [id], named [deviceName] in the file's name; false when it's being recorded already. */
  suspend fun start(id: DeviceId, deviceName: String): Boolean = lock.withLock {
    if (id in sessions.value) return@withLock false
    val messages = Channel<Message>(Channel.UNLIMITED)
    val startedAt = timeSource.markNow()
    sessions.update { it + (id to Session(messages)) }
    mutableRecordings.update { it + (id to ScreenRecording.Recording(clock())) }
    scope.launch(io) { record(id, deviceName, startedAt, messages) }
    true
  }

  /** One picture of [id]'s video, [width] by [height], as an access unit; kept only while [id] is recorded. */
  fun onPicture(id: DeviceId, width: Int, height: Int, accessUnit: ByteArray) {
    val session = sessions.value[id] ?: return
    val _ = session.messages.trySend(Message.Picture(accessUnit, width, height, timeSource.markNow()))
  }

  /** The last picture of [id]'s video, handed over when its stream went quiet, was only part of one. */
  fun onCutShort(id: DeviceId) {
    val session = sessions.value[id] ?: return
    val _ = session.messages.trySend(Message.CutShort)
  }

  /** Stops recording [id] and saves it; [stop] says why. Null when it wasn't recorded. */
  suspend fun stop(id: DeviceId, stop: RecordingStop = RecordingStop.Asked): ScreenRecording? {
    val done = CompletableDeferred<ScreenRecording>()
    val sent = lock.withLock {
      val session = sessions.value[id] ?: return null
      sessions.update { it - id }
      session.messages.trySend(Message.Stop(timeSource.markNow(), stop, done)).isSuccess
    }
    // The recording ended by itself meanwhile, e.g. no video came; it said how.
    return if (sent) done.await() else recordings.value[id]
  }

  /** Forgets how [id]'s last recording ended. */
  fun dismiss(id: DeviceId) = mutableRecordings.update { all -> if (all[id] is ScreenRecording.Recording) all else all - id }

  /**
   * Writes [id]'s pictures as they come until it's stopped, or until no video has come for
   * [FirstVideoWait]. However it ends, the stop asked for is answered, with the files written so far.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  private suspend fun record(id: DeviceId, deviceName: String, startedAt: ComparableTimeMark, messages: Channel<Message>) {
    var part: Part? = null
    val saved = mutableListOf<String>()
    var stop: Message.Stop? = null

    /** Finishes the file being written at [at] and keeps it; one nothing was kept in is deleted. */
    fun close(at: ComparableTimeMark) {
      val current = part ?: return
      if (current.isEmpty) current.file.delete() else current.finish(at)
      // Only once it's whole: a file that failed to finish is reported as written in part.
      part = null
      if (!current.isEmpty) saved += current.path
    }
    try {
      val ending = try {
        var result: ScreenRecording? = null
        while (result == null) {
          val message = if (part == null) {
            select<Message?> {
              messages.onReceive { it }
              onTimeout((FirstVideoWait - startedAt.elapsedNow()).coerceAtLeast(Duration.ZERO)) { null }
            }
          } else {
            messages.receive()
          }
          when (message) {
            null -> result = ScreenRecording.Failed(RecordingFailure.NoVideo)
            is Message.Picture -> {
              val current = part
              val starts = startsStream(message.accessUnit)
              // Until a stream starts, its pictures can't be decoded on their own.
              if ((current == null || current.isEmpty) && !starts) continue
              if (current == null || (starts && (message.width != current.width || message.height != current.height))) {
                close(message.at)
                part = open(deviceName, message)
              }
              part?.write(message)
            }
            Message.CutShort -> part?.discardLast()
            is Message.Stop -> {
              stop = message
              close(message.at)
              result = if (saved.isEmpty()) {
                ScreenRecording.Failed(RecordingFailure.NoVideo)
              } else {
                ScreenRecording.Saved(saved.toList(), (message.at - startedAt).inWholeMilliseconds, message.stop)
              }
            }
          }
        }
        result
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        // The file being written plays as far as it reached the disk, so it's kept with the whole ones.
        val partial = part?.takeUnless { it.isEmpty }?.path
        ScreenRecording.Failed(RecordingFailure.Unwritable(e.message ?: e.toString()), saved + listOfNotNull(partial))
      }
      end(id, messages, ending)
      stop?.done?.complete(ending)
    } finally {
      // Cancelled, e.g. the app is closing, or failed: what was written is kept as far as it went.
      part?.let { withContext(NonCancellable) { val _ = runCatching { it.finish(timeSource.markNow()) } } }
      stop?.done?.cancel()
    }
  }

  /** Ends [id]'s recording as [ending]: nothing more is taken, and a stop still waiting gets the same answer. */
  private suspend fun end(id: DeviceId, messages: Channel<Message>, ending: ScreenRecording) {
    lock.withLock {
      val current = sessions.value[id]
      // Another recording of the device started since goes on saying so.
      if (current == null || current.messages === messages) {
        if (current != null) sessions.update { it - id }
        mutableRecordings.update { it + (id to ending) }
      }
    }
    messages.close()
    for (message in messages) (message as? Message.Stop)?.done?.complete(ending)
  }

  private suspend fun open(deviceName: String, first: Message.Picture): Part {
    val parameterSets = checkNotNull(parameterSetsOf(first.accessUnit)) { "The video has no parameter sets" }
    val path = files.recordingPath(deviceName)
    return Part(videos.mp4(path, first.width, first.height, parameterSets), path, first.width, first.height)
  }

  /** One file of a recording; its time starts at the first picture kept in it. */
  private class Part(
    val file: VideoFile,
    val path: String,
    val width: Int,
    val height: Int,
  ) {
    private var startedAt: ComparableTimeMark? = null
    private var last = -1L
    private var kept = 0

    /** Nothing is kept in it: its only pictures were cut short. */
    val isEmpty: Boolean get() = kept == 0

    fun write(picture: Message.Picture) {
      val start = startedAt ?: picture.at.also { startedAt = it }
      // Pictures that come in one go would share a time; each one gets a moment of its own.
      val at = if (last < 0) 0L else maxOf((picture.at - start).inWholeMicroseconds, last + MinPictureGapMicros)
      file.write(picture.accessUnit, at, isKeyFrame(picture.accessUnit))
      last = at
      kept++
    }

    /** The picture written last was cut short; with none left, the time starts again at the next one. */
    fun discardLast() {
      if (kept == 0) return
      file.discardLast()
      kept--
      if (kept == 0) {
        startedAt = null
        last = -1L
      }
    }

    fun finish(at: ComparableTimeMark) {
      val start = startedAt ?: at
      file.finish(maxOf((at - start).inWholeMicroseconds, last + MinPictureGapMicros))
    }
  }
}

/** The types of the NAL units in [accessUnit], an Annex B access unit, in order. */
internal fun nalTypes(accessUnit: ByteArray): List<Int> = nalStarts(accessUnit).map { (_, header) -> accessUnit[header].toInt() and NalTypeMask }

/** Whether [accessUnit] starts a stream: its parameter sets, then a picture decoded on its own. */
internal fun startsStream(accessUnit: ByteArray): Boolean = nalTypes(accessUnit).let { NalSps in it && NalPps in it && NalIdrSlice in it }

/** Whether [accessUnit] holds a picture decoded on its own, which a player can start from. */
internal fun isKeyFrame(accessUnit: ByteArray): Boolean = NalIdrSlice in nalTypes(accessUnit)

/** What comes before [accessUnit]'s first slice, when that holds its SPS and PPS; null otherwise. */
internal fun parameterSetsOf(accessUnit: ByteArray): ByteArray? {
  val starts = nalStarts(accessUnit)
  val types = starts.map { (_, header) -> accessUnit[header].toInt() and NalTypeMask }
  val firstSlice = types.indexOfFirst { it == NalSlice || it == NalIdrSlice }
  val before = if (firstSlice < 0) types else types.take(firstSlice)
  if (NalSps !in before || NalPps !in before) return null
  val end = if (firstSlice < 0) accessUnit.size else starts[firstSlice].first
  return accessUnit.copyOfRange(0, end)
}

/** Where each NAL unit in [bytes] starts, a four-byte start code's leading zero included, and where its header is. */
private fun nalStarts(bytes: ByteArray): List<Pair<Int, Int>> {
  val starts = mutableListOf<Pair<Int, Int>>()
  var at = 0
  while (at + 3 < bytes.size) {
    if (bytes[at] == ZeroByte && bytes[at + 1] == ZeroByte && bytes[at + 2] == OneByte) {
      val start = if (at > 0 && bytes[at - 1] == ZeroByte) at - 1 else at
      starts += start to at + 3
      at += 3
    } else {
      at++
    }
  }
  return starts
}

/** How long a recording waits for the first picture of a stream before it gives up: the device sends no video. */
internal val FirstVideoWait: Duration = 10.seconds

/** Pictures that arrive together are spread this far apart, as a video's pictures can't share a time. */
private const val MinPictureGapMicros = 1_000L

private const val NalTypeMask = 0x1F
private const val NalSlice = 1
private const val NalIdrSlice = 5
private const val NalSps = 7
private const val NalPps = 8
private const val ZeroByte: Byte = 0
private const val OneByte: Byte = 1
