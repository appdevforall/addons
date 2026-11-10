package com.itsaky.androidide.plugins.aiagentlocal.device

import com.itsaky.androidide.plugins.aiagentlocal.device.descriptor.DescriptorGenerator
import java.math.BigDecimal
import java.math.BigInteger
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A stand-in for Android's `JSONTokener` number parsing, so that the JVM tests can see what the phone would see
 * (review F01). Android's `readLiteral` gives a `Long` (or `Integer`) when `Long.parseLong` succeeds and a `Double`
 * for anything else, including every integer literal that does not fit in a `Long` (its precision is lost there).
 * `org.json:json`, which the JVM tests otherwise use, gives an exact `BigInteger` or `BigDecimal` instead.
 */
class AndroidLikeTokener(text: String) : JSONTokener(text) {
    override fun nextValue(): Any? {
        val v = super.nextValue()
        return when (v) {
            is BigInteger -> if (v.bitLength() < 64) v.toLong() else v.toDouble()
            is BigDecimal -> v.toDouble()
            else -> v
        }
    }
}

class AndroidNumberStandInTest {

    private fun <T> withAndroidNumbers(block: () -> T): T {
        val saved = Json.tokenerFactory
        Json.tokenerFactory = { AndroidLikeTokener(it) }
        try {
            return block()
        } finally {
            Json.tokenerFactory = saved
        }
    }

    private fun optional(path: String): String? =
        AndroidNumberStandInTest::class.java.classLoader!!.getResourceAsStream(path)?.use { String(it.readBytes(), Charsets.UTF_8) }

    @Test
    fun givenIntegerLiterals_whenReadByTheStandIn_thenTheyArriveAsAndroidWouldGiveThem() {
        val o = JSONObject(AndroidLikeTokener("""{"a":9223372036854775807,"b":9223372036854775808,"c":18446744073709551615,"d":1.5,"e":7,"f":1e2}"""))
        assertEquals(Long.MAX_VALUE, (o.get("a") as Number).toLong())
        assertTrue(o.get("a") !is BigInteger)
        assertEquals(9.223372036854775808E18, o.get("b") as Double, 0.0) // rounded: the value of 2^63
        assertEquals(1.8446744073709552E19, o.get("c") as Double, 0.0) // 2^64 - 1 rounded to 2^64
        assertTrue(o.get("d") is Double)
        assertEquals(7L, (o.get("e") as Number).toLong())
        assertEquals(100.0, (o.get("f") as Number).toDouble(), 0.0)
    }

    @Test
    fun givenAnIntegerWordOfTwoToThe63OrMore_whenReadThroughTheStandIn_thenItIsRejectedNeverRounded() {
        val text = Part1TestResources.text("part1/golden/" + Part1TestResources.goldenCaseNames().first { it.endsWith("t0-no-rows-no-record") } + "/static.json")
        val broken = text.replace("\"hwcap2\": 0", "\"hwcap2\": 9223372036854775809")
        assertTrue(broken != text)
        withAndroidNumbers {
            try {
                DescriptorGenerator.generate(JSONObject(AndroidLikeTokener(broken)), emptyList(), null, null)
                fail("a word of 2^63 or more was accepted")
            } catch (e: JsonException) {
                assertEquals("static.features.hwcap2", e.path)
            }
        }
    }

    @Test
    fun givenTheWholeGoldenSet_whenAllInputsAndExpectationsAreReadLikeAndroid_thenEveryDescriptorEqualsTheExpectedOne() {
        val names = Part1TestResources.goldenCaseNames()
        assertTrue(names.size >= 100)
        val failures = ArrayList<String>()
        withAndroidNumbers {
            for (name in names) {
                val base = "part1/golden/$name"
                val args = Part1TestResources.obj("$base/args.json")
                val static = JSONObject(AndroidLikeTokener(Part1TestResources.text("$base/static.json")))
                val rows = optional("$base/rows.jsonl")?.lines() ?: emptyList()
                val rec = optional("$base/dbrecord.json")?.let { JSONObject(AndroidLikeTokener(it)) }
                val got = DescriptorGenerator.generate(
                    static, rows, rec,
                    pluginVersion = args["plugin_version"] as String?,
                    peakTolerance = Json.double(args["peak_tolerance"], "peak_tolerance"),
                    inputNames = Json.arr(args["input_names"], "input_names").map { it as String },
                )
                // the expectation is also read like Android; the descriptor is compared as a tree
                val diff = Json.diff(Part1TestResources.tree("$base/expected.json"), Json.fromOrg(got))
                if (diff.isNotEmpty()) failures.add("$name differs at $diff")
            }
        }
        assertEquals(emptyList<String>(), failures)
    }
}
