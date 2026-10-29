package org.appdevforall.maps.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the auto-shrink math behind the bbox picker's
 * "selection tracks viewport" tweak (Bryan, 2026-05-26).
 *
 * Naming convention: `viewport(N, S, E, W)` reads as the visible map area in
 * lat/lon degrees. `bbox(N, S, E, W)` reads as the user's current selection.
 *
 * Margin is 0.35 throughout (the picker's default — leaves the auto-shrunk
 * bbox at ~30% of the viewport so the user has grab-room to pan).
 */
class AutoShrinkBboxTest {

    private val M = 0.35

    private fun box(s: Double, w: Double, n: Double, e: Double) = Bbox(
        south = s, west = w, north = n, east = e,
    )

    // ----- the two cases Bryan explicitly said should NOT trigger -----

    @Test
    fun `zoom-out keeps bbox fully visible — no shrink`() {
        // User started with a 1° box centred at (0, 0). They zoomed out and
        // the viewport is now 10° on a side. Bbox is comfortably inside.
        val bbox = box(s = -0.5, w = -0.5, n = 0.5, e = 0.5)
        val result = AutoShrinkBbox.computeShrunkBbox(
            bbox = bbox,
            viewN = 5.0, viewS = -5.0, viewE = 5.0, viewW = -5.0,
            margin = M,
        )
        assertNull("bbox fully inside viewport must not trigger shrink", result)
    }

    @Test
    fun `pan but bbox still fully visible — no shrink`() {
        // User panned the camera a bit. Viewport moved with it, bbox is
        // still entirely inside. Should be a no-op.
        val bbox = box(s = 0.0, w = 0.0, n = 1.0, e = 1.0)
        val result = AutoShrinkBbox.computeShrunkBbox(
            bbox = bbox,
            viewN = 3.0, viewS = -1.0, viewE = 3.0, viewW = -1.0,
            margin = M,
        )
        assertNull("bbox still on-screen must not trigger shrink", result)
    }

    // ----- the cases that SHOULD trigger -----

    @Test
    fun `zoom-in past bbox extent — shrinks bbox to viewport minus margin`() {
        // Bbox is 10° on a side; user zoomed in so viewport is only 2° on a
        // side. Bbox extends way past the viewport on all axes.
        val bbox = box(s = -5.0, w = -5.0, n = 5.0, e = 5.0)
        val result = AutoShrinkBbox.computeShrunkBbox(
            bbox = bbox,
            viewN = 1.0, viewS = -1.0, viewE = 1.0, viewW = -1.0,
            margin = M,
        )
        assertNotNull("zoom-in past bbox should shrink", result)
        // 35% inset on a 2° viewport = 0.7° inset on each side.
        assertEquals(-0.3, result!!.south, 1e-9)
        assertEquals(0.3, result.north, 1e-9)
        assertEquals(-0.3, result.west, 1e-9)
        assertEquals(0.3, result.east, 1e-9)
    }

    @Test
    fun `pan so one edge crosses viewport — shrinks`() {
        // Viewport is 2° wide centred at lon=0. Bbox extends from lon=-0.5
        // to lon=2.5 — its east edge (2.5) is outside viewport east (1.0).
        val bbox = box(s = -0.5, w = -0.5, n = 0.5, e = 2.5)
        val result = AutoShrinkBbox.computeShrunkBbox(
            bbox = bbox,
            viewN = 1.0, viewS = -1.0, viewE = 1.0, viewW = -1.0,
            margin = M,
        )
        assertNotNull("partial offscreen should shrink", result)
        // New bbox = viewport inset by 35% on each side.
        assertEquals(-0.3, result!!.south, 1e-9)
        assertEquals(0.3, result.north, 1e-9)
        assertEquals(-0.3, result.west, 1e-9)
        assertEquals(0.3, result.east, 1e-9)
    }

    @Test
    fun `bbox exactly at viewport edge — counts as inside, no shrink`() {
        // Boundary-inclusive: bbox edges touch viewport edges exactly. Should
        // NOT trigger (no overflow). Matches Bbox.contains semantics.
        val bbox = box(s = -1.0, w = -1.0, n = 1.0, e = 1.0)
        val result = AutoShrinkBbox.computeShrunkBbox(
            bbox = bbox,
            viewN = 1.0, viewS = -1.0, viewE = 1.0, viewW = -1.0,
            margin = M,
        )
        assertNull("bbox flush with viewport edges must not shrink", result)
    }

    // ----- margin semantics -----

    @Test
    fun `15 percent margin leaves 70 percent of viewport`() {
        // 0° to 100° wide viewport → 15° inset on each side → result is
        // 15° to 85° = 70° wide = 70% of viewport.
        val bbox = box(s = -50.0, w = -50.0, n = 50.0, e = 50.0)  // way bigger
        val result = AutoShrinkBbox.computeShrunkBbox(
            bbox = bbox,
            viewN = 100.0, viewS = 0.0, viewE = 100.0, viewW = 0.0,
            margin = 0.15,
        )!!
        assertEquals(15.0, result.south, 1e-9)
        assertEquals(85.0, result.north, 1e-9)
        assertEquals(15.0, result.west, 1e-9)
        assertEquals(85.0, result.east, 1e-9)
        // Verify percentages.
        val resultLatSpan = result.north - result.south
        val resultLonSpan = result.east - result.west
        assertEquals(70.0, resultLatSpan, 1e-9)
        assertEquals(70.0, resultLonSpan, 1e-9)
    }

    @Test
    fun `margin is clamped at 0_45 to prevent degenerate result`() {
        // Margin > 0.45 (which would inset 90%+ → degenerate) gets clamped.
        // 0.45 on a 2°-wide viewport = 0.9° inset on each side
        // → result span = 2° - 2 × 0.9° = 0.2°.
        val bbox = box(s = -5.0, w = -5.0, n = 5.0, e = 5.0)
        val result = AutoShrinkBbox.computeShrunkBbox(
            bbox = bbox,
            viewN = 1.0, viewS = -1.0, viewE = 1.0, viewW = -1.0,
            margin = 0.9,  // would be 90% inset → degenerate without clamping
        )!!
        assertEquals(0.2, result.north - result.south, 1e-9)
        assertEquals(0.2, result.east - result.west, 1e-9)
    }

    @Test
    fun `degenerate viewport returns null`() {
        // E < W or N < S (could happen on uninitialized projection).
        val bbox = box(s = -1.0, w = -1.0, n = 1.0, e = 1.0)
        // Degenerate N<=S.
        assertNull(
            AutoShrinkBbox.computeShrunkBbox(
                bbox = bbox,
                viewN = 0.0, viewS = 0.0, viewE = 1.0, viewW = -1.0,
                margin = M,
            )
        )
        // Degenerate E<=W (anti-meridian crossing or stale viewport).
        assertNull(
            AutoShrinkBbox.computeShrunkBbox(
                bbox = bbox,
                viewN = 1.0, viewS = -1.0, viewE = -1.0, viewW = 1.0,
                margin = M,
            )
        )
    }
}
