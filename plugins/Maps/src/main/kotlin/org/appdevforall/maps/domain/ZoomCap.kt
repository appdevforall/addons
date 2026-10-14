package org.appdevforall.maps.domain

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * Auto-cap heuristic for the bbox-picker's `zoomMax`. Picks the highest
 * zoom whose cumulative cell count across `zMin..zMax` stays under a
 * configurable budget.
 *
 * Pure Kotlin / Web-Mercator math; no Android dependencies.
 */
internal object ZoomCap {

    const val DEFAULT_CELL_BUDGET = 100_000
    const val MIN_ZOOM = 6
    const val MAX_ZOOM = 14

    /**
     * Highest `zMax` in `[zMin, MAX_ZOOM]` where the total cell count
     * summed across `z=zMin..zMax` ≤ [cellBudget]. If even `zMin` exceeds
     * the budget, returns `zMin` (caller's downstream 1 GB cap is the next
     * line of defense).
     */
    fun pickZoomMax(
        bbox: Bbox,
        zMin: Int = MIN_ZOOM,
        cellBudget: Int = DEFAULT_CELL_BUDGET,
    ): Int {
        for (zMax in MAX_ZOOM downTo zMin) {
            val totalCells = (zMin..zMax).sumOf { z -> cellsInBboxAtZoom(bbox, z) }
            if (totalCells <= cellBudget) return zMax
        }
        return zMin
    }

    /**
     * Number of slippy-map tiles whose `(z, x, y)` rectangle intersects
     * [bbox] at zoom level [z]. Standard Web Mercator tile-x / tile-y
     * math, latitude clamped at ±85.0511° (the Mercator pole limit).
     *
     * Returns `Long` because the count grows as `4^z` and overflows `Int`
     * past z≈15 for world-scale bboxes.
     */
    fun cellsInBboxAtZoom(bbox: Bbox, z: Int): Long {
        val n = 1 shl z
        val xMin = ((bbox.west + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val xMax = ((bbox.east + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val latNorthRad = Math.toRadians(bbox.north.coerceIn(-85.0511, 85.0511))
        val latSouthRad = Math.toRadians(bbox.south.coerceIn(-85.0511, 85.0511))
        val yMin = ((1 - ln(tan(latNorthRad) + 1.0 / cos(latNorthRad)) / Math.PI) / 2.0 * n)
            .toInt().coerceIn(0, n - 1)
        val yMax = ((1 - ln(tan(latSouthRad) + 1.0 / cos(latSouthRad)) / Math.PI) / 2.0 * n)
            .toInt().coerceIn(0, n - 1)
        val xCount = (xMax - xMin + 1).toLong().coerceAtLeast(0)
        val yCount = (yMax - yMin + 1).toLong().coerceAtLeast(0)
        return xCount * yCount
    }
}
