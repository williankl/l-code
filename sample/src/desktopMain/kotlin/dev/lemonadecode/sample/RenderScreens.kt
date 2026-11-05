package dev.lemonadecode.sample

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.platform.designsystem.LemonadeCodeThemeMode
import dev.lemonadecode.workspace.impl.showcase.WorkspaceShowcase
import dev.lemonadecode.workspace.impl.showcase.WorkspaceShowcaseContent
import dev.lemonadecode.workspace.ui.toThemeMode
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

private const val Scale = 2f
private const val FrameNanos = 16_000_000L

/**
 * Renders every sample screen to a PNG at 2x, without opening a window, so the UI can be compared
 * with the design frames side by side. Pass the output folder as the first argument and
 * optionally `light` or `dark` as the second to force a theme.
 */
public fun main(args: Array<String>) {
  val out = File(args.firstOrNull() ?: "build/screens").apply { mkdirs() }
  val forced = when (args.getOrNull(1)) {
    "light" -> LemonadeCodeThemeMode.Light
    "dark" -> LemonadeCodeThemeMode.Dark
    else -> null
  }
  ImageComposeScene(width = (1820 * Scale).toInt(), height = (1080 * Scale).toInt(), density = Density(Scale)) {
    SampleApp()
  }.use { scene ->
    var time = 0L
    repeat(30) {
      scene.render(time)
      time += FrameNanos
    }
    val png = requireNotNull(scene.render(time).encodeToData(EncodedImageFormat.PNG)).bytes
    File(out, "00-SampleApp.png").writeBytes(png)
  }
  WorkspaceShowcase.entries.forEach { sample ->
    ImageComposeScene(
      width = (sample.widthDp * Scale).toInt(),
      height = (sample.heightDp * Scale).toInt(),
      density = Density(Scale),
    ) {
      // Still pictures: everything at rest from the first frame.
      LemonadeCodeTheme(mode = forced ?: sample.model().settings.theme.toThemeMode(), animations = false) {
        WorkspaceShowcaseContent(sample)
      }
    }.use { scene ->
      var time = 0L
      repeat(30) {
        scene.render(time)
        time += FrameNanos
      }
      val image = scene.render(time)
      val png = requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
      val suffix = forced?.name?.lowercase()?.let { "-$it" }.orEmpty()
      val file = File(out, "%02d-%s%s.png".format(sample.ordinal + 1, sample.name, suffix))
      file.writeBytes(png)
      println("wrote ${file.path}")
    }
  }
}
