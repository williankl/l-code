package dev.lemonadecode.mobile.impl

import dev.lemonadecode.domain.mobile.DeviceId
import dev.lemonadecode.domain.mobile.RecordingFailure
import dev.lemonadecode.domain.mobile.RecordingResolution
import dev.lemonadecode.domain.mobile.RecordingStop
import dev.lemonadecode.domain.mobile.ScreenRecording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

  /**
   * Some pictures are on disk already, in a fragment written whole, so the file plays even if it's
   * never finished. Until then the pictures wait in memory.
   */
  val reachedDisk: Boolean

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
 *
 * A recording never carries on silently when its video can't be trusted: it ends, keeping what it
 * wrote, when the stream stays down for [StallLimit] ([onStreamEnded]; a still screen keeps its
 * stream up), when the mirror gives up on video ([onVideoLost]), when the device won't record at
 * its full size ([onRefused]), or when pictures come out of order. While it records, the free
 * space of the disk it's written to is looked at every [SpaceCheck]; under [LowSpace] it says so.
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

  private class Session(val messages: Channel<Message>, val resolution: RecordingResolution)

  private sealed interface Message {
    class Picture(val accessUnit: ByteArray, val width: Int, val height: Int, val at: ComparableTimeMark) : Message

    /** The last picture was cut short. */
    data object CutShort : Message

    /** The stream ended; another may follow, as when `screenrecord` starts again. */
    class StreamEnded(val at: ComparableTimeMark) : Message

    /** The device wouldn't stream at the size asked for. */
    class Refused(val at: ComparableTimeMark) : Message

    /** The mirror gave up on video. */
    class VideoLost(val at: ComparableTimeMark) : Message

    class Stop(val at: ComparableTimeMark, val stop: RecordingStop, val done: CompletableDeferred<ScreenRecording>) : Message
  }

  /** The devices being recorded now. */
  val recording: Set<DeviceId> get() = sessions.value.keys

  /** How big [id]'s recording is, while it records; its stream is to be that size. */
  fun resolutionOf(id: DeviceId): RecordingResolution? = sessions.value[id]?.resolution

  /** The devices recorded at full resolution now, whose stream is at their own size until they stop. */
  val atFullResolution: Flow<Set<DeviceId>> = sessions
    .map { all -> all.filterValues { it.resolution == RecordingResolution.Full }.keys }
    .distinctUntilChanged()

  /** Starts recording [id] at [resolution], named [deviceName] in the file's name; false when it's being recorded already. */
  suspend fun start(id: DeviceId, deviceName: String, resolution: RecordingResolution = RecordingResolution.Mirror): Boolean = lock.withLock {
    if (id in sessions.value) return@withLock false
    // A stop that's never taken, e.g. as the app closes, is let go rather than left waiting.
    val messages = Channel<Message>(Channel.UNLIMITED) { (it as? Message.Stop)?.done?.cancel() }
    val startedAt = timeSource.markNow()
    val recording = ScreenRecording.Recording(clock(), resolution)
    sessions.update { it + (id to Session(messages, resolution)) }
    mutableRecordings.update { it + (id to recording) }
    scope.launch(io) { record(id, deviceName, recording, startedAt, messages) }
    true
  }

  /** One picture of [id]'s video, [width] by [height], as an access unit; kept only while [id] is recorded. */
  fun onPicture(id: DeviceId, width: Int, height: Int, accessUnit: ByteArray) = send(id, Message.Picture(accessUnit, width, height, timeSource.markNow()))

  /** The last picture of [id]'s video, handed over when its stream went quiet, was only part of one. */
  fun onCutShort(id: DeviceId) = send(id, Message.CutShort)

  /** [id]'s video stream ended; one that doesn't start again within [StallLimit] ends the recording. */
  fun onStreamEnded(id: DeviceId) = send(id, Message.StreamEnded(timeSource.markNow()))

  /** [id] wouldn't stream at its full size, failing or making it smaller. */
  fun onRefused(id: DeviceId) = send(id, Message.Refused(timeSource.markNow()))

  /** [id]'s mirror gave up on video and shows screenshots instead. */
  fun onVideoLost(id: DeviceId) = send(id, Message.VideoLost(timeSource.markNow()))

  private fun send(id: DeviceId, message: Message) {
    val session = sessions.value[id] ?: return
    val _ = session.messages.trySend(message)
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

  /** Writes [id]'s pictures until it ends, and meanwhile watches the disk's free space. */
  private suspend fun record(id: DeviceId, deviceName: String, recording: ScreenRecording.Recording, startedAt: ComparableTimeMark, messages: Channel<Message>) = coroutineScope {
    val watcher = launch { watchSpace(id, messages, recording) }
    try {
      write(id, deviceName, recording.resolution, startedAt, messages)
    } finally {
      watcher.cancel()
    }
  }

  /** Says, while [id] records into [messages], whether its disk is running low; it goes on either way. */
  private suspend fun watchSpace(id: DeviceId, messages: Channel<Message>, recording: ScreenRecording.Recording) {
    while (true) {
      val left = runCatching { files.recordingSpace() }.getOrNull()
      val low = left?.takeIf { it < LowSpace }
      lock.withLock {
        if (sessions.value[id]?.messages === messages) mutableRecordings.update { it + (id to recording.copy(spaceLeft = low)) }
      }
      delay(SpaceCheck)
    }
  }

  /**
   * Writes [id]'s pictures as they come until it's stopped or can't go on. However it ends, the
   * stop asked for is answered, with the files written so far.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  private suspend fun write(id: DeviceId, deviceName: String, resolution: RecordingResolution, startedAt: ComparableTimeMark, messages: Channel<Message>) {
    var part: Part? = null
    val saved = mutableListOf<String>()
    var stop: Message.Stop? = null
    // A stream's first picture came, even if it was cut short then.
    var sawVideo = false
    // The stream is down since then, between `screenrecord`'s runs or for good.
    var downSince: ComparableTimeMark? = null

    /** Finishes the file being written at [at] and keeps it; one nothing was kept in is deleted. */
    fun close(at: ComparableTimeMark) {
      val current = part ?: return
      if (current.isEmpty) current.file.delete() else current.finish(at)
      // Only once it's whole: a file that failed to finish is reported as written in part.
      part = null
      if (!current.isEmpty) saved += current.path
    }

    /** Ends as [reason], keeping the files written, the one in hand finished at [at]. */
    fun failing(reason: RecordingFailure, at: ComparableTimeMark): ScreenRecording {
      close(at)
      return ScreenRecording.Failed(reason, saved.toList())
    }
    try {
      val ending = try {
        var result: ScreenRecording? = null
        while (result == null) {
          // A still screen sends nothing for as long as it's still; only a stream that's down, or none yet, has a deadline.
          val deadline = when {
            !sawVideo -> FirstVideoWait - startedAt.elapsedNow()
            else -> downSince?.let { StallLimit - it.elapsedNow() }
          }
          val message = if (deadline == null) {
            messages.receive()
          } else {
            select<Message?> {
              messages.onReceive { it }
              onTimeout(deadline.coerceAtLeast(Duration.ZERO)) { null }
            }
          }
          when (message) {
            null -> result = when {
              sawVideo -> failing(RecordingFailure.VideoLost, timeSource.markNow())
              // At its own size, a device that sends nothing is one that can't.
              resolution == RecordingResolution.Full -> ScreenRecording.Failed(RecordingFailure.FullResolutionRefused)
              else -> ScreenRecording.Failed(RecordingFailure.NoVideo)
            }
            is Message.Picture -> {
              // Written as they come, pictures have to show in the order they come: no B-frames.
              if (!sliceTypes(message.accessUnit).all { it % 5 in KeptSliceTypes }) {
                result = failing(RecordingFailure.UnsupportedVideo, message.at)
                continue
              }
              val current = part
              val starts = startsStream(message.accessUnit)
              if (starts) {
                sawVideo = true
                downSince = null
              }
              // Until a stream starts, its pictures can't be decoded on their own.
              if ((current == null || current.isEmpty) && !starts) continue
              if (current == null || (starts && (message.width != current.width || message.height != current.height))) {
                close(message.at)
                part = open(deviceName, message)
              }
              part?.write(message)
            }
            Message.CutShort -> part?.discardLast()
            is Message.StreamEnded -> if (downSince == null) downSince = message.at
            is Message.Refused -> result = failing(RecordingFailure.FullResolutionRefused, message.at)
            is Message.VideoLost -> result = failing(RecordingFailure.VideoLost, message.at)
            is Message.Stop -> {
              stop = message
              close(message.at)
              result = when {
                saved.isNotEmpty() -> ScreenRecording.Saved(saved.toList(), (message.at - startedAt).inWholeMilliseconds, message.stop)
                // Its only picture was cut short, and the stop came before a whole one.
                sawVideo -> ScreenRecording.Failed(RecordingFailure.NoWholePicture)
                else -> ScreenRecording.Failed(RecordingFailure.NoVideo)
              }
            }
          }
        }
        result
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        // The file being written plays as far as it reached the disk; one with nothing there goes.
        val partial = part?.takeIf { !it.isEmpty && it.file.reachedDisk }?.path
        part?.takeIf { it.path != partial }?.let { val _ = runCatching { it.file.delete() } }
        part = null
        val reason = if (e is UnsupportedVideoException) RecordingFailure.UnsupportedVideo else RecordingFailure.Unwritable(e.message ?: e.toString())
        ScreenRecording.Failed(reason, saved + listOfNotNull(partial))
      }
      end(id, messages, ending)
      stop?.done?.complete(ending)
    } catch (e: CancellationException) {
      // The app is closing: what was written is kept as far as it went, and no stop is left waiting.
      withContext(NonCancellable) {
        part?.let { current ->
          val _ = runCatching { if (current.isEmpty) current.file.delete() else current.finish(timeSource.markNow()) }
        }
        lock.withLock {
          if (sessions.value[id]?.messages === messages) sessions.update { it - id }
        }
        messages.cancel()
        stop?.done?.cancel()
      }
      throw e
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
    val parameterSets = parameterSetsOf(first.accessUnit) ?: throw UnsupportedVideoException()
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

/** A stream's first picture came without its parameter sets. */
private class UnsupportedVideoException : Exception("The video's first picture has no parameter sets")

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

/**
 * Each slice's `slice_type` in [accessUnit]: 0 P, 1 B, 2 I, 3 SP, 4 SI, and 5 more when every
 * slice of the picture is of that type. Read from the slice header's start, which emulation
 * prevention bytes can't change in practice but are skipped anyway.
 */
internal fun sliceTypes(accessUnit: ByteArray): List<Int> = nalStarts(accessUnit).mapNotNull { (_, header) ->
  val type = accessUnit[header].toInt() and NalTypeMask
  if (type != NalSlice && type != NalIdrSlice) return@mapNotNull null
  val bits = BitReader(accessUnit, header + 1)
  // first_mb_in_slice, then slice_type.
  if (bits.unsignedExpGolomb() == null) null else bits.unsignedExpGolomb()
}

/** Reads bits of a NAL unit's payload from [start], leaving out emulation prevention bytes. */
private class BitReader(private val bytes: ByteArray, start: Int) {
  private var at = start
  private var bit = 0
  private var zeros = 0

  private fun nextBit(): Int? {
    if (bit == 0) {
      // `00 00 03` keeps a start code out of the payload; the 03 isn't data.
      if (zeros >= 2 && at < bytes.size && bytes[at] == EmulationPrevention) {
        at++
        zeros = 0
      }
      if (at >= bytes.size) return null
      zeros = if (bytes[at] == ZeroByte) zeros + 1 else 0
    }
    val value = (bytes[at].toInt() shr (7 - bit)) and 1
    bit++
    if (bit == 8) {
      bit = 0
      at++
    }
    return value
  }

  /** An `ue(v)`: n zero bits, a one, then n bits; null when the unit ends first. */
  fun unsignedExpGolomb(): Int? {
    var leading = 0
    while (true) {
      val next = nextBit() ?: return null
      if (next == 1) break
      if (++leading > MaxExpGolombZeros) return null
    }
    var value = 0
    repeat(leading) { value = (value shl 1) or (nextBit() ?: return null) }
    return (1 shl leading) - 1 + value
  }
}

/** How long a recording waits for the first picture of a stream before it gives up: the device sends no video. */
internal val FirstVideoWait: Duration = 10.seconds

/**
 * How long a recording's stream may stay down, its runs failing to start again, before the
 * recording stops. `screenrecord` starts again within a second; a still screen keeps its stream up.
 */
internal val StallLimit: Duration = 20.seconds

/** How often the free space of the disk a recording goes to is looked at. */
internal val SpaceCheck: Duration = 10.seconds

/**
 * Under this much free space, in bytes, a recording warns that the disk is running low: 2 GB,
 * about half an hour of video moving all the time at the 8 Mb/s the stream is sent at over USB.
 */
internal const val LowSpace: Long = 2_000_000_000L

/** `slice_type`s kept as they come, modulo 5: P and I. B, SP and SI pictures aren't. */
private val KeptSliceTypes = setOf(0, 2)

private const val MaxExpGolombZeros = 31
private const val EmulationPrevention: Byte = 3

/** Pictures that arrive together are spread this far apart, as a video's pictures can't share a time. */
private const val MinPictureGapMicros = 1_000L

private const val NalTypeMask = 0x1F
private const val NalSlice = 1
private const val NalIdrSlice = 5
private const val NalSps = 7
private const val NalPps = 8
private const val ZeroByte: Byte = 0
private const val OneByte: Byte = 1
