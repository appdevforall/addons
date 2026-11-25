package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSamplingTest {
    private fun decoded(w: Int, h: Int, sample: Int) = (w / sample).toLong() * (h / sample)

    @Test
    fun anOrdinaryPictureIsShrunkOnlyAsFarAsTheTargetAllows() {
        assertEquals(1, ImageSampling.sampleSize(512, 512, 512))
        assertEquals(1, ImageSampling.sampleSize(1000, 1000, 512))
        assertEquals(4, ImageSampling.sampleSize(4000, 3000, 512))
        assertEquals(1, ImageSampling.sampleSize(3000, 700, 512))
    }

    @Test
    fun aVeryLongThinPictureIsBoundedByItsPixelCountNotItsShortSide() {
        // The short side alone (1000) would decode all 100 million pixels.
        val s = ImageSampling.sampleSize(1000, 100_000, 512)
        assertTrue("sample $s", s > 1)
        assertTrue(decoded(1000, 100_000, s) <= ImageSampling.MAX_DECODED_PIXELS)
        val t = ImageSampling.sampleSize(100_000, 1000, 512)
        assertTrue(decoded(100_000, 1000, t) <= ImageSampling.MAX_DECODED_PIXELS)
    }

    @Test
    fun aHugeSquarePictureIsBoundedToo() {
        val s = ImageSampling.sampleSize(60_000, 60_000, 512)
        assertTrue(decoded(60_000, 60_000, s) <= ImageSampling.MAX_DECODED_PIXELS)
        assertEquals("a power of two", 1, Integer.bitCount(s))
    }

    @Test
    fun unusableDimensionsGiveOne() {
        assertEquals(1, ImageSampling.sampleSize(0, 100, 512))
        assertEquals(1, ImageSampling.sampleSize(100, -1, 512))
    }
}
