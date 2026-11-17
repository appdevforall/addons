package org.appdevforall.shareyourapps.server

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The signal maths the sound handshake needs, in plain Kotlin with no library: a radix-2 FFT, for finding
 * the start signal, and Goertzel, for measuring one tone's strength.
 */
object SoundDsp {

    /** In-place complex FFT of [re] and [im], whose size must be a power of two. [inverse] also divides by the size. */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        require(n == im.size && n > 0 && (n and (n - 1)) == 0) { "the size must be a power of two" }
        // Bit-reversed order.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val r = re[i]; re[i] = re[j]; re[j] = r
                val m = im[i]; im[i] = im[j]; im[j] = m
            }
        }
        var len = 2
        while (len <= n) {
            val angle = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(angle); val wi = sin(angle)
            val half = len / 2
            var start = 0
            while (start < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until half) {
                    val a = start + k; val b = a + half
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = nr
                }
                start += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    /** Goertzel's coefficient for [freq] Hz at [sampleRate]. */
    fun coeff(freq: Double, sampleRate: Int): Double = 2 * cos(2 * PI * freq / sampleRate)

    /** The power of one frequency, given by its [coeff], in samples [from] until [from] + [n] of [x]. */
    fun power(x: FloatArray, from: Int, n: Int, coeff: Double): Double {
        var s1 = 0.0; var s2 = 0.0
        for (i in from until from + n) {
            val s = x[i] + coeff * s1 - s2
            s2 = s1; s1 = s
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    /** The quietest level the listening meter shows, in dB of full scale; anything quieter shows as empty. */
    const val METER_FLOOR_DB = -60.0

    /**
     * The loudness of samples [from] until [from] + [n] of [x] (each -1 to 1), in dB of full scale: 0 for a full-scale
     * square wave, about -3 for a full-scale tone, and [METER_FLOOR_DB] for silence or anything quieter.
     */
    fun rmsDb(x: FloatArray, from: Int = 0, n: Int = x.size - from): Double {
        if (n <= 0) return METER_FLOOR_DB
        var sum = 0.0
        for (i in from until from + n) { val v = x[i].toDouble(); sum += v * v }
        val rms = kotlin.math.sqrt(sum / n)
        if (rms <= 0.0) return METER_FLOOR_DB
        return maxOf(METER_FLOOR_DB, 20 * kotlin.math.log10(rms))
    }

    /** A level in dB of full scale as the meter's fill, 0 to 100: [METER_FLOOR_DB] or quieter is 0, full scale is 100. */
    fun meterPercent(db: Double): Int =
        (((db - METER_FLOOR_DB) / -METER_FLOOR_DB) * 100).toInt().coerceIn(0, 100)
}
