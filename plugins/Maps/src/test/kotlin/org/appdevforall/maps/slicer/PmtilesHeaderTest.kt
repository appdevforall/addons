package org.appdevforall.maps.slicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PmtilesHeaderTest {

    private val bundledNe: File = File(
        // Path used at test-time — the bundled NE archive ships under
        // maps-plugin/src/main/assets/maps/. Tests run from the maps-plugin
        // module root.
        "src/main/assets/maps/natural-earth-z0-z4.pmtiles"
    )

    @Test
    fun parses_bundled_natural_earth_header() {
        assertTrue("Bundled NE PMTiles missing at ${bundledNe.absolutePath}", bundledNe.exists())
        val headerBytes = bundledNe.inputStream().use { it.readNBytes(PmtilesV3.HEADER_BYTES) }
        val header = PmtilesHeader.parse(
            ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        )
        assertEquals("version", 3.toByte(), header.version)
        assertEquals("root_dir_offset", 127L, header.rootDirOffset)
        // Tile type 4 = WEBP per the bundled NE archive.
        assertEquals("tile type", PmtilesV3.TYPE_WEBP, header.tileType)
        // Internal compression for directories — gzip per planetiler defaults.
        assertEquals("internal_compression", PmtilesV3.COMPRESSION_GZIP, header.internalCompression)
        assertEquals("min_zoom", 0.toByte(), header.minZoom)
        assertEquals("max_zoom", 4.toByte(), header.maxZoom)
    }

    @Test
    fun roundtrips_serialize_then_parse() {
        assertTrue(bundledNe.exists())
        val original = bundledNe.inputStream().use { it.readNBytes(PmtilesV3.HEADER_BYTES) }
        val header = PmtilesHeader.parse(
            ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
        )
        val reserialized = header.toByteArray()
        assertArrayEquals(original, reserialized)
    }
}
