package org.appdevforall.maps.slicer

/**
 * Constants and small helpers for the PMTiles v3 archive format.
 *
 * Reference: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
 *
 * Why we have our own reader (Path B): the existing community Java lib
 * `ch.poole.geo.pmtiles-reader:Reader:0.3.7` keeps directory entries
 * (`ids`, `lengths`, `offsets`) as package-private fields and only exposes
 * a per-tile `getTile(z, x, y)` API. For region slicing we need the *whole*
 * directory listing so we can:
 *   - estimate compressed bytes from the offsets+lengths (no tile fetch needed)
 *   - download a contiguous tile range and write a sliced PMTiles output
 * Driving `getTile()` per (z,x,y) inside a region would issue one or more
 * HTTP range requests per tile and discard all the directory data after.
 * A from-scratch v3 reader is ~400 lines and gives us exactly what we need.
 */
internal object PmtilesV3 {
    const val HEADER_BYTES = 127
    val MAGIC: ByteArray = byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 'T'.code.toByte(),
        'i'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte())
    const val VERSION: Byte = 3

    // Compression codes
    const val COMPRESSION_UNKNOWN: Byte = 0
    const val COMPRESSION_NONE: Byte = 1
    const val COMPRESSION_GZIP: Byte = 2
    const val COMPRESSION_BROTLI: Byte = 3
    const val COMPRESSION_ZSTD: Byte = 4

    // Tile types
    const val TYPE_UNKNOWN: Byte = 0
    const val TYPE_MVT: Byte = 1
    const val TYPE_PNG: Byte = 2
    const val TYPE_JPEG: Byte = 3
    const val TYPE_WEBP: Byte = 4
    const val TYPE_AVIF: Byte = 5

    fun compressionName(b: Byte): String = when (b) {
        COMPRESSION_NONE -> "none"
        COMPRESSION_GZIP -> "gzip"
        COMPRESSION_BROTLI -> "brotli"
        COMPRESSION_ZSTD -> "zstd"
        else -> "unknown($b)"
    }

    fun tileTypeName(b: Byte): String = when (b) {
        TYPE_MVT -> "mvt"
        TYPE_PNG -> "png"
        TYPE_JPEG -> "jpeg"
        TYPE_WEBP -> "webp"
        TYPE_AVIF -> "avif"
        else -> "unknown($b)"
    }
}
