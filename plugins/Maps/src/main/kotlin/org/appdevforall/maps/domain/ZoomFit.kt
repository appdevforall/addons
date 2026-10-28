package org.appdevforall.maps.domain

import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.min

/**
 * Web-Mercator "zoom that fits a bbox to a fraction of the viewport" math.
 *
 * Extracted from `BboxPickerFragment` so the calculation isn't a business decision living in a
 * Fragment — it's pure (inputs in, zoom out), no Android, JVM-testable.
 */
object ZoomFit {

    /**
     * Meters per dp at zoom 0 at the equator in MapLibre's camera, whose world is 512 dp wide at
     * zoom 0 (2 × π × earthRadius / 512). Using the 256-px figure with physical pixels over-zooms
     * by 2 × density, which put a saved region's edges off screen on the Refresh flow.
     */
    private const val MERCATOR_M_PER_DP_Z0 = 78271.517

    /** Viewport assumed when the map view has not been laid out yet. */
    private const val FALLBACK_WIDTH_DP = 360.0
    private const val FALLBACK_HEIGHT_DP = 640.0

    /**
     * The zoom at which [bbox] fits inside [screenFraction] of a [viewWidthDp] × [viewHeightDp]
     * viewport in both directions. Clamped to `[0, 16]`; a non-positive size uses 360 × 640 dp.
     */
    fun fitZoom(bbox: Bbox, viewWidthDp: Double, viewHeightDp: Double, screenFraction: Double): Double {
        val widthDp = viewWidthDp.takeIf { it > 0 } ?: FALLBACK_WIDTH_DP
        val heightDp = viewHeightDp.takeIf { it > 0 } ?: FALLBACK_HEIGHT_DP
        val cosLat = cos(Math.toRadians((bbox.south + bbox.north) / 2.0)).coerceAtLeast(0.01)
        val metersPerDpAtZ0 = MERCATOR_M_PER_DP_Z0 * cosLat
        val zoomForWidth = log2(metersPerDpAtZ0 * widthDp * screenFraction / (bbox.widthKm() * 1000.0))
        val zoomForHeight = log2(metersPerDpAtZ0 * heightDp * screenFraction / (bbox.heightKm() * 1000.0))
        return min(zoomForWidth, zoomForHeight).coerceIn(0.0, 16.0)
    }
}
