package org.appdevforall.maps.slicer

/**
 * Hilbert-curve (z, x, y) ↔ tile-ID conversion for PMTiles v3.
 *
 * Per the spec: every (z, x, y) tile is mapped to a single 64-bit tile_id.
 * The id is `accumulate(z) + hilbert_xy_to_d(2^z, x, y)`, where
 * `accumulate(z) = ((1 << 2z) - 1) / 3` is the count of all tile slots at
 * zooms 0..z-1.
 *
 * Reference algorithm: https://en.wikipedia.org/wiki/Hilbert_curve
 * (Algorithm "Convert (x,y) to d") — adapted from the PMTiles Python
 * reference implementation.
 */
internal object Hilbert {

    /** Tile ID space accumulator. Tiles at zooms 0..z-1 occupy ids `0..accumulate(z)-1`. */
    fun accumulate(z: Int): Long {
        require(z in 0..26) { "zoom $z out of range [0, 26]" }
        // ((1 << 2z) - 1) / 3 in 64-bit arithmetic
        return ((1L shl (2 * z)) - 1L) / 3L
    }

    /** Returns the (z, x, y) → tile-id mapping. */
    fun zxyToTileId(z: Int, x: Int, y: Int): Long {
        require(z in 0..26) { "zoom $z out of range" }
        val n = 1 shl z
        require(x in 0..(n - 1) && y in 0..(n - 1)) {
            "tile ($x, $y) out of range at zoom $z (max ${n - 1})"
        }
        return accumulate(z) + hilbertXyToD(n, x, y)
    }

    /** Reverse mapping: tile-id → (z, x, y). Used for sanity tests. */
    fun tileIdToZxy(tileId: Long): Triple<Int, Int, Int> {
        require(tileId >= 0) { "tileId $tileId must be nonnegative" }
        // Find the zoom level whose accumulator bracket contains tileId.
        var acc = 0L
        var z = 0
        while (true) {
            val nextAcc = accumulate(z + 1)
            if (tileId < nextAcc) {
                acc = accumulate(z)
                break
            }
            z++
            require(z <= 26) { "tileId $tileId implies zoom > 26" }
        }
        val n = 1 shl z
        val (x, y) = hilbertDToXy(n, tileId - acc)
        return Triple(z, x, y)
    }

    /**
     * Map an (x, y) coordinate on an n×n grid (n = power of 2) to its index
     * `d` along a Hilbert curve. From Wikipedia's reference pseudocode.
     */
    private fun hilbertXyToD(n: Int, xIn: Int, yIn: Int): Long {
        var x = xIn
        var y = yIn
        var d = 0L
        var s = n shr 1
        while (s > 0) {
            val rx = if ((x and s) > 0) 1 else 0
            val ry = if ((y and s) > 0) 1 else 0
            d += s.toLong() * s.toLong() * ((3 * rx) xor ry).toLong()
            // rotate
            if (ry == 0) {
                if (rx == 1) {
                    x = s - 1 - x
                    y = s - 1 - y
                }
                val t = x
                x = y
                y = t
            }
            s = s shr 1
        }
        return d
    }

    /** Reverse Hilbert: index d on an n×n grid → (x, y). */
    private fun hilbertDToXy(n: Int, dIn: Long): Pair<Int, Int> {
        var d = dIn
        var x = 0
        var y = 0
        var s = 1
        while (s < n) {
            val rx = (1L and (d shr 1)).toInt()
            val ry = (1L and (d xor rx.toLong())).toInt()
            if (ry == 0) {
                if (rx == 1) {
                    x = s - 1 - x
                    y = s - 1 - y
                }
                val t = x
                x = y
                y = t
            }
            x += s * rx
            y += s * ry
            d = d shr 2
            s = s shl 1
        }
        return x to y
    }
}
