package dev.loupe.app

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import dev.loupe.platform.core.getOrNull
import dev.zacsweers.metro.createGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Builds the real object graph, runs a login shell in a real PTY inside a fresh git repository,
 * and draws the real workspace offscreen. Writes `build/smoke/app.png` for a look.
 */
class AppGraphSmokeTest {
  @Test
  fun `the app graph runs a shell session and draws it`() = runBlocking<Unit> {
    val repo = newRepository()
    val graph = createGraph<AppGraph>()
    val id = graph.sessionService.openTerminal(repo.path).getOrNull()
    checkNotNull(id) { "the session didn't start" }
    val terminal = checkNotNull(graph.sessionTerminals.terminal(id))
    delay(1_500)
    terminal.send("echo loupe-smoke-\$((6*7))\r")
    withTimeout(10_000) {
      while (!terminal.screen().contains("loupe-smoke-42")) delay(50)
    }
    assertTrue(graph.sessionService.sessions.value.single().branch == "feature")

    ImageComposeScene(width = 2880, height = 1800, density = Density(2f)) {
      graph.workspaceScreen.Content()
    }.use { scene ->
      var time = 0L
      repeat(60) {
        scene.render(time)
        time += 16_000_000L
        Thread.sleep(10)
      }
      val png = requireNotNull(scene.render(time).encodeToData(EncodedImageFormat.PNG)).bytes
      File("build/smoke").apply { mkdirs() }.resolve("app.png").writeBytes(png)
    }
    graph.sessionService.close(id)
  }

  private fun dev.loupe.platform.terminal.TerminalSession.screen(): String {
    textBuffer.lock()
    return try {
      textBuffer.getScreenLines()
    } finally {
      textBuffer.unlock()
    }
  }

  private fun newRepository(): File {
    val repo = Files.createTempDirectory("loupe-smoke").toFile()
    fun run(vararg args: String) = check(ProcessBuilder(*args).directory(repo).start().waitFor() == 0) { args.joinToString(" ") }
    run("git", "init", "-q", "-b", "main")
    run("git", "config", "user.email", "smoke@loupe.dev")
    run("git", "config", "user.name", "Loupe Smoke")
    run("git", "config", "commit.gpgsign", "false")
    File(repo, "app.kt").writeText("fun main() {\n  println(\"hi\")\n}\n")
    run("git", "add", ".")
    run("git", "commit", "-q", "-m", "init")
    run("git", "checkout", "-q", "-b", "feature")
    File(repo, "app.kt").writeText("fun main() {\n  println(\"hello, loupe\")\n}\n")
    return repo
  }
}
