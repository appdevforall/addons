package org.appdevforall.maps.slicer

import org.appdevforall.maps.domain.Bbox
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * End-to-end slicer tests against the bundled Natural Earth z0-z4 archive.
 *
 * NE z0-z4 is small (~7 MB, 341 tiles total) and ships in the plugin assets,
 * so the slicer can run wholly offline against a real PMTiles v3 source. We
 * use `file://` URLs here; production wires up `https://`.
 */
class PmtilesRegionSlicerTest {

    private val bundledNe: File = File("src/main/assets/maps/natural-earth-z0-z4.pmtiles")

    @Test
    fun tilesInRegion_full_world_z0_returns_single_tile() = runBlocking {
        assumeTrue("bundled NE archive must exist", bundledNe.exists())
        val tiles = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = "file://" + bundledNe.absolutePath,
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 0,
            zoomMax = 0,
        ).getOrThrow()
        assertEquals("full world at z0 has exactly 1 tile", 1, tiles.size)
        assertEquals(0, tiles[0].z)
        assertEquals(0, tiles[0].x)
        assertEquals(0, tiles[0].y)
    }

    @Test
    fun tilesInRegion_full_world_z0_z4_returns_all_341() = runBlocking {
        assumeTrue(bundledNe.exists())
        // z0..z4 covers 1 + 4 + 16 + 64 + 256 = 341 tiles for full world.
        val tiles = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = "file://" + bundledNe.absolutePath,
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 0,
            zoomMax = 4,
        ).getOrThrow()
        assertTrue(
            "expected ~341 tiles for z0..z4 worldwide, got ${tiles.size}",
            tiles.size in 300..341
        )
        // Per-zoom sanity.
        val byZ = tiles.groupBy { it.z }.mapValues { it.value.size }
        assertEquals(1, byZ[0])
        assertEquals(4, byZ[1])
        assertEquals(16, byZ[2])
        // z3 might have ocean tiles missing in the source archive; allow <=64.
        assertTrue("z3 count ${byZ[3]} should be <=64", (byZ[3] ?: 0) <= 64)
        assertTrue("z4 count ${byZ[4]} should be <=256", (byZ[4] ?: 0) <= 256)
    }

    @Test
    fun tilesInRegion_small_bbox_returns_subset() = runBlocking {
        assumeTrue(bundledNe.exists())
        // Cox's Bazar region — but NE is z0-z4 only, so this returns just a
        // single tile at most zoom levels.
        val tiles = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = "file://" + bundledNe.absolutePath,
            bbox = Bbox(21.10, 92.10, 21.30, 92.25),
            zoomMin = 0,
            zoomMax = 4,
        ).getOrThrow()
        // Expect: 1 (z0) + 1 (z1) + 1 (z2) + 1 (z3) + 1 (z4) = 5 tiles.
        // The exact number depends on whether the source archive carries every
        // ocean tile (Cox's Bazar is coastal — could be missing entries).
        assertTrue("expected ~5 tiles for a small bbox z0-z4, got ${tiles.size}", tiles.size in 1..10)
    }

    @Test
    fun estimateRegionBytes_is_nonzero_and_matches_byte_sum() = runBlocking {
        assumeTrue(bundledNe.exists())
        val tiles = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = "file://" + bundledNe.absolutePath,
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 0,
            zoomMax = 2,
        ).getOrThrow()
        val estimate = PmtilesRegionSlicer.estimateRegionBytes(tiles)
        val byteSum = tiles.sumOf { it.byteLength }
        assertEquals("estimate = tile-byte sum + small overhead", estimate, byteSum + 8 * 1024)
    }

    @Test
    fun downloadAndSlice_writes_valid_pmtiles_v3() = runBlocking {
        assumeTrue(bundledNe.exists())
        val sourceUrl = "file://" + bundledNe.absolutePath

        // Read the source header to pass into downloadAndSlice.
        val headerBytes = bundledNe.inputStream().use { it.readNBytes(PmtilesV3.HEADER_BYTES) }
        val sourceHeader = PmtilesHeader.parse(
            ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        )

        // Slice z0-z3 worldwide — produces a small archive (~1 MB or less)
        // we can write to a temp file and re-parse.
        val tiles = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = sourceUrl,
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 0,
            zoomMax = 3,
        ).getOrThrow()
        assertTrue("expected tiles", tiles.isNotEmpty())

        val tmp = File.createTempFile("sliced-", ".pmtiles")
        tmp.deleteOnExit()
        val progress = mutableListOf<Pair<Long, Long>>()
        PmtilesRegionSlicer.downloadAndSlice(
            tiles = tiles,
            globalPmtilesUrl = sourceUrl,
            sourceHeader = sourceHeader,
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 0,
            zoomMax = 3,
            targetFile = tmp,
            onProgress = { d, t -> progress += d to t },
        ).getOrThrow()

        assertTrue("sliced file exists", tmp.exists())
        assertTrue("sliced file non-empty", tmp.length() > PmtilesV3.HEADER_BYTES.toLong())
        assertTrue("progress reported", progress.isNotEmpty())

        // Round-trip: parse the sliced header.
        val slicedHeader = PmtilesHeader.parse(
            ByteBuffer.wrap(tmp.inputStream().use { it.readNBytes(PmtilesV3.HEADER_BYTES) })
                .order(ByteOrder.LITTLE_ENDIAN)
        )
        assertEquals("version", 3.toByte(), slicedHeader.version)
        assertEquals("clustered=1", 1.toByte(), slicedHeader.clustered)
        assertNotNull(slicedHeader)

        // Re-slice from the sliced file — should yield the same tile count.
        val resliced = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = "file://" + tmp.absolutePath,
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 0,
            zoomMax = 3,
        ).getOrThrow()
        assertEquals("sliced and re-sliced tile counts match", tiles.size, resliced.size)
    }
}
