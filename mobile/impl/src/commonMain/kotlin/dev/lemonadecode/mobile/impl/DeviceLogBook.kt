package dev.lemonadecode.mobile.impl

import dev.lemonadecode.domain.mobile.DeviceId
import dev.lemonadecode.domain.mobile.DeviceLogLine
import dev.lemonadecode.domain.mobile.DeviceLogs
import dev.lemonadecode.domain.mobile.MobileError
import dev.lemonadecode.platform.core.getOrElse
import dev.lemonadecode.platform.core.safeSuspendRunCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Where a log reader puts what it reads. */
internal interface LogSink {
  /** What reads the lines and whose they are, e.g. `logcat · com.teya.storefront`. */
  fun setSource(source: String)

  fun append(entries: List<LogEntry>)
}

/**
 * The last [capacity] lines of each device's log. One reader per device is shared by everyone
 * following it, and keeps going for [linger] after the last one leaves, so coming back to a device
 * shows the earlier lines. A reader stops when its device goes away and starts again if it comes
 * back while followed.
 */
internal class DeviceLogBook(
  private val scope: CoroutineScope,
  private val isConnected: (DeviceId) -> Boolean,
  /** Reads a device's log into the sink until the stream ends; returns why it stopped, or null if it just ended. */
  private val read: suspend (DeviceId, LogSink) -> MobileError?,
  private val capacity: Int = LogCapacity,
  private val linger: Duration = LogLinger,
) {
  private val lock = Mutex()

  /** Written under [lock]; read without it by [clear]. */
  private val streams = MutableStateFlow<Map<DeviceId, Stream>>(emptyMap())

  fun follow(id: DeviceId): Flow<DeviceLogs> = flow {
    val stream = acquire(id)
    try {
      emitAll(stream.state)
    } finally {
      withContext(NonCancellable) { release(stream) }
    }
  }

  fun clear(id: DeviceId) {
    streams.value[id]?.clear()
  }

  /** Stops the readers of devices that went away and restarts followed ones that came back. */
  suspend fun retain(): Unit = lock.withLock {
    for (stream in streams.value.values) {
      val connected = isConnected(stream.id)
      when {
        !connected && stream.collectors == 0 -> {
          stream.ending?.cancel()
          remove(stream)
        }
        !connected -> {
          stream.stopReading()
          stream.fail(MobileError.DeviceGone)
        }
        stream.collectors > 0 && !stream.isReading -> start(stream)
      }
    }
  }

  private suspend fun acquire(id: DeviceId): Stream = lock.withLock {
    val stream = streams.value[id] ?: Stream(id).also { streams.value += id to it }
    stream.collectors++
    stream.ending?.cancel()
    stream.ending = null
    if (!stream.isReading) start(stream)
    stream
  }

  private suspend fun release(stream: Stream) = lock.withLock {
    stream.collectors--
    if (stream.collectors == 0) {
      stream.ending = scope.launch {
        delay(linger)
        lock.withLock { if (stream.collectors == 0) remove(stream) }
      }
    }
  }

  private fun remove(stream: Stream) {
    stream.stopReading()
    streams.value -= stream.id
  }

  private fun start(stream: Stream) {
    val previous = stream.reader
    stream.reader = scope.launch {
      previous?.cancelAndJoin()
      var failures = 0
      while (true) {
        if (!isConnected(stream.id)) {
          stream.fail(MobileError.DeviceGone)
          break
        }
        val before = stream.sequence
        val error = safeSuspendRunCatching { read(stream.id, stream) }
          .getOrElse { MobileError.CommandFailed(it.message ?: "The log stopped") }
        failures = if (stream.sequence > before) 1 else failures + 1
        if (error != null) stream.fail(error)
        delay(retryDelay(failures))
      }
    }
  }

  private fun retryDelay(failures: Int): Duration = (1.seconds * (1 shl (failures - 1).coerceIn(0, 5))).coerceAtMost(30.seconds)

  private inner class Stream(val id: DeviceId) : LogSink {
    val state = MutableStateFlow(DeviceLogs(source = "", lines = emptyList()))

    /** Guarded by [lock]. */
    var collectors = 0
    var reader: Job? = null
    var ending: Job? = null

    /** The last line's number; only the reader writes it. */
    var sequence = 0L
      private set

    val isReading: Boolean get() = reader?.isActive == true

    override fun setSource(source: String) = state.update { it.copy(source = source) }

    override fun append(entries: List<LogEntry>) {
      if (entries.isEmpty()) return
      val lines = entries.map { DeviceLogLine(++sequence, it.time, it.level, it.tag, it.message) }
      state.update { it.copy(lines = (it.lines + lines).takeLast(capacity), error = null) }
    }

    fun clear() = state.update { it.copy(lines = emptyList()) }

    fun fail(error: MobileError) = state.update { it.copy(error = error) }

    /** Cancels the reader; [start] waits for it to finish before reading again. */
    fun stopReading() {
      reader?.cancel()
    }
  }
}

/** How many lines are kept per device. */
internal const val LogCapacity = 1000

/** How long a device's log keeps being read after nobody follows it. */
internal val LogLinger: Duration = 5.minutes
