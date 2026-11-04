package dev.lemonadecode.mobile.impl

import dev.lemonadecode.domain.mobile.MobileTools
import dev.lemonadecode.domain.mobile.QrMatrix
import dev.lemonadecode.domain.mobile.ScreenPicture
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.process.BinaryProcessOutput
import dev.lemonadecode.platform.process.ProcessOutput
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.time.Duration

/**
 * Answers commands by what they say after the tool's folder, e.g. `adb devices -l` for
 * `/sdk/platform-tools/adb devices -l`; the longest answer the command starts with wins. Records
 * every command it's asked to run. Unanswered commands fail.
 */
internal class FakeDeviceCommands : DeviceCommands {
  val ran = mutableListOf<String>()

  /** How many readers of each stream are open now. */
  val openStreams = mutableMapOf<String, Int>()

  private val answers = mutableMapOf<String, () -> ProcessOutput>()
  private val pictures = mutableMapOf<String, () -> BinaryProcessOutput>()
  private val streams = mutableMapOf<String, Channel<String>>()
  private val byteStreams = mutableMapOf<String, Channel<ByteArray>>()

  fun answer(command: String, stdout: String = "", exitCode: Int = 0, stderr: String = "") = answer(command) { ProcessOutput(exitCode, stdout, stderr) }

  fun answer(command: String, output: () -> ProcessOutput) {
    answers[command] = output
  }

  fun picture(command: String, bytes: ByteArray) {
    pictures[command] = { BinaryProcessOutput(0, bytes, "") }
  }

  /** The lines a long-running [command] prints; send to it to print more. */
  fun stream(command: String): Channel<String> = streams.getOrPut(command) { Channel(Channel.UNLIMITED) }

  /** The bytes a long-running [command] writes; send to it to write more, close it to end the program. */
  fun byteStream(command: String): Channel<ByteArray> = byteStreams.getOrPut(command) { Channel(Channel.UNLIMITED) }

  fun count(prefix: String): Int = ran.count { it.startsWith(prefix) }

  override suspend fun run(command: List<String>, timeout: Duration): LemonadeCodeResult<ProcessOutput, Throwable> {
    val key = key(command)
    ran += key
    val answer = answers.entries.filter { key.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
    return LemonadeCodeResult.success(answer?.invoke() ?: ProcessOutput(1, "", "not faked: $key"))
  }

  override suspend fun runForBytes(command: List<String>, timeout: Duration): LemonadeCodeResult<BinaryProcessOutput, Throwable> {
    val key = key(command)
    ran += key
    return LemonadeCodeResult.success(pictures[key]?.invoke() ?: BinaryProcessOutput(1, ByteArray(0), "not faked: $key"))
  }

  override fun lines(command: List<String>): Flow<String> = flow {
    val key = key(command)
    ran += key
    openStreams[key] = (openStreams[key] ?: 0) + 1
    try {
      emitAll(stream(key).receiveAsFlow())
    } finally {
      openStreams[key] = openStreams.getValue(key) - 1
    }
  }

  override fun bytes(command: List<String>): Flow<ByteArray> = flow {
    val key = key(command)
    ran += key
    val stream = byteStreams[key] ?: throw CommandExitException(1, "not faked: $key")
    emitAll(stream.receiveAsFlow())
  }

  private fun key(command: List<String>): String = (listOf(command.first().substringAfterLast('/')) + command.drop(1)).joinToString(" ")
}

internal class FakeToolFinder(
  var tools: MobileTools = MobileTools(adb = "/sdk/platform-tools/adb", libimobiledevice = "/opt/homebrew/bin"),
) : MobileToolFinder {
  var finds: Int = 0
    private set

  override suspend fun find(): MobileTools {
    finds++
    return tools
  }

  override fun ideviceTool(folder: String, tool: String): String = "$folder/$tool"
}

/** Capture files the test writes into as the tool would; screenshots saved are kept. */
internal class FakeDeviceFiles : DeviceFiles {
  val written = mutableMapOf<String, ByteArray>()
  val saved = mutableListOf<Pair<String, ByteArray>>()
  private var captures = 0

  override suspend fun capturePath(): String = "/tmp/lemonade-code/captures/capture-${captures++}.png"

  override suspend fun take(path: String): ByteArray? = written.remove(path)

  override suspend fun saveScreenshot(deviceName: String, png: ByteArray): String {
    saved += deviceName to png
    return "/tmp/lemonade-code/screenshots/$deviceName.png"
  }
}

internal val FakeQrEncoder = QrEncoder { QrMatrix(1, booleanArrayOf(true)) }

/** The start of a PNG [width] by [height]: enough for its size to be read. */
internal fun png(width: Int, height: Int): ByteArray {
  fun int(value: Int) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
  val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
  return signature + int(13) + "IHDR".encodeToByteArray() + int(width) + int(height) + byteArrayOf(8, 6, 0, 0, 0) + int(0)
}

/** What [AndroidInfoScript] prints: the device's name, model, Android version, `wm size` and serial number. */
internal fun androidInfo(name: String, model: String, version: String, wmSize: String, serialNumber: String): String = listOf(name, model, version, wmSize, serialNumber).joinToString("\n$InfoMarker\n", postfix = "\n")

/**
 * Decodes each access unit that holds a picture's slice into one 2 by 1 picture whose first byte
 * counts the pictures so far, and records the units it was given.
 */
internal class FakeScreenDecoders : ScreenDecoders {
  val units = mutableListOf<ByteArray>()
  var opened = 0
  var closed = 0

  override fun h264(): ScreenDecoder {
    opened++
    return object : ScreenDecoder {
      override fun decode(accessUnit: ByteArray): List<ScreenPicture.Bgra> {
        units += accessUnit
        val pictures = units.count(::holdsSlice)
        return if (holdsSlice(accessUnit)) listOf(ScreenPicture.Bgra(ByteArray(8) { if (it == 0) pictures.toByte() else 0 }, 2, 1, 8)) else emptyList()
      }

      override fun close() {
        closed++
      }
    }
  }

  private fun holdsSlice(unit: ByteArray): Boolean = (0 until unit.size - 3).any { at ->
    unit[at] == 0.toByte() && unit[at + 1] == 0.toByte() && unit[at + 2] == 1.toByte() && (unit[at + 3].toInt() and 0x1F).let { it == 1 || it == 5 }
  }
}

/** An H.264 NAL unit of [type] after a start code, with [payload]; a slice starting a picture has its first bit set. */
internal fun nal(type: Int, vararg payload: Int): ByteArray = byteArrayOf(0, 0, 0, 1, (0x60 or type).toByte()) + payload.map(Int::toByte).toByteArray()

/** One picture's access unit as `screenrecord` writes it: a slice starting a picture, then some data. */
internal fun picture(idr: Boolean = false): ByteArray = nal(if (idr) 5 else 1, 0x88, 0x84, 0x21, 0x7F)
