package org.appdevforall.maps.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-math tests for the bbox-picker's auto-cap heuristic. Each case
 * spot-checks one expected zoom-cap for a bbox at a known scale, so
 * regressions in either [ZoomCap.pickZoomMax] or [ZoomCap.cellsInBboxAtZoom]
 * surface immediately.
 */
class ZoomCapTest {

    /** 1° × 1° city-sized bbox at lat 0 → small enough to stay at z=14. */
    @Test
    fun pickZoomMax_smallBbox_keepsStreetDetail() {
        val cityBbox = Bbox(south = -0.5, west = -0.5, north = 0.5, east = 0.5)
        assertEquals(14, ZoomCap.pickZoomMax(cityBbox))
    }

    /** 20° × 20° default no-GPS bbox → caps at "town" detail (z=12). */
    @Test
    fun pickZoomMax_default20deg_capsAtTown() {
        val defaultBbox = Bbox(south = 5.0, west = -5.0, north = 25.0, east = 15.0)
        assertEquals(12, ZoomCap.pickZoomMax(defaultBbox))
    }

    /** Whole-world bbox → caps low so the cell count stays under budget. */
    @Test
    fun pickZoomMax_wholeWorld_capsRegionOrLower() {
        val worldBbox = Bbox(south = -85.0, west = -180.0, north = 85.0, east = 180.0)
        val capped = ZoomCap.pickZoomMax(worldBbox)
        assertTrue("whole-world should cap at z=8 or lower; got z=$capped", capped <= 8)
    }

    /** Below-budget cap (1 cell budget) → returns zMin. */
    @Test
    fun pickZoomMax_tinyBudget_clampsToZMin() {
        val anyBbox = Bbox(south = -0.5, west = -0.5, north = 0.5, east = 0.5)
        assertEquals(ZoomCap.MIN_ZOOM, ZoomCap.pickZoomMax(anyBbox, cellBudget = 1))
    }

    /** Cell count monotonically non-decreases with zoom for a fixed bbox. */
    @Test
    fun cellsInBboxAtZoom_monotonicallyIncreasesWithZoom() {
        val bbox = Bbox(south = -0.5, west = -0.5, north = 0.5, east = 0.5)
        var prev = 0L
        for (z in 6..14) {
            val cells = ZoomCap.cellsInBboxAtZoom(bbox, z)
            assertTrue("z=$z cells=$cells should be ≥ z=${z - 1} cells=$prev", cells >= prev)
            prev = cells
        }
    }

    /** Cell count multiplied by ~4 per zoom level for a square bbox at the equator.
     *  Use higher zooms (z=13 → z=14) so fence-post integer truncation isn't
     *  dominant — at low zooms the ratio drifts well below 4× because cell
     *  counts are tiny (e.g. 16 → 36 at z10→z11 = only 2.25×). */
    @Test
    fun cellsInBboxAtZoom_approxQuadruplesPerZoom() {
        val bbox = Bbox(south = -0.5, west = -0.5, north = 0.5, east = 0.5)
        val z13 = ZoomCap.cellsInBboxAtZoom(bbox, 13)
        val z14 = ZoomCap.cellsInBboxAtZoom(bbox, 14)
        val ratio = z14.toDouble() / z13.toDouble()
        assertTrue("expected ~4× growth z13→z14, got z13=$z13 z14=$z14 ratio=$ratio",
            ratio in 3.5..4.5)
    }

    /** Latitude clamping at ±85.0511° — north=90 doesn't blow up `tan(π/2)`. */
    @Test
    fun cellsInBboxAtZoom_polarBboxDoesNotBlowUp() {
        val polarBbox = Bbox(south = 80.0, west = -10.0, north = 90.0, east = 10.0)
        val cells = ZoomCap.cellsInBboxAtZoom(polarBbox, 10)
        assertTrue("polar bbox should still produce finite cell count; got $cells", cells >= 0)
    }
}
