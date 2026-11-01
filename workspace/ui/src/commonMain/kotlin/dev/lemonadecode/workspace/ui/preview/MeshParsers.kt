package dev.lemonadecode.workspace.ui.preview

import kotlin.math.min
import kotlin.math.pow

// STL

/**
 * Binary STL is an 80-byte header, a triangle count and 50 bytes per triangle. ASCII STL starts with
 * `solid`, but so do some binary files, so the size has to match the count before a file counts as binary.
 */
internal fun parseStl(bytes: ByteArray): Mesh {
  val declared = if (bytes.size >= 84) bytes.uint32Le(80) else -1L
  if (declared >= 0 && 84 + 50 * declared == bytes.size.toLong()) return parseBinaryStl(bytes, declared.toInt())
  if (startsWithWord(bytes, "solid")) {
    val ascii = parseAsciiStl(bytes)
    if (ascii.triangleCount > 0) return ascii
  }
  if (bytes.size < 84) throw MeshFormatException("too short for an STL file")
  // A binary file whose count doesn't match its size: read the triangles that are there.
  return parseBinaryStl(bytes, min(declared, (bytes.size - 84) / 50L).toInt())
}

private fun parseBinaryStl(bytes: ByteArray, count: Int): Mesh {
  val positions = FloatArray(count * 9)
  for (triangle in 0 until count) {
    // Skip the stored normal (12 bytes); the attribute word (2 bytes) follows the corners.
    val offset = 84 + triangle * 50 + 12
    for (i in 0 until 9) positions[triangle * 9 + i] = bytes.floatLe(offset + i * 4)
  }
  return Mesh(positions, IntArray(count * 3) { it })
}

private fun parseAsciiStl(bytes: ByteArray): Mesh {
  val positions = FloatBuilder()
  val indices = IntBuilder()
  val corners = IntBuilder(8)
  val scanner = TextScanner(bytes)
  fun closeLoop() {
    indices.addFan(corners)
    corners.clear()
  }
  while (scanner.next()) {
    when {
      scanner.tokenIs("vertex") -> {
        corners.add(positions.size / 3)
        repeat(3) {
          if (!scanner.next()) throw MeshFormatException("the file ends early")
          positions.add(scanner.float())
        }
      }

      scanner.tokenIs("endloop") || scanner.tokenIs("endfacet") || scanner.tokenIs("facet") || scanner.tokenIs("endsolid") -> closeLoop()
    }
  }
  closeLoop()
  return Mesh(positions.toArray(), indices.toArray())
}

private fun startsWithWord(bytes: ByteArray, word: String): Boolean {
  var i = 0
  while (i < bytes.size && isSpace(bytes[i])) i++
  if (bytes.size - i < word.length) return false
  for (c in word.indices) {
    if (bytes[i + c].toInt().toChar().lowercaseChar() != word[c]) return false
  }
  return i + word.length == bytes.size || isSpace(bytes[i + word.length])
}

// OBJ

/** Wavefront OBJ: `v x y z` and `f` lines; polygons become fans, `v/vt/vn` keeps the `v`, negative indices count back. */
internal fun parseObj(bytes: ByteArray): Mesh {
  val positions = FloatBuilder()
  val indices = IntBuilder()
  val corners = IntBuilder(8)
  val scanner = TextScanner(bytes)
  do {
    if (!scanner.nextOnLine()) continue
    if (scanner.tokenIs("v")) {
      repeat(3) {
        if (!scanner.nextOnLine()) throw MeshFormatException("a vertex has fewer than three coordinates")
        positions.add(scanner.float())
      }
    } else if (scanner.tokenIs("f")) {
      val defined = positions.size / 3
      corners.clear()
      while (scanner.nextOnLine()) {
        val index = scanner.leadingInt()
        corners.add(
          when {
            index > 0 -> index - 1
            index < 0 -> defined + index
            else -> throw MeshFormatException("a face uses vertex 0")
          },
        )
      }
      indices.addFan(corners)
    }
  } while (scanner.nextLine())
  return Mesh(positions.toArray(), indices.toArray())
}

// PLY

private enum class PlyType(val size: Int) {
  Int8(1),
  UInt8(1),
  Int16(2),
  UInt16(2),
  Int32(4),
  UInt32(4),
  Float32(4),
  Float64(8),
  ;

