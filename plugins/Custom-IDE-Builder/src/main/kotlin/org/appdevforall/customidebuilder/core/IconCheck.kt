package org.appdevforall.customidebuilder.core

/** Pixels as 0xAARRGGBB, row by row. The Android layer fills this from a Bitmap; nothing here needs Android. */
class PixelImage(val argb: IntArray, val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0 && argb.size == width * height) { "pixel count does not match ${width}x$height" }
    }

    fun mirrored(): PixelImage {
        val out = IntArray(argb.size)
        for (y in 0 until height) for (x in 0 until width) out[y * width + x] = argb[y * width + (width - 1 - x)]
        return PixelImage(out, width, height)
    }
}

/**
 * A difference hash of [GRID]x[GRID] bits in each direction. The first half holds "this cell is brighter than the
 * cell to its right" on a (GRID+1)xGRID grid, the second "brighter than the cell below" on a GRIDx(GRID+1) one.
 */
class IconHash(val bits: LongArray) {
    fun distance(other: IconHash): Int = bits.indices.sumOf { java.lang.Long.bitCount(bits[it] xor other.bits[it]) }

    /** Bits set. An image with no detail hashes to nearly none. */
    fun ones(): Int = bits.sumOf { java.lang.Long.bitCount(it) }

    override fun equals(other: Any?): Boolean = other is IconHash && bits.contentEquals(other.bits)

    override fun hashCode(): Int = bits.contentHashCode()

    companion object {
        const val GRID = 8
        const val BITS = 2 * GRID * GRID

        /** Brightness levels (of 255) one cell must exceed its neighbour by to set a bit, so flat areas and noise set none. */
        const val MARGIN = 4

        /** Transparent pixels count as white, so an icon and a copy with its background filled in white compare alike. */
        fun of(image: PixelImage, margin: Int = MARGIN, grid: Int = GRID): IconHash {
            val wide = grid(image, grid + 1, grid)
            val tall = grid(image, grid, grid + 1)
            val bits = LongArray((2 * grid * grid + 63) / 64)
            var k = 0
            fun set() { bits[k / 64] = bits[k / 64] or (1L shl (k % 64)) }
            for (r in 0 until grid) for (c in 0 until grid) {
                if (wide[r * (grid + 1) + c] > wide[r * (grid + 1) + c + 1] + margin) set()
                k++
            }
            for (r in 0 until grid) for (c in 0 until grid) {
                if (tall[r * grid + c] > tall[(r + 1) * grid + c] + margin) set()
                k++
            }
            return IconHash(bits)
        }

        /** Mean brightness (0-255) of each of cols x rows equal areas of the image, row by row. */
        private fun grid(image: PixelImage, cols: Int, rows: Int): IntArray {
            val out = IntArray(cols * rows)
            for (gy in 0 until rows) {
                val y0 = gy * image.height / rows
                val y1 = maxOf(y0 + 1, (gy + 1) * image.height / rows)
                for (gx in 0 until cols) {
                    val x0 = gx * image.width / cols
                    val x1 = maxOf(x0 + 1, (gx + 1) * image.width / cols)
                    var sum = 0L
                    for (y in y0 until minOf(y1, image.height)) for (x in x0 until minOf(x1, image.width)) sum += brightness(image.argb[y * image.width + x])
                    out[gy * cols + gx] = (sum / ((minOf(y1, image.height) - y0).toLong() * (minOf(x1, image.width) - x0))).toInt()
                }
            }
            return out
        }

        private fun brightness(argb: Int): Int {
            val a = argb ushr 24
            val r = composite((argb shr 16) and 0xFF, a)
            val g = composite((argb shr 8) and 0xFF, a)
            val b = composite(argb and 0xFF, a)
            return (299 * r + 587 * g + 114 * b) / 1000
        }

        private fun composite(channel: Int, alpha: Int): Int = (channel * alpha + 255 * (255 - alpha)) / 255
    }
}

/**
 * Crops a picture to what stands out from its background, then pads it back to a square in that background colour.
 * Two drawings of one icon that differ only in their margins (a launcher PNG with its padding and the same icon
 * drawn full-bleed from a vector, or a user's crop of either) come out alike, so their hashes do too.
 */
object IconTrim {
    /** Summed RGB difference (of 765) from the background at which a pixel counts as part of the picture. */
    const val CONTENT_DIFFERENCE = 48

    /** The corners must agree to this summed difference to be a background at all; a photo's corners do not. */
    const val CORNER_AGREEMENT = 48

