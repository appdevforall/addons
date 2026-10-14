package org.appdevforall.maps.domain

import kotlin.math.cos
import kotlin.math.log2

/**
 * Web-Mercator "zoom that fits a bbox to a fraction of the viewport" math.
 *
 * Extracted from `BboxPickerFragment` so the calculation isn't a business decision living in a
 * Fragment — it's pure (inputs in, zoom out), no Android, JVM-testable.
 */
object ZoomFit {

    /** Web-Mercator meters-per-pixel at zoom 0 at the equator (2 × π × earthRadius / 256). */
    private const val MERCATOR_M_PER_PX_Z0 = 156543.034

    /**
     * The zoom level at which [bbox] occupies [screenFraction] of a [mapWidthPx]-wide viewport.
     * Clamped to `[0, 16]`. A non-positive [mapWidthPx] (view not laid out yet) falls back to 1080.
     */
    fun fitZoom(bbox: Bbox, mapWidthPx: Int, screenFraction: Double): Double {
        val centerLat = (bbox.south + bbox.north) / 2.0
        val widthMeters = bbox.widthKm() * 1000.0
        val targetPx = (mapWidthPx.takeIf { it > 0 } ?: 1080) * screenFraction
        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.01)
        val twoToZ = MERCATOR_M_PER_PX_Z0 * cosLat * targetPx / widthMeters
        return log2(twoToZ).coerceIn(0.0, 16.0)
    }
}
