package org.appdevforall.maps.slicer

import org.appdevforall.maps.domain.Bbox

/**
 * Process-wide cache for slicer estimates keyed by `(sourceUrl, bbox, zoom range)`.
 *
 * Step 2's bbox picker hits this on every debounced drag-end so the user
 * sees real bytes rather than a synthetic `tileCount × 50 KB`. Step 3 reads
 * the same cache entry on entry — the spec calls for one cached result
 * shared across both surfaces, not two independent slicer walks.
 *
 * The cache key is the **rounded** bbox + zoom range, so tiny mouse jitter
 * doesn't cause a re-walk. Rounding is to 4 decimal places (~11 m at the
 * equator), which is well below tile-edge precision at z14 (~2.4 m / tile
 * pixel at z19, ~76 m at z14).
 *
 * Eviction: LRU bound of 16 entries — enough for ~3-4 regions of bbox
 * exploration before the oldest entry falls out.
 */
internal object SliceEstimateCache {

    private const val MAX_ENTRIES = 16
    private const val ROUND_DECIMALS = 4

    /** Cached value: tile entries (for the downloader to read directly later). */
    private val cache = object : LinkedHashMap<Key, List<TileEntry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, List<TileEntry>>?): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(sourceUrl: String, bbox: Bbox, zoomMin: Int, zoomMax: Int): List<TileEntry>? =
        cache[Key(sourceUrl, bbox.rounded(), zoomMin, zoomMax)]

    @Synchronized
    fun put(sourceUrl: String, bbox: Bbox, zoomMin: Int, zoomMax: Int, value: List<TileEntry>) {
        cache[Key(sourceUrl, bbox.rounded(), zoomMin, zoomMax)] = value
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }

    private fun Bbox.rounded(): RoundedBbox {
        val scale = Math.pow(10.0, ROUND_DECIMALS.toDouble())
        return RoundedBbox(
            Math.round(south * scale) / scale,
            Math.round(west * scale) / scale,
            Math.round(north * scale) / scale,
            Math.round(east * scale) / scale,
        )
    }

    private data class RoundedBbox(val s: Double, val w: Double, val n: Double, val e: Double)
    private data class Key(val url: String, val box: RoundedBbox, val zMin: Int, val zMax: Int)
}
