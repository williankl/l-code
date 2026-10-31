package dev.loupe.platform.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [block] and captures any failure in a [LoupeResult]. Unlike `runCatching`, coroutine
 * cancellation is rethrown so structured concurrency keeps working.
 */
public suspend inline fun <T> safeSuspendRunCatching(block: suspend () -> T): LoupeResult<T, Throwable> = try {
  LoupeResult.success(block())
} catch (cancellation: CancellationException) {
  throw cancellation
} catch (error: Throwable) {
  LoupeResult.failure(error)
}

/** [safeSuspendRunCatching] on [dispatcher], for blocking work like process or file I/O. */
public suspend fun <T> safeSuspendRunCatchingOn(
  dispatcher: CoroutineDispatcher,
  block: suspend () -> T,
): LoupeResult<T, Throwable> = withContext(dispatcher) { safeSuspendRunCatching { block() } }
