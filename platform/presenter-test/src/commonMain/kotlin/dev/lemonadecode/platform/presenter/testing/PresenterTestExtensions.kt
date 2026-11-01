package dev.lemonadecode.platform.presenter.testing

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import dev.lemonadecode.platform.presenter.LemonadeCodePresenter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Cold flow of this presenter's Models, driven by Molecule in immediate mode. */
public fun <Model : Any> LemonadeCodePresenter<Model>.testIn(): Flow<Model> = moleculeFlow(mode = RecompositionMode.Immediate) { present() }

/**
 * Runs [validate] against this presenter inside a Turbine block and ignores whatever is left
 * at the end, so transient recompositions never fail a test.
 */
public suspend fun <Model : Any> LemonadeCodePresenter<Model>.test(
  validate: suspend ReceiveTurbine<Model>.() -> Unit,
) {
  testIn().test {
    validate()
    cancelAndIgnoreRemainingEvents()
  }
}

/** Suspends until an emitted item matches [predicate], within one [timeout] budget. */
@IgnorableReturnValue
public suspend fun <T> ReceiveTurbine<T>.awaitItemWhere(
  timeout: Duration = 3.seconds,
  predicate: (T) -> Boolean,
): T = withTimeout(timeout) {
  var item = awaitItem()
  while (!predicate(item)) item = awaitItem()
  item
}

/** Yields until [condition] holds, for side effects that don't emit a new Model. */
public suspend fun yieldUntil(
  timeout: Duration = 3.seconds,
  condition: () -> Boolean,
): Unit = withTimeout(timeout) {
  while (!condition()) yield()
}