    /**
     * [image] trimmed and padded, after transparency is composited on white (as [IconHash] sees it). The image
     * itself when its corners disagree, when nothing stands out or when the picture already fills it.
     */
    fun of(image: PixelImage): PixelImage {
        val w = image.width
        val h = image.height
        val rgb = IntArray(image.argb.size) { onWhite(image.argb[it]) }
        val corners = intArrayOf(rgb[0], rgb[w - 1], rgb[(h - 1) * w], rgb[h * w - 1])
        for (a in corners) for (b in corners) if (difference(a, b) > CORNER_AGREEMENT) return image
        val bg = average(corners)
        var top = h
        var bottom = -1
        var left = w
        var right = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (difference(rgb[y * w + x], bg) >= CONTENT_DIFFERENCE) {
                if (y < top) top = y
                if (y > bottom) bottom = y
                if (x < left) left = x
                if (x > right) right = x
            }
        }
        if (bottom < 0) return image
        val cw = right - left + 1
        val ch = bottom - top + 1
        if (cw == w && ch == h) return image
        val side = maxOf(cw, ch)
        val out = IntArray(side * side) { bg }
        val ox = (side - cw) / 2
        val oy = (side - ch) / 2
        for (y in 0 until ch) System.arraycopy(rgb, (top + y) * w + left, out, (oy + y) * side + ox, cw)
        return PixelImage(out, side, side)
    }

    private fun onWhite(argb: Int): Int {
        val a = argb ushr 24
        fun c(shift: Int) = (((argb shr shift) and 0xFF) * a + 255 * (255 - a)) / 255
        return (0xFF shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
    }

    private fun difference(a: Int, b: Int): Int =
        Math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) + Math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) + Math.abs((a and 0xFF) - (b and 0xFF))

    private fun average(c: IntArray): Int {
        fun ch(shift: Int) = c.sumOf { (it shr shift) and 0xFF } / c.size
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

sealed class IconVerdict {
    /** Different enough from every drawing of Code on the Go's icon. [distance] is the smallest of all the comparisons. */
    class Different(val distance: Int) : IconVerdict() {
        override fun toString() = "Different($distance)"
    }

    class TooSimilar(val distance: Int) : IconVerdict() {
        override fun toString() = "TooSimilar($distance)"
    }

    /** The comparison could not be made; the build is not blocked by it. */
    class Unchecked(val reason: String) : IconVerdict() {
        override fun toString() = "Unchecked($reason)"
    }
}

/** Decides whether a chosen picture is Code on the Go's own icon, or a copy of it. */
object IconCheck {
    /**
     * Pictures whose hashes differ in at most this many of 128 bits are refused. Measured on drawn icon-like
     * pictures (IconCheckTest): the same picture rescaled by plain sampling, with noise or with its brightness
     * shifted differs by at most 12 bits, and about 1 pair in 100 of unrelated simple pictures differs by 14 or
     * fewer, while unrelated pictures average about 37. So 14 refuses the copies and costs a rare false alarm,
     * which the user answers by choosing another picture. A hash this small cannot do better, and it is a guard
     * against honest reuse, not a defence against a determined imitation (the user also confirms the picture is their own).
     * On Code on the Go's real icon (CogoIconCheckTest) its launcher PNGs at every density, crops and rescales of
     * them come within 9 bits of its drawings, and 300 unrelated pictures no closer than 18.
     */
    const val MAX_SIMILAR_DISTANCE = 14

    /**
     * A reference needs more than this many set bits. A picture with no detail hashes to nothing, and its distance
     * from the reference is then the reference's own count: with more than [MAX_SIMILAR_DISTANCE] a plain picture is
     * never mistaken for it, and with fewer the comparison is skipped instead.
     */
    private const val MIN_DETAIL = MAX_SIMILAR_DISTANCE + 8

    fun compare(chosen: PixelImage, reference: PixelImage): IconVerdict = compare(chosen, listOf(reference))

    /**
     * Compares [chosen] with every drawing of the host's icon in [references] (the vector drawn full-bleed, the
     * adaptive icon masked and unmasked, the legacy PNGs, the in-app logo), each as it is and trimmed to its
     * content ([IconTrim]), each also mirrored, and keeps the smallest distance. The icon is refused if any
     * drawing of it matches: the drawings differ in their padding and mask, and a copy matches at least one.
     */
    fun compare(chosen: PixelImage, references: List<PixelImage>): IconVerdict {
        val plain = listOf(chosen, chosen.mirrored()).map { IconHash.of(it) }
        val trimmed = listOf(chosen, chosen.mirrored()).map { IconHash.of(IconTrim.of(it)) }
        var best = Int.MAX_VALUE
        for (reference in references) {
            val ref = IconHash.of(reference)
            if (ref.ones() >= MIN_DETAIL) best = minOf(best, plain.minOf { it.distance(ref) })
            val refTrimmed = IconHash.of(IconTrim.of(reference))
            if (refTrimmed.ones() >= MIN_DETAIL) best = minOf(best, trimmed.minOf { it.distance(refTrimmed) })
        }
        if (best == Int.MAX_VALUE) {
            return IconVerdict.Unchecked("Code on the Go's icon has too little detail to compare with.")
        }
        return if (best <= MAX_SIMILAR_DISTANCE) IconVerdict.TooSimilar(best) else IconVerdict.Different(best)
    }
}
