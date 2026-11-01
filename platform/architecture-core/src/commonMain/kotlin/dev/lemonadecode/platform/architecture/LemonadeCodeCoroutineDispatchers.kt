package dev.lemonadecode.platform.architecture

import kotlinx.coroutines.CoroutineDispatcher

public abstract class LemonadeCodeCoroutineDispatchers {
  public abstract val io: CoroutineDispatcher
  public abstract val main: CoroutineDispatcher
  public abstract val default: CoroutineDispatcher
}

public expect fun defaultCoroutineDispatchers(): LemonadeCodeCoroutineDispatchers
