package dev.lemonadecode.platform.presenter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember

/**
 * Mounts the presenter made by [factory] for as long as the caller stays in composition and
 * returns its Model as Compose state.
 */
@Composable
public fun <M : Any> presenterAsState(factory: () -> LemonadeCodePresenter<M>): State<M> {
  val host = remember { PresenterHost(factory()) }
  DisposableEffect(host) { onDispose { host.dispose() } }
  return host.viewState.collectAsState()
}
