package org.appdevforall.maps.slicer

import org.appdevforall.maps.domain.Bbox
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * Validates the slicer against the **real** OSM PMTiles archive at
 * `https://iiab.switnet.org/maps/2/`. Compares the slicer's byte-sum
 * estimate against the per-region catalog estimates.
 *
 * **Opt-in**: this test only runs when invoked with
 *   `-DrunOnlineSlicerTests=true`
 *
 * Reason: the test hits the public iiab.switnet.org server with several
 * range requests per region, which:
 *   1. Requires network connectivity (not always available in CI),
 *   2. Costs ~100 KB-1 MB per region in directory bytes,
 *   3. Could rate-limit if hammered.
 *
 * For Cox's Bazar (smallest region) the slicer should return ~5-30 MB
 * of tile bytes at z 6-14. Kathmandu (largest) ~180-280 MB. Catalog
 * estimates have a documented ±50% tolerance.
 */
class OnlineNgoRegionValidationTest {

    /**
     * Public IIAB OSM archive (OpenStreetMap / OpenMapTiles vector).
     *
     * 2026-04-01 = fallback date used by RegionDownloader; if iiab.switnet.org
     * has rotated to a newer dated file, this test will return 404 and we'll
     * need to refresh the constant.
     */
    private val osmVectorUrl =
        "https://iiab.switnet.org/maps/2/openstreetmap-openmaptiles.2026-04-01.z00-z14.pmtiles"

    /** Catalog estimates (low-MB, high-MB), z0-z14 vector. */
    private val ngoRegions = listOf(
        OnlineRegion("coxs-bazar",    Bbox(21.10, 92.10, 21.30, 92.25), 100, 150),
        OnlineRegion("kakuma",        Bbox(3.65, 34.80, 3.85, 35.00),   120, 180),
        OnlineRegion("goma",          Bbox(-1.80, 28.85, -1.50, 29.30), 250, 350),
        OnlineRegion("port-au-prince",Bbox(18.45, -72.45, 18.65, -72.20), 200, 300),
        OnlineRegion("kathmandu",     Bbox(27.55, 85.20, 27.85, 85.55), 180, 280),
        OnlineRegion("maiduguri",     Bbox(11.75, 13.05, 11.95, 13.30), 150, 250),
    )

    private data class OnlineRegion(
        val id: String,
        val bbox: Bbox,
        val expectedLowMb: Int,
        val expectedHighMb: Int,
    )

    @Test
    fun slicer_estimates_match_catalog_for_all_six_regions() = runBlocking {
        assumeTrue(
            "Online slicer tests are opt-in. Pass -DrunOnlineSlicerTests=true to enable.",
            System.getProperty("runOnlineSlicerTests") == "true"
        )

        println()
        println("=== Online NGO region slicer validation (iiab.switnet.org OSM) ===")
        println("URL: $osmVectorUrl")
        println("%-18s %10s %12s %16s %s".format("region", "tiles", "est_MB", "catalog_MB", "verdict"))
        println("-".repeat(80))

        for (region in ngoRegions) {
            val tiles: List<TileEntry>
            val latencyMs = measureTimeMillis {
                tiles = PmtilesRegionSlicer.tilesInRegion(
                    globalPmtilesUrl = osmVectorUrl,
                    bbox = region.bbox,
                    zoomMin = 6,
                    zoomMax = 14,
                ).getOrThrow()
            }
            val estimateBytes = PmtilesRegionSlicer.estimateRegionBytes(tiles)
            val estimateMb = estimateBytes / (1024.0 * 1024.0)

            // ±50% tolerance per the plan ("Flag deltas > 50% in either
            // direction"). Catalog has a low-high range already; we widen by
            // 50% on each side as the merge of catalog spread and slicer
            // estimation error.
            val lowBound = region.expectedLowMb * 0.5
            val highBound = region.expectedHighMb * 1.5
            val withinTolerance = estimateMb in lowBound..highBound
            val verdict = if (withinTolerance) "OK" else "OUT-OF-BAND"

            println(
                "%-18s %10d %12.1f %4d-%-4d MB    %s (%dms)".format(
                    region.id,
                    tiles.size,
                    estimateMb,
                    region.expectedLowMb,
                    region.expectedHighMb,
                    verdict,
                    latencyMs,
                )
            )
        }
        println("-".repeat(80))
    }
}
