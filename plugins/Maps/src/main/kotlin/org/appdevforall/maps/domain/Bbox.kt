package org.appdevforall.maps.domain

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow

/**
 * WGS84 bounding box, ordered south, west, north, east. All in degrees.
 *
 * The wizard's bbox picker drives this object directly; the tile estimator and
 * downloader read from it. A pure value type with no Android dependencies.
 *
 * `public` (not `internal`) only so the wizard's Fragment.Listener interfaces can
 * pass it through their callbacks without Kotlin's "exposes internal type"
 * diagnostic — it's still semantically plugin-internal.
 */
class Bbox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double
) {

    init {
        require(south <= north) { "south ($south) must be <= north ($north)" }
        require(west <= east) { "west ($west) must be <= east ($east) — anti-meridian crossings unsupported" }
    }

    /** Width of the box at its centre latitude, in kilometres. */
    fun widthKm(): Double {
        val midLat = (south + north) / 2.0
        return haversineKm(midLat, west, midLat, east)
    }

    /** Height of the box, in kilometres (latitude span × 111.32). */
    fun heightKm(): Double = haversineKm(south, west, north, west)

    /**
     * Approximate area of the box in square kilometres. Treats it as a
     * rectangle on the haversine-corrected width/height — accurate enough for
     * a tile-pack size estimate (single-digit-percent error inside the
     * reasonable mid-latitude range, well within "show MB estimate" tolerance).
     */
    fun areaKm2(): Double = widthKm() * heightKm()

    /**
     * True iff (lat, lon) sits inside the closed box (boundary inclusive).
     * Boundary inclusion matches the wizard's "did the user click inside the
     * picked region" semantics, where dragging exactly to the edge counts.
     *
     * Anti-meridian crossings are unsupported (asserted in init {}); callers
     * that need wrap-around must split into two boxes.
     */
    fun contains(lat: Double, lon: Double): Boolean =
        lat in south..north && lon in west..east

    fun toBoundsArray(): DoubleArray = doubleArrayOf(south, west, north, east)

    companion object {

        /**
         * Build a square bbox of [edgeKm] edge length centred on (`lat`, `lon`).
         * Latitude span is straight; longitude span is corrected for cos(latitude)
         * so the *physical* width matches.
         */
        fun aroundPoint(lat: Double, lon: Double, edgeKm: Double): Bbox {
            val halfDegLat = (edgeKm / 2.0) / 111.32
            val cosLat = max(0.01, cos(Math.toRadians(lat)))
            val halfDegLon = (edgeKm / 2.0) / (111.32 * cosLat)
            val south = (lat - halfDegLat).coerceIn(-85.0, 85.0)
            val north = (lat + halfDegLat).coerceIn(-85.0, 85.0)
            val west = (lon - halfDegLon).coerceIn(-180.0, 180.0)
            val east = (lon + halfDegLon).coerceIn(-180.0, 180.0)
            return Bbox(south, west, north, east)
        }
    }
}

/**
 * Compute the great-circle distance between two lat/lon points in kilometres.
 * Standard haversine; accurate enough for the wizard's "show MB estimate" UX
 * to single-digit-percent precision, which is all the user needs.
 */
internal fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0088
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLon / 2).pow(2)
    val c = 2 * kotlin.math.asin(kotlin.math.sqrt(a))
    return r * c
}
