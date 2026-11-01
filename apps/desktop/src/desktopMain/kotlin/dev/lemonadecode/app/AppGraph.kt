package dev.lemonadecode.app

import dev.lemonadecode.platform.architecture.AppInitializer
import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.sessions.api.SessionService
import dev.lemonadecode.sessions.api.SessionTerminals
import dev.lemonadecode.workspace.impl.WorkspaceGraph
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope

/**
 * The application's object graph. Every module contributes its bindings to [AppScope]; this is
 * the one place they are put together.
 */
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
internal interface AppGraph : WorkspaceGraph {
  val initializers: Set<AppInitializer>
  val sessionTerminals: SessionTerminals
  val applicationScope: CoroutineScope
  val sessionService: SessionService
  val appExit: DesktopAppExit
}
