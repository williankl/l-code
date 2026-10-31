package dev.loupe.platform.architecture

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing

public actual fun defaultCoroutineDispatchers(): LoupeCoroutineDispatchers = DesktopCoroutineDispatchers

private object DesktopCoroutineDispatchers : LoupeCoroutineDispatchers() {
  override val io: CoroutineDispatcher = Dispatchers.IO
  override val main: CoroutineDispatcher = Dispatchers.Swing
  override val default: CoroutineDispatcher = Dispatchers.Default
}
