package dev.loupe.platform.presenter

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing

public actual fun presenterDispatcher(): CoroutineDispatcher = Dispatchers.Swing