  companion object {
    fun of(name: String): PlyType = when (name) {
      "char", "int8" -> Int8
      "uchar", "uint8" -> UInt8
      "short", "int16" -> Int16
      "ushort", "uint16" -> UInt16
      "int", "int32" -> Int32
      "uint", "uint32" -> UInt32
      "float", "float32" -> Float32
      "double", "float64" -> Float64
      else -> throw MeshFormatException("unknown PLY type “$name”")
    }
  }
}

/** A scalar property, or a list when [countType] is set. */
private class PlyProperty(val name: String, val type: PlyType, val countType: PlyType?)

private class PlyElement(val name: String, val count: Int) {
  val properties = mutableListOf<PlyProperty>()
}

/** Stanford PLY, ASCII or binary of either byte order: `x`, `y`, `z` of each vertex and each face's index list. */
internal fun parsePly(bytes: ByteArray): Mesh {
  var position = 0
  fun nextHeaderLine(): String? {
    if (position >= bytes.size) return null
    var end = position
    while (end < bytes.size && bytes[end] != NewLine) end++
    val line = bytes.decodeToString(position, end).trim()
    position = min(end + 1, bytes.size)
    return line
  }

  if (nextHeaderLine() != "ply") throw MeshFormatException("not a PLY file")
  var format: String? = null
  val elements = mutableListOf<PlyElement>()
  while (true) {
    val line = nextHeaderLine() ?: throw MeshFormatException("the PLY header never ends")
    val words = line.split(' ', '\t').filter { it.isNotEmpty() }
    fun badLine(): Nothing = throw MeshFormatException("bad PLY header line “$line”")
    when (words.firstOrNull()) {
      "format" -> format = words.getOrNull(1) ?: badLine()

      "element" -> elements += PlyElement(
        name = words.getOrNull(1) ?: badLine(),
        count = words.getOrNull(2)?.toIntOrNull()?.takeIf { it >= 0 } ?: badLine(),
      )

      "property" -> {
        val element = elements.lastOrNull() ?: badLine()
        element.properties += if (words.getOrNull(1) == "list") {
          if (words.size < 5) badLine()
          PlyProperty(name = words[4], type = PlyType.of(words[3]), countType = PlyType.of(words[2]))
        } else {
          if (words.size < 3) badLine()
          PlyProperty(name = words[2], type = PlyType.of(words[1]), countType = null)
        }
      }

      "end_header" -> break
    }
  }

  val reader = when (format) {
    "ascii" -> PlyAsciiReader(TextScanner(bytes, position))
    "binary_little_endian" -> PlyBinaryReader(bytes, position, littleEndian = true)
    "binary_big_endian" -> PlyBinaryReader(bytes, position, littleEndian = false)
    null -> throw MeshFormatException("the PLY header has no format")
    else -> throw MeshFormatException("unknown PLY format “$format”")
  }

  var positions: FloatArray? = null
  val indices = IntBuilder()
  for (element in elements) {
    reader.ensureRoom(element)
    when (element.name) {
      "vertex" -> positions = readPlyVertices(element, reader)
      "face" -> readPlyFaces(element, reader, indices)
      else -> repeat(element.count) { element.properties.forEach(reader::skip) }
    }
  }
  return Mesh(positions ?: throw MeshFormatException("no vertices"), indices.toArray())
}

private fun readPlyVertices(element: PlyElement, reader: PlyReader): FloatArray {
  fun axis(name: String): Int = element.properties.indexOfFirst { it.name == name && it.countType == null }
    .takeIf { it >= 0 } ?: throw MeshFormatException("PLY vertices have no $name")
  val x = axis("x")
  val y = axis("y")
  val z = axis("z")
  val positions = FloatArray(element.count * 3)
  for (vertex in 0 until element.count) {
    element.properties.forEachIndexed { index, property ->
      when (index) {
        x -> positions[vertex * 3] = reader.read(property.type).toFloat()
        y -> positions[vertex * 3 + 1] = reader.read(property.type).toFloat()
        z -> positions[vertex * 3 + 2] = reader.read(property.type).toFloat()
        else -> reader.skip(property)
      }
    }
  }
  return positions
}

