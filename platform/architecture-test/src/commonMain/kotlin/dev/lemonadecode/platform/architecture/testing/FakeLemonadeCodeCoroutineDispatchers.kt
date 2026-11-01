package dev.lemonadecode.platform.architecture.testing

import dev.lemonadecode.platform.architecture.LemonadeCodeCoroutineDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@OptIn(ExperimentalCoroutinesApi::class)
/** Every dispatcher is [dispatcher], so tests run deterministically on the test scheduler. */
public class FakeLemonadeCodeCoroutineDispatchers(
  dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
) : LemonadeCodeCoroutineDispatchers() {
  override val io: CoroutineDispatcher = dispatcher
  override val main: CoroutineDispatcher = dispatcher
  override val default: CoroutineDispatcher = dispatcher
}
