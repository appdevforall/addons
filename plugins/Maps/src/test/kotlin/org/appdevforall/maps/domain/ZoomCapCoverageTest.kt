package org.appdevforall.maps.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch-coverage supplement for [ZoomCap] — drives the Web-Mercator tile-math
 * clamps in [ZoomCap.cellsInBboxAtZoom] (x/y coerced into `[0, n-1]` at the map
 * edges) and the [ZoomCap.pickZoomMax] decision branches at the budget boundary
 * and at the zMin == MAX_ZOOM degenerate range.
 */
class ZoomCapCoverageTest {

    @Test
    fun `world-edge bbox clamps tile x and y into range`() {
        // east=180 / north=85.0511 push the raw tile index to exactly n, so the
        // coerceIn(0, n-1) upper clamp fires; west=-180 / south=-85.0511 push the
        // lower bound to 0. Result is the full tile grid at that zoom (n x n).
        val world = Bbox(south = -85.0511, west = -180.0, north = 85.0511, east = 180.0)
        val z = 4
        val n = 1L shl z
        val cells = ZoomCap.cellsInBboxAtZoom(world, z)
        // Full grid (n columns x n rows) after the edge clamps.
        assertEquals(n * n, cells)
    }

    @Test
    fun `single-point-ish bbox yields exactly one cell at low zoom`() {
        // A pinpoint bbox collapses x/y min == max → xCount=1, yCount=1.
        // Use an INTERIOR point — (0,0) sits exactly on the z6 equator/prime-
        // meridian tile boundary, so a bbox there legitimately straddles 2 y-tiles.
        val tiny = Bbox(south = 23.0, west = 8.0, north = 23.0001, east = 8.0001)
        assertEquals(1L, ZoomCap.cellsInBboxAtZoom(tiny, 6))
    }

    @Test
    fun `pickZoomMax returns MAX_ZOOM when zMin equals MAX_ZOOM and under budget`() {
        // zMin == MAX_ZOOM makes the for-loop body run exactly once.
        val tiny = Bbox(south = 0.0, west = 0.0, north = 0.001, east = 0.001)
        val capped = ZoomCap.pickZoomMax(tiny, zMin = ZoomCap.MAX_ZOOM)
        assertEquals(ZoomCap.MAX_ZOOM, capped)
    }

    @Test
    fun `pickZoomMax picks an intermediate cap at a mid budget`() {
        // A budget that's exceeded at z=14 but satisfied lower forces the loop to
        // iterate down past the first candidate before returning.
        val bbox = Bbox(south = -1.0, west = -1.0, north = 1.0, east = 1.0)
        val capped = ZoomCap.pickZoomMax(bbox, cellBudget = 2_000)
        assertTrue("expected a mid cap in [6,14]; got $capped", capped in 6..14)
        // The chosen cap must actually satisfy the budget.
        val cells = (6..capped).sumOf { ZoomCap.cellsInBboxAtZoom(bbox, it) }
        assertTrue("chosen cap $capped must be under budget; cells=$cells", cells <= 2_000)
    }

    @Test
    fun `pickZoomMax with zMin above MAX_ZOOM skips the loop and returns zMin`() {
        // zMin > MAX_ZOOM makes `MAX_ZOOM downTo zMin` an empty range, so the
        // for-loop body never runs and the method falls straight through to the
        // `return zMin` tail (the loop-exhaust branch). This is the branch the
        // budget-boundary cases can't reach, because they always enter the loop.
        val bbox = Bbox(south = 0.0, west = 0.0, north = 0.001, east = 0.001)
        val capped = ZoomCap.pickZoomMax(bbox, zMin = ZoomCap.MAX_ZOOM + 1)
        assertEquals(ZoomCap.MAX_ZOOM + 1, capped)
    }

    @Test
    fun `pickZoomMax accepts a cap that exactly equals the budget`() {
        // Drive the `totalCells <= cellBudget` comparison at exact equality, so
        // the boundary `==` arm (not just strictly-less) returns rather than
        // continuing to iterate down. Compute the real cell total at z=zMin and
        // pass it verbatim as the budget.
        val bbox = Bbox(south = -1.0, west = -1.0, north = 1.0, east = 1.0)
        val zMin = 6
        val exactBudget = ZoomCap.cellsInBboxAtZoom(bbox, zMin).toInt()
        // At z=zMin the running total is exactly the budget → accept zMin (or
        // higher if a larger zMax still fits, but never below zMin).
        val capped = ZoomCap.pickZoomMax(bbox, zMin = zMin, cellBudget = exactBudget)
        assertTrue("cap must be at least zMin when zMin exactly fits; got $capped", capped >= zMin)
        val total = (zMin..capped).sumOf { ZoomCap.cellsInBboxAtZoom(bbox, it) }
        assertTrue("chosen total $total must satisfy the exact budget $exactBudget",
            total <= exactBudget)
    }

    @Test
    fun `constants expose the documented defaults`() {
        assertEquals(100_000, ZoomCap.DEFAULT_CELL_BUDGET)
        assertEquals(6, ZoomCap.MIN_ZOOM)
        assertEquals(14, ZoomCap.MAX_ZOOM)
    }
}
