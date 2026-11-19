package org.appdevforall.maps.slicer

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Protobuf-style unsigned varints, used by PMTiles v3 directories.
 *
 * Wire format (per protobuf): little-endian base-128. The MSB of each byte
 * indicates "more bytes follow". A 64-bit varint takes at most 10 bytes.
 */
internal object Varint {

    /** Decode a single varint from [buf] starting at its current position. */
    fun decode(buf: ByteBuffer): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(shift < 64) { "varint > 64 bits" }
            val b = buf.get().toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
    }

    /** Encode [value] as a varint to [out]. */
    fun encode(value: Long, out: ByteArrayOutputStream) {
        require(value >= 0) { "negative varint $value not supported by spec" }
        var v = value
        while ((v and 0x7fL.inv()) != 0L) {
            out.write(((v and 0x7fL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7fL).toInt())
    }

    /** Max bytes needed to encode any uint64 varint. */
    const val MAX_BYTES = 10
}
