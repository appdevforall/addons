package org.appdevforall.maps.domain

/**
 * Pure math behind the bbox picker's "shrink bbox to fit viewport" behavior.
 * No Android / MapLibre dependencies.
 *
 * Mirrors Google Maps' selection-tracks-viewport behavior:
 *  - Triggered ~1s after the camera goes idle (debounced inside the picker).
 *  - Acts ONLY when the bbox has any edge outside the current viewport.
 *  - On act, returns a new bbox filling the viewport minus a `margin` inset on
 *    every side.
 *
 * The "is bbox fully inside viewport" check naturally no-ops the cases that
 * shouldn't change the selection:
 *  - Zoom-out: viewport grows, bbox stays fully inside → no-op.
 *  - Pan while bbox stays on-screen → no-op.
 */
internal object AutoShrinkBbox {

    /**
     * Compute the new bbox to apply if a shrink is warranted, or `null` if
     * the existing [bbox] is already fully inside the viewport.
     *
     * @param bbox the current selection
     * @param viewN viewport northern latitude
     * @param viewS viewport southern latitude (must be < viewN)
     * @param viewE viewport eastern longitude
     * @param viewW viewport western longitude (must be < viewE — anti-meridian
     *   crossings are out of scope here and return `null`)
     * @param margin fraction of viewport span to inset on each side (e.g. 0.15
     *   for 15% margin). Clamped to [0, 0.45] so the result is always
     *   non-degenerate.
     */
    fun computeShrunkBbox(
        bbox: Bbox,
        viewN: Double,
        viewS: Double,
        viewE: Double,
        viewW: Double,
        margin: Double,
    ): Bbox? {
        // Degenerate viewport — punt.
        if (viewN <= viewS || viewE <= viewW) return null
        // Anti-meridian crossings out of scope (matches Bbox's init {} check).
        // Already fully inside? Nothing to do.
        if (bbox.south >= viewS && bbox.north <= viewN &&
            bbox.west >= viewW && bbox.east <= viewE
        ) return null

        val m = margin.coerceIn(0.0, 0.45)
        val latSpan = viewN - viewS
        val lonSpan = viewE - viewW
        val padLat = latSpan * m
        val padLon = lonSpan * m
        val newS = (viewS + padLat).coerceIn(-85.0, 85.0)
        val newN = (viewN - padLat).coerceIn(-85.0, 85.0)
        val newW = (viewW + padLon).coerceIn(-180.0, 180.0)
        val newE = (viewE - padLon).coerceIn(-180.0, 180.0)
        // Defensive: numeric precision could collapse the box near the poles.
        if (newN <= newS || newE <= newW) return null
        return Bbox(south = newS, west = newW, north = newN, east = newE)
    }
}
