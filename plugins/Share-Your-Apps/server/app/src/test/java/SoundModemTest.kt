package soundmodemtest

import org.appdevforall.shareyourapps.server.SoundDecoder
import org.appdevforall.shareyourapps.server.SoundModem
import org.appdevforall.shareyourapps.server.SoundPayload
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

var failures = 0
val failedNames = mutableListOf<String>()
fun check(name: String, cond: Boolean) {
    if (!cond) failedNames += name
    println((if (cond) "PASS  " else "FAIL  ") + name); if (!cond) failures++
}

val ok: (ByteArray) -> Boolean = { SoundPayload.decode(it) != null }
val hotspot = SoundPayload("7K3M9QXR", InetAddress.getByName("192.168.49.1") as Inet4Address, 6175, true,
    "DIRECT-LS-ABCD", "Hq7xKm2pRt9wZb4n").encode()
val external = SoundPayload("7K3M9QXR", InetAddress.getByName("10.0.0.23") as Inet4Address, 6175).encode()

class Heard(val bytes: ByteArray?, val how: String)

fun listen(audio: FloatArray, m: SoundModem = SoundModem(), from: Int = 0): Heard {
    val d = SoundDecoder(m, ok)
    d.feed(audio, from, audio.size - from); d.finish()
    return Heard(d.result, d.how)
}

fun heard(h: Heard, data: ByteArray = hotspot) = h.bytes?.contentEquals(data) == true
fun silence(seconds: Double, rate: Int = 44100) = FloatArray((seconds * rate).toInt())
fun noise(x: FloatArray, sigma: Double, seed: Long, from: Int = 0, to: Int = x.size): FloatArray {
    val r = Random(seed); val y = x.copyOf()
    for (i in from until to) y[i] += (r.nextGaussian() * sigma).toFloat()
    return y
}
fun clip(x: FloatArray, g: Double) = FloatArray(x.size) { (x[it] * g).coerceIn(-1.0, 1.0).toFloat() }
fun echo(x: FloatArray, rate: Int): FloatArray {
    val d1 = rate * 8 / 1000; val d2 = rate * 40 / 1000
    return FloatArray(x.size) { i -> (x[i] + (if (i >= d1) 0.5 * x[i - d1] else 0.0) + (if (i >= d2) 0.25 * x[i - d2] else 0.0)).toFloat() }
}
/** The same sound played by a phone whose clock runs [f] times as fast. */
fun stretch(x: FloatArray, f: Double) = FloatArray((x.size * f).toInt()) { j ->
    val p = j / f; val i = p.toInt(); val a = p - i
    if (i + 1 < x.size) (x[i] * (1 - a) + x[i + 1] * a).toFloat() else x[x.size - 1]
}
fun tone(x: FloatArray, hz: Double, amp: Double, rate: Int = 44100) = FloatArray(x.size) { i -> (x[i] + amp * sin(2 * PI * hz * i / rate)).toFloat() }

/** One burst plus its gap, built like SoundModem.burst, but from any tones, and silent on [lostHz]: a room that loses it. */
fun synth(m: SoundModem, symbols: IntArray, lostHz: Double = -1.0): FloatArray {
    val out = FloatArray(m.chirpLength + m.gapLength + symbols.size * m.symbolLength + m.repeatGap)
    for (i in m.chirp.indices) out[i] = (m.chirp[i] * SoundModem.VOLUME).toFloat()
    var at = m.chirpLength + m.gapLength
    val edge = m.ms(SoundModem.EDGE_MS)
    for ((k, t) in symbols.withIndex()) {
        val hz = SoundModem.toneHz(k, t)
        if (hz != lostHz) {
            val w = 2 * PI * hz / m.sampleRate
            for (i in 0 until m.symbolLength) out[at + i] = (SoundModem.VOLUME * sin(w * i) * SoundModem.ramp(i, m.symbolLength, edge)).toFloat()
        }
        at += m.symbolLength
    }
    return out
}

