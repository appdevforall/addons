package org.appdevforall.maps.slicer

import org.appdevforall.maps.domain.Bbox
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow
import kotlin.system.measureTimeMillis

/**
 * Validation suite for the six representative NGO regions.
 *
 * **What this test does** (offline / always-on):
 *   - Computes `tilesInRegion(...)` for each region's bbox against the
 *     bundled Natural Earth z0-z4 archive. NE is global, so for any region
 *     we expect exactly the number of (z,x,y) tiles whose XY-rectangle
 *     overlaps the bbox at zooms 0..4.
 *   - Sanity-checks via the published-formula expected tile count: for
 *     each zoom z, `tile_count = (xMax-xMin+1) * (yMax-yMin+1)`.
 *   - Records slicer latency.
 *
 * **What this test does NOT do** (offline limitation): hit the real OSM
 * archive on `iiab.switnet.org`. That archive is multi-hundred-MB; the
 * test suite has to stay self-contained. The catalog estimate column
 * ("~100-150 MB", etc.) refers to that real archive, not NE z0-z4.
 *
 * To validate against the **real catalog estimates**, run the
 * `Maps:mapsOnlineSlicerSmoke` JUnit category test manually with network
 * access — it's defined in [OnlineNgoRegionValidationTest].
 */
class NgoRegionValidationTest {

    private val bundledNe: File = File("src/main/assets/maps/natural-earth-z0-z4.pmtiles")

    /**
     * Six representative NGO regions. Bbox is (south, west, north, east).
     */
    private val ngoRegions = listOf(
        NgoRegion("coxs-bazar",    "Cox's Bazar refugee camps, Bangladesh",
                  Bbox(21.10, 92.10, 21.30, 92.25)),
        NgoRegion("kakuma",        "Kakuma refugee camp, Kenya (Turkana)",
                  Bbox(3.65, 34.80, 3.85, 35.00)),
        NgoRegion("goma",          "Goma + Lake Kivu shore, DRC (North Kivu)",
                  Bbox(-1.80, 28.85, -1.50, 29.30)),
        NgoRegion("port-au-prince","Port-au-Prince metro, Haiti",
                  Bbox(18.45, -72.45, 18.65, -72.20)),
        NgoRegion("kathmandu",     "Kathmandu Valley + districts, Nepal",
                  Bbox(27.55, 85.20, 27.85, 85.55)),
        NgoRegion("maiduguri",     "Maiduguri + Borno IDP camps, Nigeria",
                  Bbox(11.75, 13.05, 11.95, 13.30)),
    )

    private data class NgoRegion(val id: String, val displayName: String, val bbox: Bbox)

    @Test
    fun slicer_runs_for_all_six_ngo_regions_offline() = runBlocking {
        assumeTrue("bundled NE archive must exist", bundledNe.exists())
        val sourceUrl = "file://" + bundledNe.absolutePath

        println()
        println("=== NGO region slicer validation (NE z0-z4 stand-in) ===")
        println("%-18s %12s %10s %10s %s".format("region", "tiles", "bytes_kb", "latency_ms", "slippy_math"))
        println("-".repeat(80))

        for (region in ngoRegions) {
            val expectedZxyCount = expectedSlippyTileCount(region.bbox, zoomMin = 0, zoomMax = 4)
            val tiles: List<TileEntry>
            val latency = measureTimeMillis {
                tiles = PmtilesRegionSlicer.tilesInRegion(
                    globalPmtilesUrl = sourceUrl,
                    bbox = region.bbox,
                    zoomMin = 0,
                    zoomMax = 4,
                ).getOrThrow()
            }
            val totalBytes = tiles.sumOf { it.byteLength }
            println(
                "%-18s %12d %10d %10d expected_slippy=%d".format(
                    region.id,
                    tiles.size,
                    totalBytes / 1024,
                    latency,
                    expectedZxyCount,
                )
            )
            // NE z0-z4 doesn't guarantee a tile entry for every slippy tile
            // (sea-only tiles may be absent), so we assert "we don't over-
            // collect" — the slicer's tile count <= the expected slippy
            // rectangle count, never above.
            assert(tiles.size <= expectedZxyCount) {
                "${region.id}: slicer returned ${tiles.size} tiles, expected at most $expectedZxyCount"
            }
            // Sanity: at least *some* tiles in every region (every NGO region
            // has land coverage in NE).
            assert(tiles.isNotEmpty()) {
                "${region.id}: slicer returned zero tiles — bbox / Hilbert math wrong?"
            }
            // Latency budget on a local file: should be < 1s even worst-case.
            assert(latency < 5000) {
                "${region.id}: slicer took ${latency}ms — too slow"
            }
        }
        println("-".repeat(80))
    }

