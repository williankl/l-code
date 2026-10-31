package dev.loupe.platform.architecture.testing

import dev.loupe.platform.architecture.LoupeCoroutineDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@OptIn(ExperimentalCoroutinesApi::class)
/** Every dispatcher is [dispatcher], so tests run deterministically on the test scheduler. */
public class FakeLoupeCoroutineDispatchers(
  dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
) : LoupeCoroutineDispatchers() {
  override val io: CoroutineDispatcher = dispatcher
  override val main: CoroutineDispatcher = dispatcher
  override val default: CoroutineDispatcher = dispatcher
}
