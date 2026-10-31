package dev.loupe.workspace.ui.preview

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.NumberFormat

internal actual fun imageFromPixels(pixels: IntArray, width: Int, height: Int): ImageBitmap {
  // An ARGB int stored little-endian is B, G, R, A in memory.
  val bytes = ByteArray(pixels.size * 4)
  ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels)
  val bitmap = Bitmap()
  bitmap.installPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL), bytes, width * 4)
  // Immutable, so drawing shares the pixels instead of copying them every frame.
  bitmap.setImmutable()
  return bitmap.asComposeImageBitmap()
}

internal actual fun releaseImage(image: ImageBitmap) {
  image.asSkiaBitmap().close()
}

internal actual fun formatCount(count: Int): String = NumberFormat.getIntegerInstance().format(count)
