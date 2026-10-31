package dev.loupe.platform.architecture

import dev.loupe.platform.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The concurrency primitives every app graph needs, bound by the module that defines them. */
@BindingContainer
@ContributesTo(AppScope::class)
public object ConcurrencyBindings {
  @Provides
  @SingleIn(AppScope::class)
  public fun provideDispatchers(): LoupeCoroutineDispatchers = defaultCoroutineDispatchers()

  /**
   * The app-lifetime scope shared by long-lived workers (services, initializers). Never
   * cancelled; [SupervisorJob] keeps one failing child from cancelling its siblings.
   */
  @Provides
  @SingleIn(AppScope::class)
  public fun provideApplicationScope(): CoroutineScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> error.printStackTrace() },
  )
}
