package dev.loupe.app

import dev.loupe.platform.architecture.AppInitializer
import dev.loupe.platform.di.AppScope
import dev.loupe.sessions.api.SessionService
import dev.loupe.sessions.api.SessionTerminals
import dev.loupe.workspace.impl.WorkspaceGraph
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
}
