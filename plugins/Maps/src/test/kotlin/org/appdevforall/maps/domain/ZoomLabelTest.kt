package org.appdevforall.maps.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the OSM-wiki zoom-name mapping. Every arm of the `when` is asserted at
 * its boundaries so a shifted range (e.g. `in 3..5` becoming `in 3..6`) fails
 * a test instead of silently mislabeling the bbox-dims line.
 */
class ZoomLabelTest {

    @Test
    fun `every range maps to its OSM label at both boundaries`() {
        val expected = listOf(
            0 to "world", 2 to "world",
            3 to "country", 5 to "country",
            6 to "country / state", 7 to "country / state",
            8 to "region",
            9 to "metro area", 10 to "metro area",
            11 to "city",
            12 to "town",
            13 to "village",
            14 to "streets",
            15 to "small roads", 16 to "small roads",
            17 to "buildings", 22 to "buildings",
        )
        for ((z, label) in expected) {
            assertEquals("zoom $z", label, ZoomLabel.forZoom(z))
        }
    }

    @Test
    fun `out-of-range zooms fall through to buildings`() {
        // Negative zoom can't occur from ZoomCap, but the mapping's else-arm is
        // the documented fallback either way.
        assertEquals("buildings", ZoomLabel.forZoom(99))
    }

    @Test
    fun `negative zoom hits the world arm`() {
        // `in 0..2` excludes negatives; they land in else. Pin the actual
        // behavior so a future "clamp negatives to world" change is a
        // deliberate, test-visible decision.
        assertEquals("buildings", ZoomLabel.forZoom(-1))
    }
}
