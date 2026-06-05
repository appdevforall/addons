package org.appdevforall.maps.slicer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Branch coverage for [PmtilesV3]'s `compressionName` and `tileTypeName`
 * lookup `when` expressions — every named code plus the `else` (unknown) arm.
 */
class PmtilesV3CoverageTest {

    @Test
    fun compressionName_covers_every_known_code() {
        assertEquals("none", PmtilesV3.compressionName(PmtilesV3.COMPRESSION_NONE))
        assertEquals("gzip", PmtilesV3.compressionName(PmtilesV3.COMPRESSION_GZIP))
        assertEquals("brotli", PmtilesV3.compressionName(PmtilesV3.COMPRESSION_BROTLI))
        assertEquals("zstd", PmtilesV3.compressionName(PmtilesV3.COMPRESSION_ZSTD))
    }

    @Test
    fun compressionName_unknown_falls_through_to_else() {
        // COMPRESSION_UNKNOWN (0) and any other byte hit the else arm.
        assertEquals("unknown(0)", PmtilesV3.compressionName(PmtilesV3.COMPRESSION_UNKNOWN))
        assertEquals("unknown(9)", PmtilesV3.compressionName(9))
    }

    @Test
    fun tileTypeName_covers_every_known_code() {
        assertEquals("mvt", PmtilesV3.tileTypeName(PmtilesV3.TYPE_MVT))
        assertEquals("png", PmtilesV3.tileTypeName(PmtilesV3.TYPE_PNG))
        assertEquals("jpeg", PmtilesV3.tileTypeName(PmtilesV3.TYPE_JPEG))
        assertEquals("webp", PmtilesV3.tileTypeName(PmtilesV3.TYPE_WEBP))
        assertEquals("avif", PmtilesV3.tileTypeName(PmtilesV3.TYPE_AVIF))
    }

    @Test
    fun tileTypeName_unknown_falls_through_to_else() {
        assertEquals("unknown(0)", PmtilesV3.tileTypeName(PmtilesV3.TYPE_UNKNOWN))
        assertEquals("unknown(7)", PmtilesV3.tileTypeName(7))
    }
}
