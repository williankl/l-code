package dev.loupe.sample

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

public fun main(): Unit = application {
  Window(
    onCloseRequest = ::exitApplication,
    title = "Loupe · UI sample",
    state = rememberWindowState(
      size = DpSize(1820.dp, 1080.dp),
      position = WindowPosition(Alignment.Center),
    ),
  ) {
    SampleApp(onAction = { println("[loupe] $it") })
  }
}