private fun readPlyFaces(element: PlyElement, reader: PlyReader, indices: IntBuilder) {
  val lists = element.properties.filter { it.countType != null }
  val list = lists.firstOrNull { it.name == "vertex_indices" || it.name == "vertex_index" } ?: lists.firstOrNull()
    ?: throw MeshFormatException("PLY faces have no vertex list")
  val corners = IntBuilder(8)
  repeat(element.count) {
    for (property in element.properties) {
      if (property !== list) {
        reader.skip(property)
        continue
      }
      val count = reader.read(property.countType!!).toInt()
      corners.clear()
      repeat(count) { corners.add(reader.read(property.type).toLong().toInt()) }
      indices.addFan(corners)
    }
  }
}

private abstract class PlyReader {
  abstract fun read(type: PlyType): Double

  /** Fails early when [element] can't fit in what's left, before allocating for it. */
  abstract fun ensureRoom(element: PlyElement)

  open fun skip(property: PlyProperty) {
    val countType = property.countType
    if (countType == null) {
      read(property.type)
    } else {
      repeat(read(countType).toInt()) { read(property.type) }
    }
  }
}

private class PlyAsciiReader(private val scanner: TextScanner) : PlyReader() {
  override fun read(type: PlyType): Double {
    if (!scanner.next()) throw MeshFormatException("the file ends early")
    return scanner.double()
  }

  override fun ensureRoom(element: PlyElement) {
    // Every value takes at least a digit and a separator.
    if (element.count.toLong() * element.properties.size * 2 > scanner.remaining) throw MeshFormatException("the file ends early")
  }
}

private class PlyBinaryReader(
  private val bytes: ByteArray,
  private var position: Int,
  private val littleEndian: Boolean,
) : PlyReader() {
  override fun read(type: PlyType): Double {
    val p = position
    if (p + type.size > bytes.size) throw MeshFormatException("the file ends early")
    position += type.size
    return when (type) {
      PlyType.Int8 -> bytes[p].toDouble()
      PlyType.UInt8 -> (bytes[p].toInt() and 0xFF).toDouble()
      PlyType.Int16 -> int16(p).toShort().toDouble()
      PlyType.UInt16 -> int16(p).toDouble()
      PlyType.Int32 -> int32(p).toDouble()
      PlyType.UInt32 -> (int32(p).toLong() and 0xFFFFFFFFL).toDouble()
      PlyType.Float32 -> Float.fromBits(int32(p)).toDouble()
      PlyType.Float64 -> Double.fromBits(int64(p))
    }
  }

  override fun ensureRoom(element: PlyElement) {
    val rowBytes = element.properties.sumOf { (it.countType ?: it.type).size.toLong() }
    if (element.count * rowBytes > bytes.size - position) throw MeshFormatException("the file ends early")
  }

  override fun skip(property: PlyProperty) {
    if (property.countType == null) {
      if (position + property.type.size > bytes.size) throw MeshFormatException("the file ends early")
      position += property.type.size
    } else {
      super.skip(property)
    }
  }

  private fun byte(offset: Int): Int = bytes[offset].toInt() and 0xFF

  private fun int16(p: Int): Int = if (littleEndian) byte(p) or (byte(p + 1) shl 8) else (byte(p) shl 8) or byte(p + 1)

  private fun int32(p: Int): Int = if (littleEndian) {
    byte(p) or (byte(p + 1) shl 8) or (byte(p + 2) shl 16) or (byte(p + 3) shl 24)
  } else {
    (byte(p) shl 24) or (byte(p + 1) shl 16) or (byte(p + 2) shl 8) or byte(p + 3)
  }

  private fun int64(p: Int): Long {
    val first = int32(p).toLong() and 0xFFFFFFFFL
    val second = int32(p + 4).toLong() and 0xFFFFFFFFL
    return if (littleEndian) (second shl 32) or first else (first shl 32) or second
  }
}

// Text

private fun isSpace(byte: Byte): Boolean = byte == Space || (byte >= Tab && byte <= CarriageReturn)

/** Walks whitespace-separated tokens of ASCII text without making a string per token. */
internal class TextScanner(private val bytes: ByteArray, private var position: Int = 0) {
  private var start = 0
  private var end = 0

  val remaining: Int get() = bytes.size - position

  /** Moves to the next token, across lines. False at the end of the text. */
  fun next(): Boolean {
    while (position < bytes.size && isSpace(bytes[position])) position++
    return take()
  }

