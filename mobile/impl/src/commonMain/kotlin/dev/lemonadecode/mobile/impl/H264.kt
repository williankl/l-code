package dev.lemonadecode.mobile.impl

/**
 * Cuts an H.264 stream in Annex B form, as `screenrecord --output-format=h264` writes it, into
 * access units: the NAL units of one picture, with the parameter sets before it, for a decoder to
 * take one at a time.
 *
 * Nothing in the stream says where a picture ends, only where the next one starts. So the last
 * picture before the screen stops changing would wait for it to change again; [flush] hands it
 * over once the stream has gone quiet. When the quiet was only a pause in the middle of the
 * picture, the bytes after it don't start a new unit, and [cutShort] says so.
 */
internal class AccessUnitSplitter {
  private var buffer = ByteArray(InitialCapacity)
  private var size = 0

  /** Where the next start code is looked for. */
  private var scanned = 0

  /** Where the access unit being gathered starts, at its start code; -1 until a start code comes. */
  private var unitStart = -1

  /** The unit being gathered holds a slice of its picture already. */
  private var hasSlice = false

  /** A [flush] happened and nothing has come since. */
  private var flushed = false

  /**
   * The last [flush] handed over a picture that wasn't whole: what came after it carried on in
   * the middle of a unit. A decoder given it has a damaged picture, and every one built on it.
   */
  var cutShort: Boolean = false
    private set

  /** Adds [length] bytes of [bytes] to the stream; returns the access units they complete, oldest first. */
  fun feed(bytes: ByteArray, length: Int = bytes.size): List<ByteArray> {
    append(bytes, length)
    if (flushed) checkAfterFlush()
    val units = mutableListOf<ByteArray>()
    var at = scanned
    while (at + StartCodeLength < size) {
      if (!isStartCode(at)) {
        at++
        continue
      }
      val header = at + StartCodeLength
      val type = buffer[header].toInt() and NalTypeMask
      val slice = type == NalSlice || type == NalIdrSlice
      // A four-byte start code's leading zero comes with it.
      val nalStart = if (at > unitStart && at > 0 && buffer[at - 1] == ZeroByte) at - 1 else at
      if (unitStart < 0) {
        unitStart = nalStart
      } else if (hasSlice) {
        // A slice's next byte says whether it starts a new picture; wait for it.
        if (slice && header + 1 >= size) break
        if (startsUnit(type, header)) {
          units += buffer.copyOfRange(unitStart, nalStart)
          unitStart = nalStart
          hasSlice = false
        }
      }
      if (slice) hasSlice = true
      at = header + 1
    }
    scanned = at
    compact()
    return units
  }

  /** The access unit gathered so far, as a whole picture, once the stream has gone quiet; null when there's none. */
  fun flush(): ByteArray? {
    val unit = if (unitStart >= 0 && hasSlice) buffer.copyOfRange(unitStart, size) else null
    if (unit != null) {
      size = 0
      scanned = 0
      unitStart = -1
      hasSlice = false
      flushed = true
    }
    return unit
  }

  /** After a flush the stream must go on with a start code, `00 00 01` or `00 00 00 01`; anything else carries on the picture. */
  private fun checkAfterFlush() {
    for (at in 0 until minOf(size, StartCodeLength + 1)) {
      val byte = buffer[at]
      val fits = when (at) {
        0, 1 -> byte == ZeroByte
        2 -> byte == ZeroByte || byte == OneByte
        else -> buffer[2] == OneByte || byte == OneByte
      }
      if (!fits) {
        cutShort = true
        flushed = false
        return
      }
      if (at == 2 && byte == OneByte) break
    }
    if (size > StartCodeLength) flushed = false
  }

  private fun isStartCode(at: Int): Boolean = buffer[at] == ZeroByte && buffer[at + 1] == ZeroByte && buffer[at + 2] == OneByte

  /** Whether a NAL unit of [type], after a slice, begins the next access unit. */
  private fun startsUnit(type: Int, header: Int): Boolean = when (type) {
    // Its first_mb_in_slice is 0, coded as a single 1 bit: the first slice of a new picture.
    NalSlice, NalIdrSlice -> buffer[header + 1].toInt() and 0x80 != 0
    NalSei, NalSps, NalPps, NalAccessUnitDelimiter -> true
    in NalReservedPrefixFirst..NalReservedPrefixLast -> true
    else -> false
  }

  private fun append(bytes: ByteArray, length: Int) {
    if (size + length > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + length))
    bytes.copyInto(buffer, destinationOffset = size, startIndex = 0, endIndex = length)
    size += length
  }

  /** Drops what's been handed over, or, before the first start code, what can't begin one. */
  private fun compact() {
    // Before the first start code, the last four bytes could be the start of a four-byte one.
    val keepFrom = if (unitStart >= 0) unitStart else maxOf(0, size - StartCodeLength - 1)
    if (keepFrom == 0) return
    buffer.copyInto(buffer, destinationOffset = 0, startIndex = keepFrom, endIndex = size)
    size -= keepFrom
    scanned = maxOf(0, scanned - keepFrom)
    if (unitStart >= 0) unitStart -= keepFrom
  }

  private companion object {
    const val InitialCapacity = 256 * 1024
    const val StartCodeLength = 3
    const val NalTypeMask = 0x1F
    const val NalSlice = 1
    const val NalIdrSlice = 5
    const val NalSei = 6
    const val NalSps = 7
    const val NalPps = 8
    const val NalAccessUnitDelimiter = 9
    const val NalReservedPrefixFirst = 14
    const val NalReservedPrefixLast = 18
    const val ZeroByte: Byte = 0
    const val OneByte: Byte = 1
  }
}
