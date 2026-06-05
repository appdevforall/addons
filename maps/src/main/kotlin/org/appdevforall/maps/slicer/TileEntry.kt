package org.appdevforall.maps.slicer

/**
 * One tile within a global PMTiles archive that intersects a region.
 *
 * `byteOffset` is **absolute within the global file** — it is `header.tileDataOffset
 * + dirEntry.offset`. `byteLength` is the on-the-wire compressed size (pmtiles
 * stores already-compressed tile bytes, so this is what an HTTP Range will fetch).
 *
 * Note: multiple [TileEntry]s with different `(z, x, y)` may share the same
 * `(byteOffset, byteLength)`. The PMTiles v3 format does content-level dedup of
 * tile bytes — e.g. blank ocean tiles all point at one blob. The slicer must
 * preserve this when writing a sliced archive (write each unique tile-content
 * once, share offsets in the sliced directory).
 */
internal data class TileEntry(
    val z: Int,
    val x: Int,
    val y: Int,
    val tileId: Long,
    val byteOffset: Long,
    val byteLength: Long,
)
