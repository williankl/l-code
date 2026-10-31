package dev.loupe.platform.architecture

import kotlinx.coroutines.CoroutineDispatcher

public abstract class LoupeCoroutineDispatchers {
  public abstract val io: CoroutineDispatcher
  public abstract val main: CoroutineDispatcher
  public abstract val default: CoroutineDispatcher
}

public expect fun defaultCoroutineDispatchers(): LoupeCoroutineDispatchers
