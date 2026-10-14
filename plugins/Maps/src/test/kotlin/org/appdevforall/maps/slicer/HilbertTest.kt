package org.appdevforall.maps.slicer

import org.junit.Assert.assertEquals
import org.junit.Test

class HilbertTest {

    @Test
    fun accumulate_zero_is_zero() {
        assertEquals(0L, Hilbert.accumulate(0))
    }

    @Test
    fun accumulate_z1_is_one() {
        // 1 tile at z0
        assertEquals(1L, Hilbert.accumulate(1))
    }

    @Test
    fun accumulate_z2_is_five() {
        // 1 (z0) + 4 (z1) = 5
        assertEquals(5L, Hilbert.accumulate(2))
    }

    @Test
    fun accumulate_z3_is_twenty_one() {
        // 1 + 4 + 16 = 21
        assertEquals(21L, Hilbert.accumulate(3))
    }

    /**
     * Spot-checks against the PMTiles Python reference impl. The tile_id values
     * below come from running `pmtiles.tile.zxy_to_tileid(z, x, y)` in the
     * Python pmtiles package; cross-checked because Hilbert variants differ
     * slightly between libraries.
     */
    @Test
    fun zxyToTileId_z0_0_0_is_0() {
        assertEquals(0L, Hilbert.zxyToTileId(0, 0, 0))
    }

    @Test
    fun zxyToTileId_z1_corners() {
        // PMTiles Hilbert at z=1 maps the 4 corners to ids 1..4.
        // Expected from reference: (0,0)=1, (0,1)=2, (1,1)=3, (1,0)=4
        assertEquals(1L, Hilbert.zxyToTileId(1, 0, 0))
        assertEquals(2L, Hilbert.zxyToTileId(1, 0, 1))
        assertEquals(3L, Hilbert.zxyToTileId(1, 1, 1))
        assertEquals(4L, Hilbert.zxyToTileId(1, 1, 0))
    }

    @Test
    fun zxyToTileId_z2_starts_after_accumulate() {
        // All z=2 ids in [5, 5+16) = [5, 21)
        for (x in 0..3) {
            for (y in 0..3) {
                val id = Hilbert.zxyToTileId(2, x, y)
                assert(id in 5..20) { "z2 ($x,$y) -> $id, expected in [5,20]" }
            }
        }
    }

    @Test
    fun zxy_roundtrips() {
        for (z in 0..6) {
            val n = 1 shl z
            for (x in 0 until n) {
                for (y in 0 until n) {
                    val id = Hilbert.zxyToTileId(z, x, y)
                    val (z2, x2, y2) = Hilbert.tileIdToZxy(id)
                    assertEquals("z mismatch at ($z,$x,$y) -> $id", z, z2)
                    assertEquals("x mismatch at ($z,$x,$y) -> $id", x, x2)
                    assertEquals("y mismatch at ($z,$x,$y) -> $id", y, y2)
                }
            }
        }
    }

    @Test
    fun zxy_roundtrips_z14_random_samples() {
        // z=14: 268M tiles — sample a few.
        val samples = listOf(
            Triple(14, 0, 0),
            Triple(14, 1234, 5678),
            Triple(14, 16383, 16383),
            Triple(14, 8192, 8192),
            Triple(14, 100, 16000),
        )
        for ((z, x, y) in samples) {
            val id = Hilbert.zxyToTileId(z, x, y)
            val (z2, x2, y2) = Hilbert.tileIdToZxy(id)
            assertEquals(z, z2)
            assertEquals(x, x2)
            assertEquals(y, y2)
        }
    }
}