    @Test
    fun estimate_is_consistent_with_tile_byte_sum() = runBlocking {
        assumeTrue(bundledNe.exists())
        val sourceUrl = "file://" + bundledNe.absolutePath
        for (region in ngoRegions) {
            val tiles = PmtilesRegionSlicer.tilesInRegion(
                globalPmtilesUrl = sourceUrl,
                bbox = region.bbox,
                zoomMin = 0,
                zoomMax = 4,
            ).getOrThrow()
            val estimate = PmtilesRegionSlicer.estimateRegionBytes(tiles)
            val sum = tiles.sumOf { it.byteLength }
            // estimateRegionBytes adds a fixed 8KB overhead.
            assert(estimate == sum + 8L * 1024L) {
                "${region.id}: estimate $estimate != sum $sum + 8KB"
            }
        }
    }

    @Test
    fun downloadAndSlice_works_for_coxs_bazar_against_NE() = runBlocking {
        assumeTrue(bundledNe.exists())
        val sourceUrl = "file://" + bundledNe.absolutePath
        val region = ngoRegions.first { it.id == "coxs-bazar" }

        // Read source header (parse from local file)
        val headerBytes = bundledNe.inputStream().use { it.readNBytes(PmtilesV3.HEADER_BYTES) }
        val sourceHeader = PmtilesHeader.parse(
            java.nio.ByteBuffer.wrap(headerBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        )

        val tiles = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = sourceUrl,
            bbox = region.bbox,
            zoomMin = 0,
            zoomMax = 4,
        ).getOrThrow()
        assert(tiles.isNotEmpty()) { "Cox's Bazar should have at least 1 tile" }

        val tmp = File.createTempFile("coxs-bazar-", ".pmtiles")
        tmp.deleteOnExit()

        PmtilesRegionSlicer.downloadAndSlice(
            tiles = tiles,
            globalPmtilesUrl = sourceUrl,
            sourceHeader = sourceHeader,
            bbox = region.bbox,
            zoomMin = 0,
            zoomMax = 4,
            targetFile = tmp,
            onProgress = { _, _ -> },
        ).getOrThrow()

        assert(tmp.exists() && tmp.length() > PmtilesV3.HEADER_BYTES) {
            "sliced file should be created and non-trivial"
        }

        // Re-slice the output — should yield the same tile count.
        val resliced = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = "file://" + tmp.absolutePath,
            bbox = region.bbox,
            zoomMin = 0,
            zoomMax = 4,
        ).getOrThrow()
        assert(resliced.size == tiles.size) {
            "re-sliced count ${resliced.size} != original ${tiles.size}"
        }
    }

    /**
     * Slippy-map expected count: `(xMax-xMin+1) × (yMax-yMin+1)` per zoom.
     * Independent of any PMTiles content — pure math from the spec.
     */
    private fun expectedSlippyTileCount(bbox: Bbox, zoomMin: Int, zoomMax: Int): Int {
        var total = 0
        for (z in zoomMin..zoomMax) {
            val n = 2.0.pow(z).toInt()
            val xMin = Math.floor((bbox.west + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
            val xMax = Math.floor((bbox.east + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
            val latRadN = Math.toRadians(bbox.north.coerceIn(-85.0511, 85.0511))
            val latRadS = Math.toRadians(bbox.south.coerceIn(-85.0511, 85.0511))
            val yMin = Math.floor((1 - Math.log(Math.tan(latRadN) + 1.0 / Math.cos(latRadN)) / Math.PI) / 2.0 * n)
                .toInt().coerceIn(0, n - 1)
            val yMax = Math.floor((1 - Math.log(Math.tan(latRadS) + 1.0 / Math.cos(latRadS)) / Math.PI) / 2.0 * n)
                .toInt().coerceIn(0, n - 1)
            total += (xMax - xMin + 1) * (yMax - yMin + 1)
        }
        return total
    }
}
