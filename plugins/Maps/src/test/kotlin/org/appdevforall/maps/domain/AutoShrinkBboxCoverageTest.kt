package org.appdevforall.maps.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Branch-coverage supplement for [AutoShrinkBbox] — covers the decision branches
 * the happy-path test (`AutoShrinkBboxTest`) doesn't reach: the negative-margin
 * lower clamp, the per-axis "edge outside viewport" triggers, and the defensive
 * pole-collapse guard that returns null when latitude clamping degenerates the box.
 */
class AutoShrinkBboxCoverageTest {

    private fun box(s: Double, w: Double, n: Double, e: Double) =
        Bbox(south = s, west = w, north = n, east = e)

    @Test
    fun `negative margin is clamped up to zero — result equals the full viewport`() {
        // margin < 0 clamps to 0.0, so no inset: the shrunk bbox is the viewport itself.
        val bbox = box(s = -5.0, w = -5.0, n = 5.0, e = 5.0)
        val result = AutoShrinkBbox.computeShrunkBbox(
            bbox = bbox,
            viewN = 1.0, viewS = -1.0, viewE = 1.0, viewW = -1.0,
            margin = -0.5,
        )
        assertNotNull(result)
        assertEquals(-1.0, result!!.south, 1e-9)
        assertEquals(1.0, result.north, 1e-9)
        assertEquals(-1.0, result.west, 1e-9)
        assertEquals(1.0, result.east, 1e-9)
    }

    @Test
    fun `bbox north edge above viewport triggers shrink`() {
        // Only the north edge overflows; the other three are inside.
        val bbox = box(s = -0.5, w = -0.5, n = 2.0, e = 0.5)
        val result = AutoShrinkBbox.computeShrunkBbox(
            bbox = bbox,
            viewN = 1.0, viewS = -1.0, viewE = 1.0, viewW = -1.0,
            margin = 0.0,
        )
        assertNotNull("north-edge overflow must shrink", result)
    }

    @Test
    fun `bbox south edge below viewport triggers shrink`() {
        val bbox = box(s = -2.0, w = -0.5, n = 0.5, e = 0.5)
        val result = AutoShrinkBbox.computeShrunkBbox(
            bbox = bbox,
            viewN = 1.0, viewS = -1.0, viewE = 1.0, viewW = -1.0,
            margin = 0.0,
        )
        assertNotNull("south-edge overflow must shrink", result)
    }

    @Test
    fun `bbox west edge left of viewport triggers shrink`() {
        val bbox = box(s = -0.5, w = -2.0, n = 0.5, e = 0.5)
        val result = AutoShrinkBbox.computeShrunkBbox(
            bbox = bbox,
            viewN = 1.0, viewS = -1.0, viewE = 1.0, viewW = -1.0,
            margin = 0.0,
        )
        assertNotNull("west-edge overflow must shrink", result)
    }

    @Test
    fun `pole-clamp collapse returns null`() {
        // A viewport spanning across the +85 Mercator pole limit with a margin so
        // small the inset can't separate the latitude edges after the ±85 clamp:
        // newS and newN both clamp to 85.0, so newN <= newS → defensive null.
        val bbox = box(s = 80.0, w = -1.0, n = 89.0, e = 1.0)
        val result = AutoShrinkBbox.computeShrunkBbox(
            bbox = bbox,
            // Both viewN and viewS above the +85 clamp ceiling. After the tiny
            // inset, newS and newN both coerce to 85.0 → collapse.
            viewN = 89.0, viewS = 86.0, viewE = 1.0, viewW = -1.0,
            margin = 0.0,
        )
        assertNull("latitude clamp collapses the box → defensive null", result)
    }
}
