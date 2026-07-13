package org.appdevforall.maps.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomFitTest {

    @Test
    fun `a smaller bbox fits at a higher zoom than a larger one`() {
        val small = Bbox.aroundPoint(0.0, 0.0, 10.0)
        val large = Bbox.aroundPoint(0.0, 0.0, 1000.0)
        assertTrue(ZoomFit.fitZoom(small, 1080, 0.5) > ZoomFit.fitZoom(large, 1080, 0.5))
    }

    @Test
    fun `zoom is clamped to 0_16`() {
        val tiny = Bbox.aroundPoint(0.0, 0.0, 0.001)
        val huge = Bbox.aroundPoint(0.0, 0.0, 20000.0)
        assertTrue(ZoomFit.fitZoom(tiny, 1080, 0.5) <= 16.0)
        assertTrue(ZoomFit.fitZoom(huge, 1080, 0.5) >= 0.0)
    }

    @Test
    fun `non-positive map width falls back to 1080 (no crash, finite zoom)`() {
        val z = ZoomFit.fitZoom(Bbox.aroundPoint(0.0, 0.0, 100.0), mapWidthPx = 0, screenFraction = 0.5)
        assertTrue(z.isFinite())
        assertEquals(ZoomFit.fitZoom(Bbox.aroundPoint(0.0, 0.0, 100.0), 1080, 0.5), z, 1e-9)
    }
}
