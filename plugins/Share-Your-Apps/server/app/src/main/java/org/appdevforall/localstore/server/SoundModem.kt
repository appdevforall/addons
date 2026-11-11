package org.appdevforall.localstore.server

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns handshake bytes into sound; [SoundDecoder] turns sound back into bytes. Plain Kotlin, no Android code.
 * The modem doesn't know what the bytes mean: the listener checks each candidate (with SoundPayload's CRC).
 *
 * One burst: a rising sweep (the start signal), a short gap, a header of 3 bytes — the length, the length
 * inverted, and the repeat number with its inverse in the low 4 bits — then the data. Each byte is two tones,
 * high 4 bits first; each tone is one of 16, held for [SYMBOL_MS] with soft edges so it doesn't click.
 * Even and odd tones use separate, interleaved sets of 16 frequencies, so a room's echo of one tone can't pass
 * for the next. All 32 frequencies lie within one octave (2400 to 4725 Hz), so a distorting speaker's
 * harmonics fall above them.
 *
 * Each repeat scrambles the data with its own key (the header isn't scrambled): a value sent on a weak or noisy
 * frequency in one repeat goes out on another in the next. At every position, the three repeats' keys differ.
 */
class SoundModem(val sampleRate: Int = SAMPLE_RATE) {
    val chirpLength = ms(CHIRP_MS)
    val gapLength = ms(GAP_MS)
    val symbolLength = ms(SYMBOL_MS)
    val repeatGap = ms(REPEAT_GAP_MS)
    private val edge = ms(EDGE_MS)

    /** The start signal, at full scale: a linear sweep from [CHIRP_LOW_HZ] to [CHIRP_HIGH_HZ]. */
    val chirp: FloatArray = run {
        val seconds = chirpLength.toDouble() / sampleRate
        val ends = ms(5.0)
        FloatArray(chirpLength) { i ->
            val t = i.toDouble() / sampleRate
            val phase = 2 * PI * (CHIRP_LOW_HZ * t + (CHIRP_HIGH_HZ - CHIRP_LOW_HZ) * t * t / (2 * seconds))
            (sin(phase) * ramp(i, chirpLength, ends)).toFloat()
        }
    }

    fun ms(m: Double): Int = (m * sampleRate / 1000).toInt()

    /** Samples in one burst carrying [dataBytes] bytes. */
    fun frameLength(dataBytes: Int): Int = chirpLength + gapLength + (HEADER_BYTES + dataBytes) * 2 * symbolLength

    /** Samples from the start of one burst carrying [dataBytes] bytes to the start of the next. */
    fun burstPeriod(dataBytes: Int): Int = frameLength(dataBytes) + repeatGap

    /**
     * How many bursts a player plays for [dataBytes] bytes: as many as fit in [WINDOW_MS], but at least [MIN_BURSTS],
     * so a listener that joins in the middle of a burst still hears [REPEATS] whole ones to combine. A short code gets
     * dozens; a hotspot's name and password, whose bursts last about 7 s, get 4.
     */
    fun burstsFor(dataBytes: Int): Int =
        maxOf(MIN_BURSTS, (WINDOW_MS * sampleRate / 1000 / burstPeriod(dataBytes)).toInt())

    /** One burst: the start signal, the header and [data], scrambled for [repeat] (0 to [REPEATS] - 1). */
    fun burst(data: ByteArray, repeat: Int): FloatArray {
        require(data.size in 1..MAX_BYTES) { "a burst carries 1 to $MAX_BYTES bytes" }
        require(repeat in 0 until REPEATS) { "the repeat must be 0 to ${REPEATS - 1}" }
        val out = FloatArray(frameLength(data.size))
        for (i in chirp.indices) out[i] = (chirp[i] * VOLUME).toFloat()
        var at = chirpLength + gapLength
        for ((k, tone) in symbols(data, repeat).withIndex()) {
            val w = 2 * PI * toneHz(k, tone) / sampleRate
            for (i in 0 until symbolLength) out[at + i] = (VOLUME * sin(w * i) * ramp(i, symbolLength, edge)).toFloat()
            at += symbolLength
        }
        return out
    }

    /** [count] bursts in a row, repeats 0, 1, 2, 0, …, with a short gap after each. */
    fun play(data: ByteArray, count: Int = REPEATS): FloatArray {
        val one = burstPeriod(data.size)
        val out = FloatArray(one * count)
        for (r in 0 until count) burst(data, r % REPEATS).copyInto(out, r * one)
        return out
    }

    companion object {
        const val SAMPLE_RATE = 44100
        const val LOW_HZ = 2400.0
        const val SPACING_HZ = 75.0
        const val TONES = 16
        const val CHIRP_LOW_HZ = 2400.0
        const val CHIRP_HIGH_HZ = 4800.0
        const val CHIRP_MS = 150.0
        const val GAP_MS = 20.0
        const val SYMBOL_MS = 30.0
        const val EDGE_MS = 3.0
        const val REPEAT_GAP_MS = 100.0
        /** Of full scale: loud enough for a noisy room, quiet enough not to distort a small speaker. */
        const val VOLUME = 0.7
        const val REPEATS = 3
        /** How long a player plays and a listener listens, from the moment each starts. */
        const val WINDOW_MS = 30_000L
        /** The fewest bursts a player plays, however long each is. */
        const val MIN_BURSTS = REPEATS + 1
        const val HEADER_BYTES = 3
        const val MAX_BYTES = 255
        const val MAX_SYMBOLS = 2 * MAX_BYTES

        /** The frequency of tone [tone] (0–15) at symbol position [k]: even and odd positions use different sets. */
        fun toneHz(k: Int, tone: Int): Double = LOW_HZ + SPACING_HZ * (2 * tone + (k and 1))

        /** The tone numbers (0–15) of a burst, in order: the header plain, the data scrambled for [repeat]. */
        fun symbols(data: ByteArray, repeat: Int): IntArray {
            val n = data.size
            val header = intArrayOf(n, n xor 0xFF, (repeat shl 4) or (repeat.inv() and 0x0F))
            val out = IntArray((HEADER_BYTES + n) * 2)
            var k = 0
            for (b in header) { out[k++] = b shr 4; out[k++] = b and 0x0F }
            for ((i, b) in data.withIndex()) {
                val v = b.toInt() and 0xFF
                out[k++] = (v shr 4) xor key(repeat, 2 * i)
                out[k++] = (v and 0x0F) xor key(repeat, 2 * i + 1)
            }
            return out
        }

        /** The scrambling key for data symbol [i] of [repeat]: XOR with it moves each value to a different tone. */
        fun key(repeat: Int, i: Int): Int = KEYS[repeat][i]

        private val KEYS: Array<IntArray> = run {
            var s = 0x2545F4914F6CDD1DL
            fun next(): Int { s = s * 6364136223846793005L + 1442695040888963407L; return ((s ushr 33) and 0x7FFFFFFF).toInt() }
            val k0 = IntArray(MAX_SYMBOLS); val k1 = IntArray(MAX_SYMBOLS); val k2 = IntArray(MAX_SYMBOLS)
            for (i in 0 until MAX_SYMBOLS) {
                k0[i] = next() and 15
                val d1 = 1 + next() % 15
                var d2 = 1 + next() % 15
                if (d2 == d1) d2 = 1 + d2 % 15
                k1[i] = k0[i] xor d1; k2[i] = k0[i] xor d2
            }
            arrayOf(k0, k1, k2)
        }

        /** A raised-cosine fade over the first and last [e] of [n] samples. */
        fun ramp(i: Int, n: Int, e: Int): Double = when {
            e <= 0 -> 1.0
            i < e -> 0.5 - 0.5 * cos(PI * i / e)
            i >= n - e -> 0.5 - 0.5 * cos(PI * (n - 1 - i) / e)
            else -> 1.0
        }
    }
}
