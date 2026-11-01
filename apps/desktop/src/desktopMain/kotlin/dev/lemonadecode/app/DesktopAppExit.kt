package dev.lemonadecode.app

import dev.lemonadecode.platform.architecture.AppExit
import dev.lemonadecode.platform.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ExposeImplBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Asks the window to close; `main` watches [requested] and quits the way closing the window does. */
@ContributesBinding(AppScope::class)
@ExposeImplBinding
@SingleIn(AppScope::class)
@Inject
internal class DesktopAppExit : AppExit {
  private val mutableRequested = MutableStateFlow(false)
  val requested: StateFlow<Boolean> = mutableRequested.asStateFlow()

  override fun exit() {
    mutableRequested.value = true
  }
}
