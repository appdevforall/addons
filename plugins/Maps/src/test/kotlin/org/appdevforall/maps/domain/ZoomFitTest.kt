package org.appdevforall.maps.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

class ZoomFitTest {

    // The saved Lalibela region from the 10-27 device walk, and the Galaxy A06 map view in dp
    // (720 x 1600 px at density 1.875).
    private val lalibela = Bbox(9.961346470706417, 36.77746496147344, 14.919443564155998, 41.06244902809138)
    private val a06WidthDp = 384.0
    private val a06HeightDp = 853.3

    /** The bbox's on-screen size in dp at [zoom], from MapLibre's 512-dp-wide world, not ZoomFit's math. */
    private fun projectedSizeDp(bbox: Bbox, zoom: Double): Pair<Double, Double> {
        val worldDp = 512.0 * 2.0.pow(zoom)
        fun mercY(lat: Double) = ln(tan(PI / 4 + Math.toRadians(lat) / 2))
        val width = (bbox.east - bbox.west) / 360.0 * worldDp
        val height = (mercY(bbox.north) - mercY(bbox.south)) / (2 * PI) * worldDp
        return width to height
    }

    @Test
    fun `a saved region fits inside the requested fraction of the screen on both axes`() {
        val zoom = ZoomFit.fitZoom(lalibela, a06WidthDp, a06HeightDp, 0.5)
        val (width, height) = projectedSizeDp(lalibela, zoom)
        assertTrue("box is $width dp wide on a $a06WidthDp dp screen", width <= a06WidthDp * 0.5 * 1.02)
        assertTrue("box is $height dp tall on a $a06HeightDp dp screen", height <= a06HeightDp * 0.5 * 1.02)
        // A tight fit: the limiting axis fills the fraction rather than leaving the box tiny.
        assertEquals(a06WidthDp * 0.5, width, a06WidthDp * 0.02)
    }

    @Test
    fun `a box taller than the screen's shape is fitted by its height`() {
        val tall = Bbox(0.0, 0.0, 10.0, 1.0)
        val zoom = ZoomFit.fitZoom(tall, a06WidthDp, a06WidthDp, 0.5)
        val (_, height) = projectedSizeDp(tall, zoom)
        assertEquals(a06WidthDp * 0.5, height, a06WidthDp * 0.02)
    }

    @Test
    fun `a smaller bbox fits at a higher zoom than a larger one`() {
        val small = Bbox.aroundPoint(0.0, 0.0, 10.0)
        val large = Bbox.aroundPoint(0.0, 0.0, 1000.0)
        assertTrue(ZoomFit.fitZoom(small, 360.0, 640.0, 0.5) > ZoomFit.fitZoom(large, 360.0, 640.0, 0.5))
    }

    @Test
    fun `zoom is clamped to 0_16`() {
        val tiny = Bbox.aroundPoint(0.0, 0.0, 0.001)
        val huge = Bbox.aroundPoint(0.0, 0.0, 20000.0)
        assertTrue(ZoomFit.fitZoom(tiny, 360.0, 640.0, 0.5) <= 16.0)
        assertTrue(ZoomFit.fitZoom(huge, 360.0, 640.0, 0.5) >= 0.0)
    }

    @Test
    fun `a map view not laid out yet falls back to 360 x 640 dp`() {
        val box = Bbox.aroundPoint(0.0, 0.0, 100.0)
        val z = ZoomFit.fitZoom(box, viewWidthDp = 0.0, viewHeightDp = 0.0, screenFraction = 0.5)
        assertEquals(ZoomFit.fitZoom(box, 360.0, 640.0, 0.5), z, 1e-9)
    }
}
