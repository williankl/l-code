package dev.lemonadecode.app

import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.zacsweers.metro.createGraph
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import java.awt.Toolkit
import java.io.File

/**
 * `lemonade-code [folder]`: opens the workspace with the sessions from last time, agents resumed, or one
 * terminal at home when there are none. A [folder] opens a terminal there as well.
 */
public fun main(args: Array<String>) {
  val graph = createGraph<AppGraph>()
  graph.initializers.forEach { initializer -> graph.applicationScope.launch { initializer.run() } }
  val folder = args.firstOrNull()?.let(::File)?.takeIf { it.isDirectory }?.absolutePath
  graph.applicationScope.launch {
    val restored = graph.sessionService.restore()
    if (restored == 0 || folder != null) {
      val _ = graph.sessionService.openTerminal(folder)
    }
  }
  // Saves the sessions however Lemonade Code ends: the window closed, ⌘Q, logging out or shutting down.
  Runtime.getRuntime().addShutdownHook(Thread { runBlocking { graph.sessionService.shutdown() } })

  nameLinuxWindowClass()
  application {
    Window(
      title = "Lemonade Code",
      icon = remember { appIcon() },
      state = rememberWindowState(size = DpSize(1440.dp, 900.dp), position = WindowPosition(Alignment.Center)),
      onCloseRequest = {
        runBlocking { graph.sessionService.shutdown() }
        exitApplication()
      },
    ) {
      graph.workspaceScreen.Content(
        onBackground = { color ->
          // A live resize uncovers new space a frame before Compose paints it; match it to the app, not white.
          val awt = java.awt.Color(color.red, color.green, color.blue)
          window.background = awt
          window.contentPane.background = awt
        },
      )
    }
  }
}

private fun appIcon(): Painter? = Thread.currentThread().contextClassLoader
  ?.getResourceAsStream("lemonade-code.png")
  ?.use { BitmapPainter(Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap()) }

/**
 * Linux docks match a window to its launcher by window class, which Java otherwise derives from
 * the main class. Needs `--add-opens java.desktop/sun.awt.X11`; without it the default stays.
 */
private fun nameLinuxWindowClass() {
  if (!System.getProperty("os.name").orEmpty().startsWith("Linux")) return
  runCatching {
    val toolkit = Toolkit.getDefaultToolkit()
    toolkit.javaClass.getDeclaredField("awtAppClassName").apply { isAccessible = true }.set(toolkit, "lemonade-code")
  }
}
