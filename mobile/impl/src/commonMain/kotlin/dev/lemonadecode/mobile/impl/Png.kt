package dev.lemonadecode.mobile.impl

/** A PNG and its size in pixels. */
internal class PngPicture(val bytes: ByteArray, val width: Int, val height: Int)

/**
 * The PNG in [bytes], sized from its IHDR header. Some devices print a warning before the
 * picture, so the signature is looked for in the first bytes. Null when there is no PNG.
 */
internal fun readPng(bytes: ByteArray): PngPicture? {
  val start = (0..minOf(MaxLeadingBytes, bytes.size - HeaderSize)).firstOrNull { bytes.hasSignatureAt(it) } ?: return null
  val png = if (start == 0) bytes else bytes.copyOfRange(start, bytes.size)
  val width = png.intAt(WidthOffset)
  val height = png.intAt(WidthOffset + 4)
  if (width <= 0 || height <= 0) return null
  return PngPicture(png, width, height)
}

private fun ByteArray.hasSignatureAt(offset: Int): Boolean = Signature.indices.all { this[offset + it] == Signature[it] } &&
  Ihdr.indices.all { this[offset + IhdrOffset + it] == Ihdr[it] }

private fun ByteArray.intAt(offset: Int): Int = (this[offset].toInt() and 0xFF shl 24) or
  (this[offset + 1].toInt() and 0xFF shl 16) or
  (this[offset + 2].toInt() and 0xFF shl 8) or
  (this[offset + 3].toInt() and 0xFF)

private val Signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val Ihdr = "IHDR".encodeToByteArray()
private const val IhdrOffset = 12
private const val WidthOffset = 16

/** Signature, chunk length, `IHDR`, width and height. */
private const val HeaderSize = 24
private const val MaxLeadingBytes = 1024