/** Runs every check; returns the names of those that failed. */
fun runChecks(): List<String> {
    failures = 0; failedNames.clear()
    val m = SoundModem()
    val play = m.play(hotspot)
    val one = m.frameLength(hotspot.size) + m.repeatGap
    check("own-hotspot payload is 46 bytes, other Wi-Fi 14", hotspot.size == 46 && external.size == 14)
    check("a burst lasts about ${one * 1000 / 44100} ms", one < 44100 * 4)

    val clean = listen(silence(0.5) + play)
    check("clean, 44.1 kHz: heard from the first repeat (${clean.how})", heard(clean) && clean.how.startsWith("repeat 1"))
    val m48 = SoundModem(48000)
    check("clean, 48 kHz", heard(listen(m48.play(hotspot), m48)))
    check("other Wi-Fi payload", heard(listen(m.play(external)), external))
    check("moderate noise", heard(listen(noise(play, 0.1, 1))))
    check("heavy noise", heard(listen(noise(play, 0.5, 2))))
    check("listening starts halfway through the first repeat", heard(listen(play, from = one / 2)))
    check("very quiet", heard(listen(FloatArray(play.size) { play[it] * 0.01f })))
    check("loud and clipped", heard(listen(clip(play, 4.0))))
    check("sender's clock 0.1% fast", heard(listen(stretch(play, 1.001))))
    check("sender's clock 0.1% slow", heard(listen(stretch(play, 0.999))))
    check("room echo (8 ms and 40 ms)", heard(listen(echo(play, 44100))))
    val wiped = listen(noise(play, 3.0, 3, 0, one))
    check("first repeat wiped out, a later one heard (${wiped.how})", heard(wiped))

    // A room that loses one data tone: pick an even-position tone repeat 1 uses often, and the header never.
    val s0 = SoundModem.symbols(hotspot, 0)
    val avoid = setOf(s0[0], s0[2], 0, 1, 2)
    val lost = (6 until s0.size step 2).map { s0[it] }.filter { it !in avoid }.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
    val lostHz = SoundModem.toneHz(0, lost)
    val scrambled = noise((0 until 3).map { synth(m, SoundModem.symbols(hotspot, it), lostHz) }.reduce { a, b -> a + b }, 0.05, 4)
    val same = noise((0 until 3).map { synth(m, s0, lostHz) }.reduce { a, b -> a + b }, 0.05, 4)
    val sh = listen(scrambled)
    check("lost tone $lostHz Hz: scrambled repeats still heard (${sh.how})", heard(sh))
    check("lost tone: three identical repeats are not (why repeats are scrambled)", listen(same).bytes == null)
    check("a steady whine at $lostHz Hz, half the signal's level", heard(listen(tone(play, lostHz, 0.35))))

    check("synth builds exactly what the modem plays", synth(m, SoundModem.symbols(hotspot, 1)).copyOf(m.frameLength(hotspot.size)).contentEquals(m.burst(hotspot, 1)))
    check("at every position, the three repeats' keys differ", (0 until SoundModem.MAX_SYMBOLS).all { i ->
        setOf(SoundModem.key(0, i), SoundModem.key(1, i), SoundModem.key(2, i)).size == 3 })
    check("more than 255 bytes is refused", try { m.burst(ByteArray(256), 0); false } catch (e: IllegalArgumentException) { true })

    // Nothing is heard where nothing was played.
    val rnd = Random(5)
    val tones = FloatArray(441000).also { x ->
        var at = 0
        while (at + 1323 <= x.size) {
            val hz = 2400 + rnd.nextDouble() * 2400
            for (i in 0 until 1323) x[at + i] = (0.7 * sin(2 * PI * hz * i / 44100) * SoundModem.ramp(i, 1323, 132)).toFloat()
            at += 1323
        }
    }
    val falseFinds = (1..5L).count { listen(noise(silence(10.0), 0.3, 10 + it)).bytes != null } +
        (if (listen(silence(5.0)).bytes != null) 1 else 0) + (if (listen(tones).bytes != null) 1 else 0)
    check("no false finds in noise, silence and random tones", falseFinds == 0)

    // Fed in chunks of any size, as recording delivers them.
    val d = SoundDecoder(m, ok)
    val audio = noise(silence(0.3) + play, 0.1, 6)
    var pos = 0
    while (pos < audio.size) { val n = minOf(1 + rnd.nextInt(5000), audio.size - pos); d.feed(audio, pos, n); pos += n }
    d.finish()
    check("random-sized chunks give the same result", d.result?.contentEquals(hotspot) == true)

    val ten = noise(silence(10.0 - play.size / 44100.0) + play, 0.1, 7)
    val t0 = System.nanoTime(); val h = listen(ten); val took = (System.nanoTime() - t0) / 1_000_000
    check("10 s of audio decoded in $took ms (including padding)", heard(h) && took < 30_000)

    // How many bursts a player plays: as many as fit in the 30-second window, never fewer than 4.
    val plan = SoundModem()
    val addr = java.net.InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 49, 1)) as java.net.Inet4Address
    val codeOnly = org.appdevforall.shareyourapps.server.SoundPayload("ABCD2345").encode().size
    val withAddress = org.appdevforall.shareyourapps.server.SoundPayload("ABCD2345", addr, 6175).encode().size
    val worstHotspot = org.appdevforall.shareyourapps.server.SoundPayload("ABCD2345", addr, 6175, true, "S".repeat(32), "p".repeat(63)).encode().size
    fun playSeconds(n: Int) = plan.burstsFor(n) * plan.burstPeriod(n).toDouble() / plan.sampleRate
    fun burstSeconds(n: Int) = plan.burstPeriod(n).toDouble() / plan.sampleRate
    check("play plan: the window is 30 seconds", SoundModem.WINDOW_MS == 30_000L)
    check("play plan: every size gets at least 4 bursts", (1..SoundModem.MAX_BYTES).all { plan.burstsFor(it) >= SoundModem.MIN_BURSTS })
    check("play plan: a short code gets many bursts", plan.burstsFor(codeOnly) >= 20)
    check("play plan: the longest hotspot payload (32-byte name, 63-byte password) gets 4 bursts, within the window",
        plan.burstsFor(worstHotspot) == 4 && playSeconds(worstHotspot) <= 30.0)
    check("play plan: each real payload fills the window to within one burst",
        listOf(codeOnly, withAddress, worstHotspot).all { playSeconds(it) <= 30.0 && playSeconds(it) > 30.0 - burstSeconds(it) })
    check("play plan: the audio made is exactly the planned bursts long",
        listOf(codeOnly, worstHotspot).all { plan.play(ByteArray(it) { i -> i.toByte() }, plan.burstsFor(it)).size == plan.burstsFor(it) * plan.burstPeriod(it) })

    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED"); return failedNames.toList()
}

/** The JUnit test: runs the checks above, and fails naming them if any fail. */
class SoundModemChecks {
    @org.junit.Test
    fun everyCheckPasses() {
        val failed = runChecks()
        org.junit.Assert.assertTrue("Failed checks: " + failed.joinToString("; "), failed.isEmpty())
    }
}
