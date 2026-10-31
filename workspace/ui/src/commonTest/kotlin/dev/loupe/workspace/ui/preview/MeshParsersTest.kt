package dev.loupe.workspace.ui.preview

import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MeshParsersTest {
  // STL

  @Test
  fun `reads ascii stl`() {
    val text = """
      solid square
        facet normal 0 0 1
          outer loop
            vertex 0 0 0
            vertex 1.5e+00 0 0
            vertex 0 1 0
          endloop
        endfacet
        facet normal 0 0 1
          outer loop
            vertex 1.5 0 0
            vertex 1.5 1 -2E-1
            vertex 0 1 0
          endloop
        endfacet
      endsolid square
    """.trimIndent()
    val mesh = parseMesh(text.encodeToByteArray(), "stl")
    assertEquals(2, mesh.triangleCount)
    assertContentEquals(floatArrayOf(0f, 0f, 0f, 1.5f, 0f, 0f, 0f, 1f, 0f), mesh.positions.copyOfRange(0, 9))
    assertEquals(-0.2f, mesh.positions[14])
  }

  @Test
  fun `reads binary stl even when its header starts with solid`() {
    val bytes = binaryStl(header = "solid but binary", triangles = listOf(Square[0], Square[1]))
    val mesh = parseMesh(bytes, "stl")
    assertEquals(2, mesh.triangleCount)
    assertContentEquals(Square[0] + Square[1], mesh.positions)
    assertContentEquals(intArrayOf(0, 1, 2, 3, 4, 5), mesh.indices)
  }

  @Test
  fun `reads the triangles a binary stl has when its count is wrong`() {
    val bytes = binaryStl(header = "exporter", triangles = listOf(Square[0], Square[1]), declaredCount = 7)
    assertEquals(2, parseMesh(bytes, "stl").triangleCount)
  }

  @Test
  fun `a short file is not an stl`() {
    assertFailsWith<MeshFormatException> { parseMesh(byteArrayOf(1, 2, 3), "stl") }
  }

  // OBJ

  @Test
  fun `reads obj polygons with slashes and negative indices`() {
    val text = """
      # a square and a triangle
      o square
      v 0 0 0
      v 1 0 0
      v 1 1 0
      v 0 1 0 1.0
      vt 0 0
      vn 0 0 1
      usemtl none
      f 1/1/1 2/1/1 3/1/1 4/1/1
      f -4//1 -3//1 -1//1
      l 1 2
    """.trimIndent()
    val mesh = parseMesh(text.encodeToByteArray(), "obj")
    assertEquals(4, mesh.vertexCount)
    assertContentEquals(intArrayOf(0, 1, 2, 0, 2, 3, 0, 1, 3), mesh.indices)
    assertContentEquals(floatArrayOf(0f, 1f, 0f), mesh.positions.copyOfRange(9, 12))
  }

  @Test
  fun `obj faces must point at vertices that exist`() {
    assertFailsWith<MeshFormatException> { parseMesh("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 9\n".encodeToByteArray(), "obj") }
    assertFailsWith<MeshFormatException> { parseMesh("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 0 1 2\n".encodeToByteArray(), "obj") }
  }

  @Test
  fun `obj with crlf line endings`() {
    val mesh = parseMesh("v 0 0 0\r\nv 1 0 0\r\nv 0 1 0\r\nf 1 2 3\r\n".encodeToByteArray(), "obj")
    assertEquals(1, mesh.triangleCount)
  }

  // PLY

  @Test
  fun `reads ascii ply and skips properties and elements it doesn't use`() {
    val text = """
      ply
      format ascii 1.0
      comment made by hand
      element vertex 4
      property float x
      property uchar red
      property float y
      property float z
      property list uchar float extra
      element face 1
      property uchar flags
      property list uchar int vertex_indices
      element edge 1
      property int vertex1
      property int vertex2
      end_header
      0 255 0 0 2 9 9
      1 255 0 0 0
      1 255 1 0 1 7
      0 255 1 0 0
      3 4 0 1 2 3
      0 1
    """.trimIndent()
    val mesh = parseMesh(text.encodeToByteArray(), "ply")
    assertContentEquals(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f), mesh.positions)
    assertContentEquals(intArrayOf(0, 1, 2, 0, 2, 3), mesh.indices)
  }

  @Test
  fun `reads binary ply in both byte orders`() {
    for (littleEndian in listOf(true, false)) {
      val header = """
        ply
        format ${if (littleEndian) "binary_little_endian" else "binary_big_endian"} 1.0
        element material 1
        property list uchar ushort names
        property double shine
        element vertex 3
        property double x
        property float y
        property short z
        property list uchar uchar extra
        element face 1
        property list uchar uint vertex_indices
        property float quality
        end_header
      """.trimIndent() + "\n"
      val body = Bytes(littleEndian).apply {
        // material: a list of two ushorts and a double
        byte(2)
        int16(7)
        int16(8)
        double(0.5)
        // vertices
        double(0.0)
        float(0f)
        int16(-3)
        byte(0)
        double(2.0)
        float(0f)
        int16(0)
        byte(1)
        byte(42)
        double(0.0)
        float(1f)
        int16(0)
        byte(0)
        // face
        byte(3)
        int32(0)
        int32(1)
        int32(2)
        float(1f)
      }
      val mesh = parseMesh(header.encodeToByteArray() + body.toByteArray(), "ply")
      assertContentEquals(floatArrayOf(0f, 0f, -3f, 2f, 0f, 0f, 0f, 1f, 0f), mesh.positions, "littleEndian=$littleEndian")
      assertContentEquals(intArrayOf(0, 1, 2), mesh.indices)
    }
  }

  @Test
  fun `a ply that ends early is unreadable`() {
    val text = "ply\nformat binary_little_endian 1.0\nelement vertex 1000\nproperty float x\nproperty float y\nproperty float z\nend_header\n"
    assertFailsWith<MeshFormatException> { parseMesh(text.encodeToByteArray() + ByteArray(12), "ply") }
    assertFailsWith<MeshFormatException> { parseMesh("hello".encodeToByteArray(), "ply") }
  }

  // glTF

  @Test
  fun `reads glb with indices and node transforms`() {
    val bin = Bytes(littleEndian = true).apply {
      listOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f).forEach(::float)
      int16(0)
      int16(1)
      int16(2)
      int16(0) // pad to four bytes
    }.toByteArray()
    // The root translates and scales; its child also turns 90° about Z.
    val half = sqrt(0.5f)
    val json = """
      {
        "asset": {"version": "2.0"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [
          {"mesh": 0, "translation": [10, 0, 0], "scale": [2, 2, 2], "children": [1]},
          {"mesh": 0, "rotation": [0, 0, $half, $half]}
        ],
        "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]}],
        "buffers": [{"byteLength": ${bin.size}}],
        "bufferViews": [
          {"buffer": 0, "byteOffset": 0, "byteLength": 36},
          {"buffer": 0, "byteOffset": 36, "byteLength": 6}
        ],
        "accessors": [
          {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3", "min": [0, 0, 0], "max": [1, 1, 0]},
          {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"}
        ]
      }
    """.trimIndent()
    val mesh = parseMesh(glb(json, bin), "glb")
    assertEquals(2, mesh.triangleCount)
    assertContentEquals(intArrayOf(0, 1, 2, 3, 4, 5), mesh.indices)
    // Root instance: 2x + 10.
    assertClose(floatArrayOf(10f, 0f, 0f, 12f, 0f, 0f, 10f, 2f, 0f), mesh.positions.copyOfRange(0, 9))
    // Child instance: turned (x, y) -> (-y, x), then the root's transform.
    assertClose(floatArrayOf(10f, 0f, 0f, 10f, 2f, 0f, 8f, 0f, 0f), mesh.positions.copyOfRange(9, 18))
  }

  @Test
  fun `reads gltf with an embedded buffer and no indices`() {
    val bin = Bytes(littleEndian = true).apply { listOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f).forEach(::float) }.toByteArray()
    val json = """
      {
        "asset": {"version": "2.0"},
        "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "mode": 4}]}],
        "nodes": [{"mesh": 0, "matrix": [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,5,0,1]}],
        "buffers": [{"byteLength": 36, "uri": "data:application/octet-stream;base64,${Base64.Default.encode(bin)}"}],
        "bufferViews": [{"buffer": 0, "byteLength": 36}],
        "accessors": [{"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"}]
      }
    """.trimIndent()
    val mesh = parseMesh(json.encodeToByteArray(), "gltf")
    assertContentEquals(intArrayOf(0, 1, 2), mesh.indices)
    assertContentEquals(floatArrayOf(0f, 5f, 0f, 1f, 5f, 0f, 0f, 5f, 1f), mesh.positions)
  }

  @Test
  fun `gltf with its buffer in another file is unreadable`() {
    val json = """
      {
        "asset": {"version": "2.0"},
        "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}],
        "buffers": [{"byteLength": 36, "uri": "models/scene.bin"}],
        "bufferViews": [{"buffer": 0, "byteLength": 36}],
        "accessors": [{"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"}]
      }
    """.trimIndent()
    val error = assertFailsWith<MeshFormatException> { parseMesh(json.encodeToByteArray(), "gltf") }
    assertTrue("scene.bin" in error.message.orEmpty(), error.message)
    assertFailsWith<MeshFormatException> { parseMesh("{not json".encodeToByteArray(), "gltf") }
  }

  // Drawing

  @Test
  fun `thins out big meshes and fits them in the unit sphere`() {
    val positions = FloatArray(3000 * 9) { if (it % 3 == 0) it.toFloat() else (it % 7).toFloat() }
    val scene = prepareScene(Mesh(positions, IntArray(3000 * 3) { it }), zUp = false, maxTriangles = 1000)
    assertEquals(3000, scene.totalTriangles)
    assertEquals(1000, scene.drawnTriangles)
    for (v in 0 until scene.positions.size / 3) {
      val (x, y, z) = Triple(scene.positions[v * 3], scene.positions[v * 3 + 1], scene.positions[v * 3 + 2])
      assertTrue(x * x + y * y + z * z <= 1.0001f)
    }
  }

  @Test
  fun `draws a triangle facing the camera`() {
    val mesh = Mesh(floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, 0f, 1f, 0f), intArrayOf(0, 1, 2))
    val renderer = MeshRenderer(prepareScene(mesh, zUp = false))
    val palette = IntArray(256) { (0xFF shl 24) or it }
    val pixels = renderer.render(64, 64, ModelView(yaw = 0f, pitch = 0f), palette)
    assertTrue(pixels[32 * 64 + 32] ushr 24 == 0xFF, "the centre is covered")
    assertEquals(0, pixels[0], "the corner is clear")
  }
}

