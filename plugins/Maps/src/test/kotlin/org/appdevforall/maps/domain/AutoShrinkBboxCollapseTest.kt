package org.appdevforall.maps.domain

import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AutoShrinkBbox.computeShrunkBbox]'s defensive collapse arm: a viewport whose
 * longitudes both clamp to the same ±180 wall (wrapped/out-of-range longitudes
 * from the map SDK) must yield null — never an inverted or zero-width [Bbox]
 * (whose init would throw in the caller's face).
 */
class AutoShrinkBboxCollapseTest {

    @Test
    fun `viewport beyond the antimeridian wall collapses to null`() {
        // Both view longitudes are past -180; after coerceIn both become -180,
        // so newE <= newW — the collapse guard must return null. Latitudes are
        // healthy, so this isolates the longitude arm of the final check.
        val result = AutoShrinkBbox.computeShrunkBbox(
            bbox = Bbox(south = 0.0, west = 0.0, north = 10.0, east = 10.0),
            viewN = 5.0,
            viewS = -5.0,
            viewE = -185.0,
            viewW = -190.0,
            margin = 0.0,
        )
        assertNull("collapsed longitude span must yield null, not an invalid Bbox", result)
    }
}
