package org.appdevforall.maps.slicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Branch coverage for [PmtilesHeader] and its companion `parse`:
 * the `require` guards (too-small buffer, bad magic, bad version) plus the
 * accessor branches (`isClustered` true/false, lon/lat conversions). Builds
 * 127-byte header buffers by hand rather than relying on the bundled archive.
 */
class PmtilesHeaderCoverageTest {

    /** Construct a synthetic, well-formed 127-byte v3 header buffer. */
    private fun headerBytes(
        version: Byte = PmtilesV3.VERSION,
        magic: ByteArray = PmtilesV3.MAGIC,
        clustered: Byte = 1,
        minLonE7: Int = -10_000_000,
        minLatE7: Int = 5_000_000,
        maxLonE7: Int = 20_000_000,
        maxLatE7: Int = 30_000_000,
    ): ByteBuffer {
        val buf = ByteBuffer.allocate(PmtilesV3.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(magic)
        buf.put(version)
        buf.putLong(127L) // rootDirOffset
        buf.putLong(10L) // rootDirBytes
        buf.putLong(200L) // jsonMetadataOffset
        buf.putLong(20L) // jsonMetadataBytes
        buf.putLong(300L) // leafDirsOffset
        buf.putLong(30L) // leafDirsBytes
        buf.putLong(400L) // tileDataOffset
        buf.putLong(40L) // tileDataBytes
        buf.putLong(50L) // addressedTilesCount
        buf.putLong(60L) // tileEntriesCount
        buf.putLong(70L) // tileContentsCount
        buf.put(clustered)
        buf.put(PmtilesV3.COMPRESSION_GZIP) // internalCompression
        buf.put(PmtilesV3.COMPRESSION_NONE) // tileCompression
        buf.put(PmtilesV3.TYPE_MVT) // tileType
        buf.put(0) // minZoom
        buf.put(4) // maxZoom
        buf.putInt(minLonE7)
        buf.putInt(minLatE7)
        buf.putInt(maxLonE7)
        buf.putInt(maxLatE7)
        buf.put(2) // centerZoom
        buf.putInt(1_000_000) // centerLonE7
        buf.putInt(2_000_000) // centerLatE7
        require(buf.position() == PmtilesV3.HEADER_BYTES)
        buf.flip()
        return buf
    }

    @Test
    fun parses_well_formed_header_and_exposes_fields() {
        val header = PmtilesHeader.parse(headerBytes())
        assertEquals(PmtilesV3.VERSION, header.version)
        assertEquals(127L, header.rootDirOffset)
        assertEquals(400L, header.tileDataOffset)
        assertEquals(PmtilesV3.TYPE_MVT, header.tileType)
    }

    @Test
    fun isClustered_true_when_flag_is_one() {
        assertTrue(PmtilesHeader.parse(headerBytes(clustered = 1)).isClustered())
    }

    @Test
    fun isClustered_false_when_flag_is_zero() {
        assertFalse(PmtilesHeader.parse(headerBytes(clustered = 0)).isClustered())
    }

    @Test
    fun lon_lat_conversions_divide_by_1e7() {
        val header = PmtilesHeader.parse(
            headerBytes(
                minLonE7 = -10_000_000,
                minLatE7 = 5_000_000,
                maxLonE7 = 20_000_000,
                maxLatE7 = 30_000_000,
            )
        )
        assertEquals(-1.0, header.minLon(), 1e-9)
        assertEquals(0.5, header.minLat(), 1e-9)
        assertEquals(2.0, header.maxLon(), 1e-9)
        assertEquals(3.0, header.maxLat(), 1e-9)
    }

    @Test
    fun parse_rejects_buffer_too_small() {
        val tiny = ByteBuffer.allocate(PmtilesV3.HEADER_BYTES - 1).order(ByteOrder.LITTLE_ENDIAN)
        assertThrows(IllegalArgumentException::class.java) { PmtilesHeader.parse(tiny) }
    }

    @Test
    fun parse_rejects_bad_magic() {
        val badMagic = byteArrayOf(
            'X'.code.toByte(), 'M'.code.toByte(), 'T'.code.toByte(),
            'i'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PmtilesHeader.parse(headerBytes(magic = badMagic))
        }
    }

    @Test
    fun parse_rejects_unsupported_version() {
        assertThrows(IllegalArgumentException::class.java) {
            PmtilesHeader.parse(headerBytes(version = 2))
        }
    }

    @Test
    fun toByteArray_roundtrips_through_parse() {
        val original = PmtilesHeader.parse(headerBytes())
        val reparsed = PmtilesHeader.parse(
            ByteBuffer.wrap(original.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        )
        assertEquals(original, reparsed)
    }

    /**
     * Build a header buffer with explicit tile-type and internal/tile compression
     * bytes so the decode of those fields is exercised for non-default values
     * (the default fixture always uses WEBP-less MVT + gzip/none).
     */
    private fun headerBytesWithCodes(
        internalCompression: Byte,
        tileCompression: Byte,
        tileType: Byte,
    ): ByteBuffer {
        val buf = ByteBuffer.allocate(PmtilesV3.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(PmtilesV3.MAGIC)
        buf.put(PmtilesV3.VERSION)
        repeat(11) { buf.putLong(0L) } // the 11 long fields — values irrelevant here
        buf.put(1) // clustered
        buf.put(internalCompression)
        buf.put(tileCompression)
        buf.put(tileType)
        buf.put(0) // minZoom
        buf.put(4) // maxZoom
        buf.putInt(0) // minLonE7
        buf.putInt(0) // minLatE7
        buf.putInt(0) // maxLonE7
        buf.putInt(0) // maxLatE7
        buf.put(0) // centerZoom
        buf.putInt(0) // centerLonE7
        buf.putInt(0) // centerLatE7
        require(buf.position() == PmtilesV3.HEADER_BYTES)
        buf.flip()
        return buf
    }

    @Test
    fun parse_decodes_png_tiles_with_brotli_and_none_codes() {
        // A non-MVT tile type plus brotli internal-compression / none
        // tile-compression — distinct byte values from the WEBP-gzip default,
        // confirming the per-byte field decode (offsets 97/98/99) is correct.
        val header = PmtilesHeader.parse(
            headerBytesWithCodes(
                internalCompression = PmtilesV3.COMPRESSION_BROTLI,
                tileCompression = PmtilesV3.COMPRESSION_NONE,
                tileType = PmtilesV3.TYPE_PNG,
            )
        )
        assertEquals(PmtilesV3.TYPE_PNG, header.tileType)
        assertEquals(PmtilesV3.COMPRESSION_BROTLI, header.internalCompression)
        assertEquals(PmtilesV3.COMPRESSION_NONE, header.tileCompression)
        // These three bytes survive a serialize round-trip unchanged.
        val reparsed = PmtilesHeader.parse(
            ByteBuffer.wrap(header.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        )
        assertEquals(PmtilesV3.TYPE_PNG, reparsed.tileType)
        assertEquals(PmtilesV3.COMPRESSION_BROTLI, reparsed.internalCompression)
        assertEquals(PmtilesV3.COMPRESSION_NONE, reparsed.tileCompression)
    }
}
