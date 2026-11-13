package soundleveltest

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.appdevforall.shareyourapps.server.SoundDsp

var failures = 0
val failedNames = mutableListOf<String>()
fun check(name: String, cond: Boolean) {
    if (!cond) failedNames += name
    println((if (cond) "PASS  " else "FAIL  ") + name); if (!cond) failures++ }

fun near(a: Double, b: Double, tolerance: Double) = abs(a - b) <= tolerance

/** A square wave of [amplitude], alternating sign every sample: its RMS is the amplitude itself. */
fun square(amplitude: Float, n: Int = 1000) = FloatArray(n) { if (it % 2 == 0) amplitude else -amplitude }

/** Runs every check; returns the names of those that failed. */
fun runChecks(): List<String> {
    failures = 0; failedNames.clear()
    val floor = SoundDsp.METER_FLOOR_DB

    // rmsDb: loudness in dB of full scale.
    check("floor is -60 dB", floor == -60.0)
    check("silence is the floor", SoundDsp.rmsDb(FloatArray(1000)) == floor)
    check("no samples is the floor", SoundDsp.rmsDb(FloatArray(10), 5, 0) == floor)
    check("empty array is the floor", SoundDsp.rmsDb(FloatArray(0)) == floor)
    check("full-scale square wave is 0 dB", near(SoundDsp.rmsDb(square(1f)), 0.0, 1e-9))
    check("half-scale square wave is about -6.02 dB", near(SoundDsp.rmsDb(square(0.5f)), -6.0206, 0.001))
    check("tenth-scale square wave is about -20 dB", near(SoundDsp.rmsDb(square(0.1f)), -20.0, 0.001))
    // 1000 Hz at 44.1 kHz for one second: a whole number of cycles, so the RMS is 1/sqrt(2).
    val tone = FloatArray(44100) { sin(2 * PI * 1000 * it / 44100).toFloat() }
    check("full-scale tone is about -3.01 dB", near(SoundDsp.rmsDb(tone), -3.0103, 0.01))
    check("anything quieter than the floor is the floor", SoundDsp.rmsDb(square(0.0001f)) == floor)
    check("never louder than 0 dB for samples within -1 to 1", SoundDsp.rmsDb(square(1f)) <= 1e-9)
    // A part of the array: silence, then a full-scale square wave.
    val mixed = FloatArray(200).also { a -> for (i in 100 until 200) a[i] = if (i % 2 == 0) 1f else -1f }
    check("from/n: the silent part is the floor", SoundDsp.rmsDb(mixed, 0, 100) == floor)
    check("from/n: the loud part is 0 dB", near(SoundDsp.rmsDb(mixed, 100, 100), 0.0, 1e-9))
    check("from only: measures to the end", near(SoundDsp.rmsDb(mixed, 100), 0.0, 1e-9))
    check("whole mix: half the power is about -3.01 dB", near(SoundDsp.rmsDb(mixed), -3.0103, 0.001))

    // meterPercent: the meter's fill, 0 to 100.
    check("meter: the floor is empty", SoundDsp.meterPercent(floor) == 0)
    check("meter: quieter than the floor is empty", SoundDsp.meterPercent(-100.0) == 0)
    check("meter: full scale is full", SoundDsp.meterPercent(0.0) == 100)
    check("meter: louder than full scale stays full", SoundDsp.meterPercent(5.0) == 100)
    check("meter: -30 dB is half", SoundDsp.meterPercent(-30.0) == 50)
    check("meter: -45 dB is a quarter", SoundDsp.meterPercent(-45.0) == 25)
    check("meter: louder fills more", SoundDsp.meterPercent(-40.0) < SoundDsp.meterPercent(-20.0))
    check("meter: a full-scale tone is nearly full", SoundDsp.meterPercent(SoundDsp.rmsDb(tone)) in 94..95)

    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED"); return failedNames.toList()
}

/** The JUnit test: runs the checks above, and fails naming them if any fail. */
class SoundLevelChecks {
    @org.junit.Test
    fun everyCheckPasses() {
        val failed = runChecks()
        org.junit.Assert.assertTrue("Failed checks: " + failed.joinToString("; "), failed.isEmpty())
    }
}
