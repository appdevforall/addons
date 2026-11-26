package org.appdevforall.customidebuilder.core

/** How far to shrink a picture while decoding it, so a picture's shape cannot make the decode as large as its longest side. */
object ImageSampling {
    /** The most pixels a decode may hold: 16 Mi, which is 64 MiB as ARGB_8888. */
    const val MAX_DECODED_PIXELS = 16L * 1024 * 1024

    /**
     * The power-of-two `inSampleSize` for a [width] x [height] picture that will end up as a [targetSide] square cut
     * from its middle. It shrinks as long as the shorter side stays at [targetSide] or more, and then further until the
     * decode is within [MAX_DECODED_PIXELS], which wins when the two disagree: a 1000 x 100000 picture is decoded
     * smaller than [targetSide] across, and scaled up afterwards, instead of being decoded at 100 million pixels.
     */
    fun sampleSize(width: Int, height: Int, targetSide: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (minOf(width, height) / (sample * 2) >= targetSide) sample *= 2
        while ((width / sample).toLong() * (height / sample) > MAX_DECODED_PIXELS && sample < (1 shl 24)) sample *= 2
        return sample
    }
}
