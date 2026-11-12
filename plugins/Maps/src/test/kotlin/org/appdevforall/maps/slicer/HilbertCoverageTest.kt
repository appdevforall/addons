package org.appdevforall.maps.slicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Branch coverage for [Hilbert] guard arms not reached by the roundtrip test:
 * out-of-range zoom in `accumulate`, out-of-range zoom/coords in `zxyToTileId`,
 * negative `tileId` in `tileIdToZxy`, plus all four quadrant rotations of
 * `hilbertXyToD` / `hilbertDToXy` (rx/ry combinations) via an explicit z=2 sweep.
 */
class HilbertCoverageTest {

    @Test
    fun accumulate_rejects_negative_zoom() {
        assertThrows(IllegalArgumentException::class.java) { Hilbert.accumulate(-1) }
    }

    @Test
    fun accumulate_rejects_zoom_above_26() {
        assertThrows(IllegalArgumentException::class.java) { Hilbert.accumulate(27) }
    }

    @Test
    fun zxyToTileId_rejects_zoom_out_of_range() {
        assertThrows(IllegalArgumentException::class.java) { Hilbert.zxyToTileId(27, 0, 0) }
    }

    @Test
    fun zxyToTileId_rejects_x_out_of_range() {
        // At z=1, n=2, valid x/y in 0..1; x=2 is out of range.
        assertThrows(IllegalArgumentException::class.java) { Hilbert.zxyToTileId(1, 2, 0) }
    }

    @Test
    fun zxyToTileId_rejects_y_out_of_range() {
        assertThrows(IllegalArgumentException::class.java) { Hilbert.zxyToTileId(1, 0, 2) }
    }

    @Test
    fun tileIdToZxy_rejects_negative() {
        assertThrows(IllegalArgumentException::class.java) { Hilbert.tileIdToZxy(-1L) }
    }

    @Test
    fun z2_full_sweep_roundtrips_all_quadrants() {
        // A complete 4x4 sweep drives every rx/ry rotation branch in both the
        // forward (xy->d) and reverse (d->xy) Hilbert routines.
        for (x in 0..3) {
            for (y in 0..3) {
                val id = Hilbert.zxyToTileId(2, x, y)
                val (z2, x2, y2) = Hilbert.tileIdToZxy(id)
                assertEquals(2, z2)
                assertEquals(x, x2)
                assertEquals(y, y2)
            }
        }
    }

    @Test
    fun tileIdToZxy_finds_zoom_for_higher_levels() {
        // Drives the zoom-search while loop past the z==0 case multiple times.
        val id = Hilbert.zxyToTileId(5, 17, 9)
        val (z, x, y) = Hilbert.tileIdToZxy(id)
        assertEquals(5, z)
        assertEquals(17, x)
        assertEquals(9, y)
    }
}
