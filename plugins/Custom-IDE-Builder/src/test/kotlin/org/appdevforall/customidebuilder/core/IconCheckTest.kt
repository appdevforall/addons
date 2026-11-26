package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class IconCheckTest {
    private val size = 96

    /** A picture drawn from a seed: a few coloured blocks and discs on a background, like an icon. */
    private fun picture(seed: Long, n: Int = size): PixelImage {
        val rnd = Random(seed)
        val px = IntArray(n * n)
        val bg = rnd.nextInt(0xFFFFFF) or (0xFF shl 24)
        px.fill(bg)
        repeat(3 + rnd.nextInt(4)) {
            val colour = rnd.nextInt(0xFFFFFF) or (0xFF shl 24)
            val cx = rnd.nextInt(n); val cy = rnd.nextInt(n); val r = n / 8 + rnd.nextInt(n / 3)
            val disc = rnd.nextBoolean()
            for (y in 0 until n) for (x in 0 until n) {
                val inside = if (disc) (x - cx) * (x - cx) + (y - cy) * (y - cy) < r * r else Math.abs(x - cx) < r && Math.abs(y - cy) < r
                if (inside) px[y * n + x] = colour
            }
        }
        return PixelImage(px, n, n)
    }

    private fun scaled(src: PixelImage, to: Int): PixelImage {
        val out = IntArray(to * to)
        for (y in 0 until to) for (x in 0 until to) out[y * to + x] = src.argb[(y * src.height / to) * src.width + (x * src.width / to)]
        return PixelImage(out, to, to)
    }

    private fun noisy(src: PixelImage, seed: Long, amount: Int): PixelImage {
        val rnd = Random(seed)
        val out = IntArray(src.argb.size) {
            val c = src.argb[it]
            fun ch(shift: Int) = (((c shr shift) and 0xFF) + rnd.nextInt(2 * amount + 1) - amount).coerceIn(0, 255)
            (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return PixelImage(out, src.width, src.height)
    }

    private fun brighter(src: PixelImage, by: Int): PixelImage {
        val out = IntArray(src.argb.size) {
            val c = src.argb[it]
            fun ch(s: Int) = (((c shr s) and 0xFF) + by).coerceIn(0, 255)
            (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return PixelImage(out, src.width, src.height)
    }

    /** A busy picture: enough edges that its hash has many bits set, as Code on the Go's icon does. */
    private fun detailed(): PixelImage {
        val n = 96
        val rnd = Random(77)
        val cells = IntArray(64) { 20 + rnd.nextInt(215) }
        return PixelImage(IntArray(n * n) { i -> val v = cells[(i / n / 12) * 8 + (i % n / 12)]; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }, n, n)
    }

    @Test
    fun theSamePictureHasDistanceZeroAndTheHashIsStable() {
        val a = picture(1)
        assertEquals(0, IconHash.of(a).distance(IconHash.of(picture(1))))
        assertEquals(IconHash.of(a), IconHash.of(picture(1)))
    }

    @Test
    fun copiesAreWithinTheThreshold() {
        var worst = 0
        for (seed in 1L..60L) {
            val ref = picture(seed)
            val ref0 = IconHash.of(ref)
            val variants = listOf(
                scaled(ref, 48), scaled(ref, 192), scaled(scaled(ref, 40), 96),
                noisy(ref, seed, 12), brighter(ref, 25), brighter(ref, -25), scaled(noisy(ref, seed, 8), 64),
            )
            for (v in variants) worst = maxOf(worst, IconHash.of(v).distance(ref0))
        }
        assertTrue("worst copy distance $worst must not exceed ${IconCheck.MAX_SIMILAR_DISTANCE}", worst <= IconCheck.MAX_SIMILAR_DISTANCE)
    }

    @Test
    fun unrelatedPicturesAreRarelyWithinTheThresholdAndFarOnAverage() {
        val hashes = (1L..120L).map { IconHash.of(picture(1000 + it)) }
        var within = 0
        var sum = 0L
        var pairs = 0
        for (i in hashes.indices) for (j in i + 1 until hashes.size) {
            val d = hashes[i].distance(hashes[j])
            if (d <= IconCheck.MAX_SIMILAR_DISTANCE) within++
            sum += d
            pairs++
        }
        val share = within.toDouble() / pairs
        assertTrue("$within of $pairs unrelated pairs are within the threshold", share < 0.02)
        assertTrue("mean ${sum.toDouble() / pairs}", sum.toDouble() / pairs > 30)
    }

    @Test
    fun aMirroredCopyIsStillACopy() {
        val ref = picture(7)
        val v = IconCheck.compare(ref.mirrored(), ref)
        assertTrue(v.toString(), v is IconVerdict.TooSimilar && v.distance == 0)
        // Without the mirrored comparison it would not have been caught.
        assertTrue(IconHash.of(ref.mirrored()).distance(IconHash.of(ref)) > IconCheck.MAX_SIMILAR_DISTANCE)
    }

    @Test
    fun aRescaledRecolouredCopyIsRefusedAndAnotherPictureIsAccepted() {
        val ref = picture(11)
        assertTrue(IconCheck.compare(scaled(noisy(ref, 3, 10), 64), ref) is IconVerdict.TooSimilar)
        val other = IconCheck.compare(picture(12), ref)
        assertTrue(other.toString(), other is IconVerdict.Different)
    }

    @Test
    fun aFlatPictureCannotMatchAndIsNotRefusedByAFlatReference() {
        val flat = PixelImage(IntArray(64 * 64) { 0xFF336699.toInt() }, 64, 64)
        val ref = detailed()
        assertTrue("the reference used here has detail", IconHash.of(ref).ones() > IconCheck.MAX_SIMILAR_DISTANCE)
        assertTrue(IconCheck.compare(flat, ref) is IconVerdict.Different)
        // A reference with no detail cannot be compared with: the check says so instead of refusing everything flat.
        assertTrue(IconCheck.compare(flat, flat) is IconVerdict.Unchecked)
    }

    @Test
    fun transparencyCountsAsWhiteAndNonSquareImagesHash() {
        val opaqueWhite = PixelImage(IntArray(32 * 32) { 0xFFFFFFFF.toInt() }, 32, 32)
        val clear = PixelImage(IntArray(32 * 32), 32, 32)
        assertEquals(IconHash.of(opaqueWhite), IconHash.of(clear))
        val wide = PixelImage(IntArray(200 * 20) { it * 7 }, 200, 20)
        IconHash.of(wide)
        val tiny = PixelImage(intArrayOf(0xFF000000.toInt()), 1, 1)
        IconHash.of(tiny)
    }

    @Test
    fun theImageWrapperChecksItsSize() {
        try {
            PixelImage(IntArray(5), 2, 2)
            throw AssertionError("accepted")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun silhouetteUsesAlphaWhenThereIsTransparency() {
        val n = 20
        val px = IntArray(n * n) { i -> if (i % n < 10) 0xFF112233.toInt() else 0x00000000 }
        val r = Silhouette.of(PixelImage(px, n, n))
        assertTrue(!r.usedColourDifference)
        assertEquals(0.5, r.coverage, 0.001)
        assertEquals(0xFFFFFFFF.toInt(), r.argb[0])
        assertEquals(0, r.argb[n - 1])
    }

    @Test
    fun silhouetteOfAnOpaqueImageIsWhatDiffersFromItsCorners() {
        val n = 20
        val px = IntArray(n * n) { 0xFFFFFFFF.toInt() }
        for (y in 5 until 15) for (x in 5 until 15) px[y * n + x] = 0xFF204080.toInt()
        val r = Silhouette.of(PixelImage(px, n, n))
        assertTrue(r.usedColourDifference)
        assertEquals(100.0 / 400.0, r.coverage, 0.001)
        assertEquals(0, r.argb[0])
        assertEquals(0xFFFFFFFF.toInt(), r.argb[10 * n + 10])
    }

    @Test
    fun silhouetteReportsAUselessResult() {
        val flat = Silhouette.of(PixelImage(IntArray(100) { 0xFF808080.toInt() }, 10, 10))
        assertEquals(0.0, flat.coverage, 0.0)
        val clear = Silhouette.of(PixelImage(IntArray(100), 10, 10))
        assertEquals(0.0, clear.coverage, 0.0)
    }
}
