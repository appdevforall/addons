package org.appdevforall.maps.domain

/**
 * Region size info derived from
 * [org.appdevforall.maps.slicer.PmtilesRegionSlicer]'s directory walk. Summing
 * real per-tile `byteLength` (rather than a uniform bytes-per-tile constant)
 * matters because tile sizes vary by orders of magnitude between dense-city
 * (~50 KB) and sparse-ocean (~0.5 KB) tiles.
 *
 * [sizeBytesEstimate] sums `TileEntry.byteLength` for every tile intersecting the
 * bbox at the selected zoom range; [tileCount] is the matching-entry count.
 *
 * A pure value type (not the slicer's raw `List<TileEntry>`) so wizard fragments
 * can pass it through their Listener interfaces without coupling to slicer types.
 */
data class TileEstimate(
    val tileCount: Long,
    val sizeBytesEstimate: Long,
    val zoomMin: Int,
    val zoomMax: Int
) {
    fun sizeMb(): Double = sizeBytesEstimate / (1024.0 * 1024.0)

    fun displayString(): String = "$tileCount tiles · %.1f MB · zoom %d–%d".format(
        sizeMb(), zoomMin, zoomMax
    )
}