/** Two triangles of a unit square, nine floats each. */
private val Square = listOf(
  floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
  floatArrayOf(1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f),
)

private fun binaryStl(header: String, triangles: List<FloatArray>, declaredCount: Int = triangles.size): ByteArray = Bytes(littleEndian = true).apply {
  val text = header.encodeToByteArray()
  repeat(80) { byte(if (it < text.size) text[it].toInt() else 0) }
  int32(declaredCount)
  for (triangle in triangles) {
    repeat(3) { float(0f) }
    triangle.forEach(::float)
    int16(0)
  }
}.toByteArray()

private fun glb(json: String, bin: ByteArray): ByteArray {
  val text = json.encodeToByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
  return Bytes(littleEndian = true).apply {
    int32(0x46546C67)
    int32(2)
    int32(12 + 8 + text.size + 8 + bin.size)
    int32(text.size)
    int32(0x4E4F534A)
    text.forEach { byte(it.toInt()) }
    int32(bin.size)
    int32(0x004E4942)
    bin.forEach { byte(it.toInt()) }
  }.toByteArray()
}

private fun assertClose(expected: FloatArray, actual: FloatArray) {
  assertEquals(expected.size, actual.size)
  for (i in expected.indices) assertTrue(abs(expected[i] - actual[i]) < 1e-4f, "at $i: expected ${expected.toList()} but was ${actual.toList()}")
}

/** Builds binary fixtures in either byte order. */
private class Bytes(private val littleEndian: Boolean) {
  private val bytes = mutableListOf<Byte>()

  fun byte(value: Int) {
    bytes += value.toByte()
  }

  fun int16(value: Int) = ordered(value.toLong(), 2)

  fun int32(value: Int) = ordered(value.toLong(), 4)

  fun float(value: Float) = int32(value.toRawBits())

  fun double(value: Double) = ordered(value.toRawBits(), 8)

  private fun ordered(value: Long, size: Int) {
    val order = if (littleEndian) 0 until size else (size - 1) downTo 0
    for (i in order) byte((value shr (8 * i)).toInt() and 0xFF)
  }

  fun toByteArray(): ByteArray = bytes.toByteArray()
}
