package dev.loupe.workspace.ui.preview

import androidx.compose.ui.graphics.ImageBitmap

/** ARGB pixels as an image. Every pixel is either opaque or fully clear, so premultiplying changes nothing. */
internal expect fun imageFromPixels(pixels: IntArray, width: Int, height: Int): ImageBitmap

/** Frees an image from [imageFromPixels] now rather than when it's garbage collected. Nothing may draw it afterwards. */
internal expect fun releaseImage(image: ImageBitmap)

/** [count] grouped the way the user's locale writes numbers, like 1,234,567. */
internal expect fun formatCount(count: Int): String
