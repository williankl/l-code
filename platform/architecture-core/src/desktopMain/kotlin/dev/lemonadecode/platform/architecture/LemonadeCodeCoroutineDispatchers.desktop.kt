package dev.lemonadecode.platform.architecture

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing

public actual fun defaultCoroutineDispatchers(): LemonadeCodeCoroutineDispatchers = DesktopCoroutineDispatchers

private object DesktopCoroutineDispatchers : LemonadeCodeCoroutineDispatchers() {
  override val io: CoroutineDispatcher = Dispatchers.IO
  override val main: CoroutineDispatcher = Dispatchers.Swing
  override val default: CoroutineDispatcher = Dispatchers.Default
}
