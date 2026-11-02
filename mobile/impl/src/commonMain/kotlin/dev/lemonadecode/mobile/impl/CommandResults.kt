package dev.lemonadecode.mobile.impl

import dev.lemonadecode.domain.mobile.MobileError
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.core.safeSuspendRunCatching
import dev.lemonadecode.platform.process.ProcessOutput
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn

/** Everything a command printed, standard output first. */
internal val ProcessOutput.text: String get() = (stdout + "\n" + stderr).trim()

/** Success when the command ran and exited with 0; otherwise what it printed, as an error. */
internal fun LemonadeCodeResult<ProcessOutput, Throwable>.toUnitResult(): LemonadeCodeResult<Unit, MobileError> = when (this) {
  is LemonadeCodeResult.Success -> if (value.isSuccess) LemonadeCodeResult.success(Unit) else LemonadeCodeResult.failure(value.toError())
  is LemonadeCodeResult.Failure -> LemonadeCodeResult.failure(error.toCommandFailed())
}

/** A failed command as an error: [MobileError.DeviceGone] when adb no longer knows the device. */
internal fun ProcessOutput.toError(): MobileError {
  val printed = stderr.trim().ifEmpty { stdout.trim() }
  val text = printed.lowercase()
  return when {
    "device" in text && "not found" in text -> MobileError.DeviceGone
    else -> MobileError.CommandFailed(printed.take(MaxMessage))
  }
}

internal fun Throwable.toCommandFailed(): MobileError = MobileError.CommandFailed(message?.take(MaxMessage) ?: "The command couldn't run")

/** Saves a screenshot, turning a failure into an error. */
internal suspend fun DeviceFiles.save(deviceName: String, png: ByteArray): LemonadeCodeResult<String, MobileError> = when (val saved = safeSuspendRunCatching { saveScreenshot(deviceName, png) }) {
  is LemonadeCodeResult.Success -> saved
  is LemonadeCodeResult.Failure -> LemonadeCodeResult.failure(saved.error.toCommandFailed())
}

/** Groups the lines that have already arrived, so a burst of them updates the log once. */
@OptIn(FlowPreview::class)
internal fun Flow<String>.batched(max: Int = 500): Flow<List<String>> = flow {
  coroutineScope {
    val lines = buffer(LineBuffer).produceIn(this)
    while (true) {
      val first = lines.receiveCatching()
      if (first.isClosed) {
        first.exceptionOrNull()?.let { throw it }
        break
      }
      val batch = mutableListOf(first.getOrThrow())
      while (batch.size < max) batch += lines.tryReceive().getOrNull() ?: break
      emit(batch)
    }
  }
}

private const val LineBuffer = 4096
private const val MaxMessage = 500
