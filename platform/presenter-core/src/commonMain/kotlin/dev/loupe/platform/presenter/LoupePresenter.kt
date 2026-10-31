package dev.loupe.platform.presenter

import androidx.compose.runtime.Composable

/**
 * Pure UI-state producer. Implementations run inside a Molecule composition driven by a
 * [PresenterHost]: `present()` collects state with Compose's snapshot system and returns an
 * immutable Model carrying an `eventSink` for user intents.
 *
 * Composing presenters means calling `child.present()` from a parent's `present()`.
 */
public abstract class LoupePresenter<Model : Any> {
  @Composable
  public abstract fun present(): Model
}
