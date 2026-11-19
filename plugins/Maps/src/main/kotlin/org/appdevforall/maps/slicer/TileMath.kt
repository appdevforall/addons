package org.appdevforall.maps.slicer

import org.appdevforall.maps.domain.Bbox
import org.davidmoten.hilbert.HilbertCurve
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * Pure slippy-map ↔ Hilbert tile geometry for the PMTiles slicer.
 *
 * Extracted from [PmtilesRegionSlicer] so the orchestration (range fetching, coalescing,
 * writing) stays separate from the coordinate math. Everything here is pure — no IO, no
 * coroutines, no Android — so it's exhaustively JVM-unit-testable on its own.
 */
internal object TileMath {

    /**
     * The set of Hilbert tile-id ranges that exactly covers every (z, x, y) tile inside
     * [bbox] at zoom [z], expressed as a list of merged `[lo, hi]` ranges (PMTiles tile-id
     * space, i.e. including the zoom-base accumulator).
     *
     * Backed by davidmoten/hilbert-curve's [org.davidmoten.hilbert.SmallHilbertCurve.query],
     * which implements the Skilling 2004 perimeter-walk range-decomposition algorithm (Lawder
     * & King family). Returns `O(perimeter × log scale)` tight ranges instead of one loose
     * `O(area)` range — so the slicer's leaf-overlap pre-filter actually filters, instead of
     * marking ~30% of leaves as "potentially overlapping" for a 20° bbox.
     *
     * Verified to use the SAME Hilbert ordering as [Hilbert.zxyToTileId] via
     * DavidmotenHilbertCompatTest — so the returned ranges, after adding the zoom base
     * accumulator, are exactly leaf-id-comparable.
     */
    fun tileIdRangesForBbox(bbox: Bbox, z: Int): List<LongRange> {
        val n = 1 shl z
        val xMin = lonToTileX(bbox.west, z).coerceIn(0, n - 1)
        val xMax = lonToTileX(bbox.east, z).coerceIn(0, n - 1)
        val yMin = latToTileY(bbox.north, z).coerceIn(0, n - 1)
        val yMax = latToTileY(bbox.south, z).coerceIn(0, n - 1)
        if (xMin > xMax || yMin > yMax) return emptyList()

        // davidmoten's bits-per-dimension equals z (each dim takes z bits in a 2^z grid). 2D curve.
        val curve = HilbertCurve.small().bits(z).dimensions(2)
        val ranges = curve.query(
            longArrayOf(xMin.toLong(), yMin.toLong()),
            longArrayOf(xMax.toLong(), yMax.toLong()),
        )
        val base = Hilbert.accumulate(z)
        // Add the zoom base so the ranges live in PMTiles global tile-id space (matches the
        // ids encoded into leaf directories).
        return ranges.toList().map { (it.low() + base)..(it.high() + base) }
    }

    /** True iff the (z, x, y) slippy-map tile overlaps [bbox]. */
    fun tileIntersectsBbox(z: Int, x: Int, y: Int, bbox: Bbox): Boolean {
        val tileWest = tileToLon(x, z)
        val tileEast = tileToLon(x + 1, z)
        val tileNorth = tileToLat(y, z)
        val tileSouth = tileToLat(y + 1, z)
        return tileEast > bbox.west && tileWest < bbox.east &&
            tileNorth > bbox.south && tileSouth < bbox.north
    }

    fun tileToLon(x: Int, z: Int): Double {
        val n = 2.0.pow(z)
        return x / n * 360.0 - 180.0
    }

    fun tileToLat(y: Int, z: Int): Double {
        val n = 2.0.pow(z)
        val latRad = Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / n)))
        return Math.toDegrees(latRad)
    }

    private fun lonToTileX(lon: Double, z: Int): Int {
        val n = 2.0.pow(z)
        return floor((lon + 180.0) / 360.0 * n).toInt()
    }

    private fun latToTileY(lat: Double, z: Int): Int {
        val n = 2.0.pow(z)
        val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return floor((1 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n).toInt()
    }
}
