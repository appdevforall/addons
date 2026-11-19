package org.appdevforall.maps.slicer

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.appdevforall.maps.domain.Bbox
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Edge cases of [PmtilesRegionSlicer] against the bundled Natural Earth archive: zoom bounds
 * on both ends, slicing a tile list given in any order, and a target that cannot be replaced.
 */
class PmtilesRegionSlicerEdgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val archive = File("src/main/assets/maps/natural-earth-z0-z4.pmtiles")
    private val url = "file://" + archive.absolutePath
    private val world = Bbox(south = -85.0, west = -180.0, north = 85.0, east = 180.0)

    private fun sourceHeader(): PmtilesHeader = PmtilesHeader.parse(
        ByteBuffer.wrap(archive.inputStream().use { it.readNBytes(PmtilesV3.HEADER_BYTES) })
            .order(ByteOrder.LITTLE_ENDIAN)
    )

    private fun header(file: File): PmtilesHeader = PmtilesHeader.parse(
        ByteBuffer.wrap(file.inputStream().use { it.readNBytes(PmtilesV3.HEADER_BYTES) })
            .order(ByteOrder.LITTLE_ENDIAN)
    )

    private suspend fun slice(tiles: List<TileEntry>, target: File): Result<Unit> =
        PmtilesRegionSlicer.downloadAndSlice(
            tiles = tiles,
            globalPmtilesUrl = url,
            sourceHeader = sourceHeader(),
            bbox = world,
            zoomMin = 1,
            zoomMax = 2,
            targetFile = target,
            onProgress = { _, _ -> },
        )

    @Test
    fun `zoom levels outside 0 to 20 are rejected at both ends`() = runBlocking {
        for ((zMin, zMax) in listOf(-1 to 2, 0 to 21)) {
            val result = PmtilesRegionSlicer.tilesInRegion(url, world, zMin, zMax)
            assertTrue("z$zMin..$zMax: $result", result.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun `a tile list in reverse order slices to the same archive bounds`() = runBlocking {
        val tiles = PmtilesRegionSlicer.tilesInRegion(url, world, 1, 2).getOrThrow()
        val sorted = File(tmp.root, "sorted.pmtiles")
        val reversed = File(tmp.root, "reversed.pmtiles")

        slice(tiles, sorted).getOrThrow()
        slice(tiles.reversed(), reversed).getOrThrow()

        val a = header(sorted)
        val b = header(reversed)
        // The header bbox is the min/max over every tile, so input order must not change it.
        assertEquals(
            listOf(a.minLonE7, a.minLatE7, a.maxLonE7, a.maxLatE7),
            listOf(b.minLonE7, b.minLatE7, b.maxLonE7, b.maxLatE7),
        )
        assertEquals(-1_800_000_000, b.minLonE7)
        assertEquals(1_800_000_000, b.maxLonE7)
        assertEquals(
            tiles.size,
            PmtilesRegionSlicer.tilesInRegion("file://" + reversed.absolutePath, world, 1, 2).getOrThrow().size,
        )
    }

    @Test
    fun `slicing fails when the target cannot be replaced`() = runBlocking {
        val tiles = PmtilesRegionSlicer.tilesInRegion(url, world, 1, 1).getOrThrow()
        // A non-empty directory at the target path survives delete(), so the final rename fails.
        val target = tmp.newFolder("occupied.pmtiles")
        val keep = File(target, "keep").apply { writeText("x") }

        val result = slice(tiles, target)

        val error = result.exceptionOrNull()
        assertTrue("got $error", error is IllegalArgumentException)
        assertTrue(error!!.message!!.startsWith("Couldn't rename"))
        assertArrayEquals("x".toByteArray(), keep.readBytes())
    }
}
