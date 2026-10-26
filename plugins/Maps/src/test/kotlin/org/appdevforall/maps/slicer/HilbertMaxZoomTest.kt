package org.appdevforall.maps.slicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The deepest zoom PMTiles v3 allows must round-trip, and nothing past it may decode. */
class HilbertMaxZoomTest {

    private val last = (1 shl 26) - 1

    @Test
    fun `zoom 26 tile ids round-trip at the first and last tile`() {
        for ((x, y) in listOf(0 to 0, last to last, last to 0)) {
            val id = Hilbert.zxyToTileId(26, x, y)
            assertEquals(Triple(26, x, y), Hilbert.tileIdToZxy(id))
        }
    }

    @Test
    fun `an id past the last zoom 26 tile is rejected`() {
        val pastEnd = Hilbert.accumulate(26) + (1L shl 52)
        val thrown = assertThrows(IllegalArgumentException::class.java) { Hilbert.tileIdToZxy(pastEnd) }
        assertTrue(thrown.message!!.endsWith("implies zoom > 26"))
    }
}
