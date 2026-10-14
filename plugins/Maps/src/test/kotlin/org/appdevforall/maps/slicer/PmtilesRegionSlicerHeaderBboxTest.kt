package org.appdevforall.maps.slicer

import kotlinx.coroutines.runBlocking
import org.appdevforall.maps.domain.Bbox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `downloadAndSlice` must stamp the sliced header's bbox with the min/max
 * coverage of the tiles actually written — the `tiles.minOf/maxOf` folds over
 * lon/lat. Feeds a hand-built tile list whose x/y sequence is deliberately
 * NON-monotonic (middle → low → high) so every fold takes both its
 * "new extreme" and "keep current" arms, and asserts the resulting header
 * bbox equals the union of the tiles' slippy bounds.
 *
 * Same offline fixture technique as [PmtilesRegionSlicerCoverageTest]: the
 * entries alias a real blob of the bundled Natural Earth archive so the chunk
 * fetch reads genuine bytes.
 */
class PmtilesRegionSlicerHeaderBboxTest {

    private val bundledNe: File = File("src/main/assets/maps/natural-earth-z0-z4.pmtiles")
    private val tempFiles = mutableListOf<File>()

    @After
    fun cleanup() {
        tempFiles.forEach { f ->
            f.delete()
            File(f.parentFile, f.name + ".partial").delete()
        }
        tempFiles.clear()
    }

    private fun sourceHeader(): PmtilesHeader {
        val headerBytes = bundledNe.inputStream().use { it.readNBytes(PmtilesV3.HEADER_BYTES) }
        return PmtilesHeader.parse(ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN))
    }

    @Test
    fun sliced_header_bbox_is_union_of_tile_bounds_regardless_of_tile_order() = runBlocking {
        assumeTrue(bundledNe.exists())
        val src = "file://" + bundledNe.absolutePath
        // NE's real tile-0 blob (same alias trick as the run-extend test).
        val blobOffset = 1215L
        val blobLength = 30122L
        // z=2 tiles in mid → min → max order for BOTH axes: (1,1), (0,0), (2,2).
        val tiles = listOf(
            TileEntry(z = 2, x = 1, y = 1, tileId = 9L, byteOffset = blobOffset, byteLength = blobLength),
            TileEntry(z = 2, x = 0, y = 0, tileId = 11L, byteOffset = blobOffset, byteLength = blobLength),
            TileEntry(z = 2, x = 2, y = 2, tileId = 13L, byteOffset = blobOffset, byteLength = blobLength),
        )

        // Deliberately a NOT-yet-existing target (unlike createTempFile, which
        // pre-creates it): first-ever slice into a fresh path must work without
        // the delete-old-target step.
        val outDir = java.nio.file.Files.createTempDirectory("bbox-order-").toFile()
        val tmp = File(outDir, "sliced.pmtiles").also { tempFiles += it }
        org.junit.Assert.assertFalse("precondition: target must not pre-exist", tmp.exists())
        PmtilesRegionSlicer.downloadAndSlice(
            tiles = tiles,
            globalPmtilesUrl = src,
            sourceHeader = sourceHeader(),
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 2,
            zoomMax = 2,
            targetFile = tmp,
            onProgress = { _, _ -> },
        ).getOrThrow()

        val sliced = PmtilesHeader.parse(
            ByteBuffer.wrap(tmp.inputStream().use { it.readNBytes(PmtilesV3.HEADER_BYTES) })
                .order(ByteOrder.LITTLE_ENDIAN)
        )
        // Union of covered tiles at z=2: x 0..2 → lon [-180, 90); y 0..2 →
        // lat (-66.51, 85.05]. E7 storage → compare at 1e-3 tolerance.
        assertEquals(TileMath.tileToLon(0, 2), sliced.minLon(), 1e-3)   // -180.0
        assertEquals(TileMath.tileToLon(3, 2), sliced.maxLon(), 1e-3)   //   90.0
        assertEquals(TileMath.tileToLat(0, 2), sliced.maxLat(), 1e-3)   //   85.05
        assertEquals(TileMath.tileToLat(3, 2), sliced.minLat(), 1e-3)   //  -66.51
    }
}
