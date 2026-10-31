package dev.loupe.platform.presenter

import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

/**
 * Runs a [LoupePresenter] with Molecule on its own scope and exposes the Model as a
 * [StateFlow]. The scope lives until [dispose]; recompositions of the UI don't restart it.
 */
public class PresenterHost<M : Any>(
  private val presenter: LoupePresenter<M>,
  dispatcher: CoroutineDispatcher = presenterDispatcher(),
) {
  private val scope: CoroutineScope = CoroutineScope(
    SupervisorJob() + dispatcher + CoroutineName(presenter::class.simpleName ?: "Presenter"),
  )

  public val viewState: StateFlow<M> = scope.launchMolecule(mode = RecompositionMode.Immediate) { presenter.present() }

  public fun dispose() {
    scope.cancel()
  }
}

/** The UI thread on this platform. */
public expect fun presenterDispatcher(): CoroutineDispatcher
