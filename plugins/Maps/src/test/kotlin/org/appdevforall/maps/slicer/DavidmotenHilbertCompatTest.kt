package org.appdevforall.maps.slicer

import org.davidmoten.hilbert.HilbertCurve
import org.davidmoten.hilbert.SmallHilbertCurve
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that davidmoten/hilbert-curve produces the SAME Hilbert index for
 * (x, y) as our [Hilbert.zxyToTileId] minus the zoom-base accumulator.
 *
 * Why this matters: PmtilesRegionSlicer uses davidmoten's
 * `SmallHilbertCurve.query()` to compute tight tile-id ranges for a bbox. If
 * davidmoten's Hilbert ordering disagrees with ours, the resulting ranges
 * silently *miss* tiles (false negatives), and the slicer would return
 * incomplete results.
 *
 * Both implementations claim to follow the standard "convert (x,y) to d"
 * Hilbert curve. This test verifies that empirically across the (z, x, y)
 * space we care about.
 */
class DavidmotenHilbertCompatTest {

    @Test
    fun davidmotenIndex_matches_pmtilesHilbert_xyToD_atAllZooms() {
        for (z in 1..18) {
            val side = (1L shl z).toInt()
            val curve: SmallHilbertCurve = HilbertCurve.small().bits(z).dimensions(2)
            // Sample the full 4 corners + center + a handful of interior points.
            val samples = listOf(
                0 to 0,
                side - 1 to 0,
                0 to side - 1,
                side - 1 to side - 1,
                side / 2 to side / 2,
                side / 3 to (2 * side / 5),
                (3 * side / 7) to (side / 11),
            ).filter { (x, y) -> x in 0 until side && y in 0 until side }

            for ((x, y) in samples) {
                val ours = Hilbert.zxyToTileId(z, x, y) - Hilbert.accumulate(z)
                val theirs = curve.index(x.toLong(), y.toLong())
                assertEquals(
                    "Hilbert mismatch at z=$z (x=$x, y=$y): ours=$ours, davidmoten=$theirs",
                    ours,
                    theirs,
                )
            }
        }
    }
}
