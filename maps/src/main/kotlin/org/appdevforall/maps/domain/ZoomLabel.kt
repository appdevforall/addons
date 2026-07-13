package org.appdevforall.maps.domain

/**
 * Human-readable name for a slippy-map zoom level, shown next to the bbox dimensions
 * (e.g. "max zoom level 12 (town)").
 *
 * Follows OpenStreetMap's "Zoom levels" wiki convention; for the levels OSM leaves blank
 * (8, 14, 16+) we use the standard Mapbox / Bing naming. Pure mapping — kept in `domain/`
 * so it's testable and reusable.
 */
object ZoomLabel {
    fun forZoom(z: Int): String = when (z) {
        in 0..2 -> "world"
        in 3..5 -> "country"
        in 6..7 -> "country / state"
        8 -> "region"
        in 9..10 -> "metro area"
        11 -> "city"
        12 -> "town"
        13 -> "village"
        14 -> "streets"
        in 15..16 -> "small roads"
        else -> "buildings"
    }
}