  /** Moves to the next token on the current line. False at the end of the line. */
  fun nextOnLine(): Boolean {
    while (position < bytes.size && bytes[position] != NewLine && isSpace(bytes[position])) position++
    if (position >= bytes.size || bytes[position] == NewLine) return false
    return take()
  }

  /** Moves past the end of the current line. False when no text follows. */
  fun nextLine(): Boolean {
    while (position < bytes.size && bytes[position] != NewLine) position++
    if (position < bytes.size) position++
    return position < bytes.size
  }

  private fun take(): Boolean {
    if (position >= bytes.size) return false
    start = position
    while (position < bytes.size && !isSpace(bytes[position])) position++
    end = position
    return true
  }

  /** Whether the token is [word], ignoring case. */
  fun tokenIs(word: String): Boolean {
    if (end - start != word.length) return false
    for (i in word.indices) {
      if (bytes[start + i].toInt().toChar().lowercaseChar() != word[i]) return false
    }
    return true
  }

  fun float(): Float = double().toFloat()

  fun double(): Double = parseDecimal(bytes, start, end) ?: bytes.decodeToString(start, end).toDoubleOrNull()
    ?: throw MeshFormatException("“${bytes.decodeToString(start, min(end, start + 24))}” isn’t a number")

  /** The integer at the start of the token, up to a `/` as in OBJ's `7/1/3`. */
  fun leadingInt(): Int {
    var i = start
    val negative = i < end && bytes[i] == Minus
    if (i < end && (negative || bytes[i] == Plus)) i++
    var value = 0L
    val digitsStart = i
    while (i < end) {
      val d = bytes[i] - Zero
      if (d < 0 || d > 9) break
      value = min(value * 10 + d, Int.MAX_VALUE.toLong())
      i++
    }
    if (i == digitsStart || (i < end && bytes[i] != Slash)) {
      throw MeshFormatException("“${bytes.decodeToString(start, min(end, start + 24))}” isn’t a vertex index")
    }
    return if (negative) -value.toInt() else value.toInt()
  }
}

/** Plain decimals like `-1.5e3` read without allocating; null for anything else (`nan`, `inf`, junk). */
private fun parseDecimal(bytes: ByteArray, start: Int, end: Int): Double? {
  var i = start
  val negative = i < end && bytes[i] == Minus
  if (i < end && (negative || bytes[i] == Plus)) i++
  // Up to 18 significant digits fit a Long; later ones only move the exponent.
  var mantissa = 0L
  var significant = 0
  var exponent = 0
  var digits = 0
  while (i < end) {
    val d = bytes[i] - Zero
    if (d < 0 || d > 9) break
    if (significant < 18) {
      mantissa = mantissa * 10 + d
      if (mantissa != 0L) significant++
    } else {
      exponent++
    }
    digits++
    i++
  }
  if (i < end && bytes[i] == Dot) {
    i++
    while (i < end) {
      val d = bytes[i] - Zero
      if (d < 0 || d > 9) break
      if (significant < 18) {
        mantissa = mantissa * 10 + d
        if (mantissa != 0L) significant++
        exponent--
      }
      digits++
      i++
    }
  }
  if (digits == 0) return null
  if (i < end && (bytes[i] == 'e'.code.toByte() || bytes[i] == 'E'.code.toByte())) {
    i++
    val negativeExponent = i < end && bytes[i] == Minus
    if (i < end && (negativeExponent || bytes[i] == Plus)) i++
    var value = 0
    val exponentStart = i
    while (i < end) {
      val d = bytes[i] - Zero
      if (d < 0 || d > 9) break
      value = min(value * 10 + d, 9999)
      i++
    }
    if (i == exponentStart) return null
    exponent += if (negativeExponent) -value else value
  }
  if (i != end) return null
  var value = mantissa.toDouble()
  if (exponent > 0) {
    value *= 10.0.pow(exponent)
  } else if (exponent < 0) {
    value /= 10.0.pow(-exponent)
  }
  return if (negative) -value else value
}

private const val NewLine: Byte = 10
private const val Zero: Byte = 48
private const val Minus: Byte = 45
private const val Plus: Byte = 43
private const val Dot: Byte = 46
private const val Slash: Byte = 47
private const val Space: Byte = 32
private const val Tab: Byte = 9
private const val CarriageReturn: Byte = 13
