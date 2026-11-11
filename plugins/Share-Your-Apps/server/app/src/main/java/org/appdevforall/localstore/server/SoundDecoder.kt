package org.appdevforall.localstore.server

import java.util.Locale
import kotlin.math.sqrt

/**
 * Listens for [SoundModem] bursts in audio fed in chunks of any size, as recording delivers them. Plain Kotlin.
 *
 * Finds each start signal by correlating with the sweep (FFT, overlap-save, normalized so loudness doesn't
 * matter), reads the header, then measures every tone with Goertzel over the middle of the symbol, skipping the
 * edges where the previous tone's echo lingers. Each burst is tried on its own; if [accept] refuses it (a wrong
 * CRC), its tone strengths, un-scrambled and scaled, are added to those of the recent bursts of the same length
 * and the sum is tried too. The first bytes [accept] takes are the [result]; later audio is ignored.
 */
class SoundDecoder(
    private val modem: SoundModem = SoundModem(),
    private val accept: (ByteArray) -> Boolean,
) {
    /** The accepted bytes, once heard. */
    var result: ByteArray? = null; private set
    /** How the result was heard, for the log: which repeat, or how many combined, and the start signal's strength. */
    var how: String = ""; private set
    /** The strongest start signal heard so far, 0 to 1: a rough "hearing something" level. */
    var bestSignal = 0.0; private set

    private class Candidate(val at: Long, val signal: Double)
    private class Frame(val at: Long, val length: Int, val powers: Array<DoubleArray>)

    private val chirpLength = modem.chirpLength
    private val sym = modem.symbolLength
    private val winFrom = sym * 20 / 100
    private val winLength = sym * 65 / 100
    private val fftSize: Int = run { var n = 1; while (n < 2 * chirpLength) n = n shl 1; n }
    private val step = fftSize - chirpLength + 1
    private val chirpRe = DoubleArray(fftSize)
    private val chirpIm = DoubleArray(fftSize)
    private val chirpNorm: Double
    private val coeffs = Array(2) { p -> DoubleArray(SoundModem.TONES) { t -> SoundDsp.coeff(SoundModem.toneHz(p, t), modem.sampleRate) } }

    private var buf = FloatArray(1 shl 17)
    private var base = 0L       // the absolute position of buf[0]
    private var size = 0        // samples held
    private val end get() = base + size
    private var searchAt = 0L   // the next position to test for a start signal
    private var peakAt = -1L
    private var peakValue = 0.0
    private val pending = ArrayList<Candidate>()
    private val heard = ArrayList<Frame>()

    init {
        var e = 0.0
        for (i in 0 until chirpLength) { val v = modem.chirp[i].toDouble(); chirpRe[i] = v; e += v * v }
        chirpNorm = sqrt(e)
        SoundDsp.fft(chirpRe, chirpIm)
    }

    /** Takes [n] samples of [x] from [from]; returns the result once heard, else null. */
    fun feed(x: FloatArray, from: Int = 0, n: Int = x.size - from): ByteArray? {
        if (result != null) return result
        append(x, from, n)
        search()
        decodePending()
        compact()
        return result
    }

    /** The audio has ended: pads with silence so a burst at the very end is still read. */
    fun finish(): ByteArray? = feed(FloatArray(fftSize + modem.frameLength(SoundModem.MAX_BYTES)))

    private fun append(x: FloatArray, from: Int, n: Int) {
        if (size + n > buf.size) {
            var cap = buf.size
            while (cap < size + n) cap = cap shl 1
            buf = buf.copyOf(cap)
        }
        x.copyInto(buf, size, from, from + n)
        size += n
    }

    private fun search() {
        val re = DoubleArray(fftSize); val im = DoubleArray(fftSize)
        while (searchAt + fftSize <= end) {
            val off = (searchAt - base).toInt()
            for (i in 0 until fftSize) { re[i] = buf[off + i].toDouble(); im[i] = 0.0 }
            SoundDsp.fft(re, im)
            for (i in 0 until fftSize) { // times the sweep's spectrum, conjugated: correlation
                val r = re[i] * chirpRe[i] + im[i] * chirpIm[i]
                val m = im[i] * chirpRe[i] - re[i] * chirpIm[i]
                re[i] = r; im[i] = m
            }
            SoundDsp.fft(re, im, inverse = true)
            var energy = 0.0
            for (i in 0 until chirpLength) { val v = buf[off + i].toDouble(); energy += v * v }
            for (t in 0 until step) {
                if (t > 0) {
                    val a = buf[off + t - 1].toDouble(); val b = buf[off + t + chirpLength - 1].toDouble()
                    energy += b * b - a * a
                }
                consider(searchAt + t, if (energy > 1e-9) re[t] / (chirpNorm * sqrt(energy)) else 0.0)
            }
            searchAt += step
        }
    }

    /** Keeps the strongest start signal within half a sweep; once past it, it becomes a candidate. */
    private fun consider(at: Long, v: Double) {
        if (v > bestSignal) bestSignal = v
        if (peakAt >= 0 && at - peakAt > chirpLength / 2) { pending += Candidate(peakAt, peakValue); peakAt = -1 }
        if (v >= THRESHOLD && (peakAt < 0 || v > peakValue)) { peakAt = at; peakValue = v }
    }

    private fun decodePending() {
        while (pending.isNotEmpty() && result == null) {
            val c = pending[0]
            val start = c.at + chirpLength + modem.gapLength
            if (start + HEADER_SYMBOLS.toLong() * sym > end) return
            val h = IntArray(HEADER_SYMBOLS) { k -> argmax(tonePowers(start, k)) }
            val n = (h[0] shl 4) or h[1]
            val inv = (h[2] shl 4) or h[3]
            val r = h[4]
            if (n == 0 || inv != (n xor 0xFF) || r >= SoundModem.REPEATS || h[5] != (r.inv() and 0x0F)) { pending.removeAt(0); continue }
            if (start + (HEADER_SYMBOLS + 2L * n) * sym > end) return
            pending.removeAt(0)
            frame(c, start, n, r)
        }
    }

    private fun frame(c: Candidate, start: Long, n: Int, repeat: Int) {
        val powers = Array(2 * n) { i ->
            val p = tonePowers(start, HEADER_SYMBOLS + i)
            val key = SoundModem.key(repeat, i)
            val sum = p.sum()
            // Indexed by the value sent, not the tone; scaled, so a loud repeat doesn't outweigh the others.
            DoubleArray(SoundModem.TONES) { v -> if (sum > 0) p[v xor key] / sum else 0.0 }
        }
        bytes(powers)?.let { done(it, "repeat ${repeat + 1}", c); return }
        val memory = (SoundModem.REPEATS + 1L) * (modem.frameLength(n) + modem.repeatGap)
        heard.removeAll { it.length != n || c.at - it.at > memory }
        heard += Frame(c.at, n, powers)
        while (heard.size > SoundModem.REPEATS) heard.removeAt(0)
        if (heard.size > 1) {
            val sum = Array(2 * n) { i -> DoubleArray(SoundModem.TONES) { v -> heard.sumOf { it.powers[i][v] } } }
            bytes(sum)?.let { done(it, "${heard.size} repeats combined", c) }
        }
    }

    private fun bytes(powers: Array<DoubleArray>): ByteArray? {
        val b = ByteArray(powers.size / 2) { i -> ((argmax(powers[2 * i]) shl 4) or argmax(powers[2 * i + 1])).toByte() }
        return if (accept(b)) b else null
    }

    private fun done(b: ByteArray, label: String, c: Candidate) {
        result = b
        how = "$label, start signal ${String.format(Locale.ROOT, "%.2f", c.signal)}"
    }

    /** The strength of each of the 16 tones in symbol [k] of the burst whose header starts at [start]. */
    private fun tonePowers(start: Long, k: Int): DoubleArray {
        val from = (start + k.toLong() * sym - base).toInt() + winFrom
        return DoubleArray(SoundModem.TONES) { t -> SoundDsp.power(buf, from, winLength, coeffs[k and 1][t]) }
    }

    private fun argmax(p: DoubleArray): Int { var best = 0; for (i in 1 until p.size) if (p[i] > p[best]) best = i; return best }

    /** Drops audio no candidate or search still needs. */
    private fun compact() {
        var keep = searchAt
        if (peakAt >= 0) keep = minOf(keep, peakAt)
        pending.firstOrNull()?.let { keep = minOf(keep, it.at) }
        val drop = (keep - base).toInt()
        if (drop <= 0 || drop < buf.size / 4) return
        buf.copyInto(buf, 0, drop, size)
        size -= drop; base += drop
    }

    companion object {
        /** Normalized correlation a start signal must reach; noise stays near 0.05, a clear sweep near 1. */
        const val THRESHOLD = 0.35
        const val HEADER_SYMBOLS = SoundModem.HEADER_BYTES * 2
    }
}
