package com.itsaky.androidide.plugins.aiagentlocal.device

import com.itsaky.androidide.plugins.aiagentlocal.device.descriptor.DescriptorGenerator
import com.itsaky.androidide.plugins.aiagentlocal.device.descriptor.PinProbe
import java.math.BigDecimal
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Tests of the items carried from the P1-04C+D review (P1-04A1 step 0): F01-F04, F09, F10. */
class CarriedItemsTest {

    private fun <T> withTokener(factory: (String) -> JSONTokener, block: () -> T): T {
        val saved = Json.tokenerFactory
        Json.tokenerFactory = factory
        try {
            return block()
        } finally {
            Json.tokenerFactory = saved
        }
    }

    /** What `Json.parse` makes of [text] under one implementation: a value, or "refused" (the path differs: Android's tokener fails on an infinity itself). */
    private fun outcome(text: String, android: Boolean): String = try {
        val factory: (String) -> JSONTokener = if (android) { s -> AndroidLikeTokener(s) } else { s -> JSONTokener(s) }
        val v = withTokener(factory) { Json.parseObject("""{"a":$text}""")["a"] }
        "${v!!.javaClass.simpleName}:$v"
    } catch (e: JsonException) {
        "refused"
    }

    // ---- F02 / F03 -------------------------------------------------------------------------------

    @Test
    fun givenLiteralsNotExactlyRepresentableAsDoubles_whenParsedUnderBothImplementations_thenTheResultsAreTheSame() {
        val expected = mapOf(
            "2.0000000000000000001" to "Long:2",
            "1800000.00000000000000001" to "Long:1800000",
            "1e-400" to "Long:0",
            "-1e-400" to "Long:0",
            "0.30000000000000000001" to "Double:0.3",
            "8.9999999999999999999" to "Long:9",
            "9007199254740993.0" to "refused", // integral value 2^53 + 1 rounds to 2^53 on a double: refused on both
            "9007199254740993" to "refused",
            "1e400" to "refused",
        )
        for ((text, want) in expected) {
            assertEquals("JVM library: $text", want, outcome(text, android = false))
            assertEquals("Android-like tokener: $text", want, outcome(text, android = true))
        }
    }

    @Test
    fun givenABigDecimalWhoseNearestDoubleIsIntegral_whenNormalised_thenItIsAnIntegerLikeTheDoubleItRoundsTo() {
        assertEquals(2L, Json.number(BigDecimal("2.0000000000000000001")))
        assertEquals(2L, Json.number(2.0))
        assertEquals(1800000L, Json.number(BigDecimal("1800000.00000000000000001")))
        assertEquals(0L, Json.number(BigDecimal("1E-400")))
        assertEquals(0.3, Json.number(BigDecimal("0.30000000000000000001")))
        try {
            Json.number(BigDecimal("1E+400"), "$.n")
            fail()
        } catch (e: JsonException) {
            assertEquals("$.n", e.path)
        }
    }

    @Test
    fun givenAFactoryThatIgnoresTheText_whenParsed_thenTheValueComesFromTheFactoryTokener() {
        // kills "Json.parse builds a plain JSONTokener instead of using tokenerFactory" (mutant N24 of the review)
        var calls = 0
        val v = withTokener({ calls++; JSONTokener("7.0") }) { Json.parse("1") }
        assertEquals(1, calls)
        assertEquals(7L, v)
        assertEquals(1L, Json.parse("1")) // the default is restored afterwards
    }

    @Test
    fun givenTheAndroidLikeTokener_whenALiteralOfTwoToThe63IsParsed_thenItIsRefusedByBothImplementations() {
        for (android in listOf(false, true)) {
            assertEquals("refused", outcome("18446744073709551615", android))
            assertEquals("Long:9007199254740991", outcome("9007199254740991", android))
        }
    }

    // ---- F04 -------------------------------------------------------------------------------------

    private fun bits(d: Double) = java.lang.Double.doubleToRawLongBits(d)

    @Test
    fun givenRealNegativeZeros_whenSummed_thenTheTotalIsPositiveZeroLikeMathFsum() {
        // CPython 3.12: math.fsum([-0.0]) == math.fsum([-0.0, -0.0]) == math.fsum([0.0, -0.0]) == math.fsum([]) == 0.0
        // (positive), and fsum([1.0, -1.0]) is positive zero too; `round(0.0, 1)` is 0.0.
        for (items in listOf(listOf<Any?>(-0.0), listOf<Any?>(-0.0, -0.0), listOf<Any?>(0.0, -0.0), listOf<Any?>(-0.0, 0.0), emptyList(), listOf<Any?>(1.0, -1.0))) {
            val s = PyCompat.fsum(items)
            assertEquals("$items", 0L, bits(s))
            assertEquals("$items", 0L, bits(PyCompat.round1(s)))
        }
        assertEquals(-0.5, PyCompat.fsum(listOf(-0.0, -0.5)), 0.0)
        // a total of -0.04 rounds to -0.0 in Python and to 0.0 here; they compare equal and durations are not negative (KDoc)
        assertEquals(0.0, PyCompat.round1(-0.04), 0.0)
    }

    // ---- F09 / F10 -------------------------------------------------------------------------------

    private fun probe(vararg cpus: String): PinProbe = PinProbe.parse(
        """{"format":"part1-pinprobe-v1","engine_version":"part1-engine/0.2.3","gate_basis":"cpuset_top_app",
           "foreground_cpuset":"/top-app","status":"ok","exit_code":0,"duration_ms":1000.0,"cpus":[${cpus.joinToString(",")}]}""",
    )

    private fun cpu(i: Int, extra: String = "") =
        """{"index":$i,"result":"ok","attempts":1,"time_to_pin_ms":2000.0$extra}"""

    private fun staticInput(): Map<String, Any?> {
        val d = "part1/golden/" + Part1TestResources.goldenCaseNames().first { it.endsWith("t0-no-pin-results") }
        return Part1TestResources.obj("$d/static.json")
    }

    private fun cpuOf(s: Map<String, Any?>, i: Int) = Json.obj(Json.arr(s["cpus"], "cpus")[i], "cpu")

    @Test
    fun givenASecondProbeThatListsACpuTheFirstMissed_whenFolded_thenThePinIsSetAndTheOldReasonIsRemoved() {
        val first = PinProbe.fold(staticInput(), probe(cpu(0), cpu(1), cpu(2)))
        assertEquals("CPU not in the pin probe", Json.obj(first["unavailable"], "u")["cpus.pin.3"])
        assertTrue(!cpuOf(first, 3).containsKey("pin"))
        val second = PinProbe.fold(first, probe(cpu(0), cpu(1), cpu(2), cpu(3)))
        val unavailable = Json.obj(second["unavailable"], "u")
        assertTrue("the stale reason is gone: $unavailable", !unavailable.containsKey("cpus.pin.3"))
        assertEquals("ok", Json.obj(cpuOf(second, 3)["pin"], "pin")["result"])
        assertEquals("CPU not in the pin probe", unavailable["cpus.pin.4"]) // a CPU still missing keeps its reason
        assertEquals("synthetic: not probed", unavailable["opencl_loadable"])
        val d = DescriptorGenerator.generateTree(second, emptyList(), null, null, 0.05, emptyList())
        val core3 = Json.obj(Json.arr(d["cores"], "cores")[3], "core")
        assertEquals("measured_app", Json.obj(core3["pin"], "pin")["origin"])
    }

    @Test
    fun givenAMidrFromThePinProbe_whenTheDescriptorIsGenerated_thenTheLeafSourceNamesThePinProbe() {
        val s = LinkedHashMap(staticInput())
        s["cpus"] = Json.arr(s["cpus"], "c").mapIndexed { i, c -> LinkedHashMap(Json.obj(c, "cpu")).also { if (i == 0) it["midr"] = null } }
        val folded = PinProbe.fold(s, probe(cpu(0, ""","midr":"0x410fd050","midr_source":"cpuinfo_unpinned""""), cpu(1)))
        val d = DescriptorGenerator.generateTree(folded, emptyList(), null, null, 0.05, emptyList())
        val cores = Json.arr(d["cores"], "cores").map { Json.obj(it, "core") }
        val midr0 = Json.obj(cores[0]["midr"], "midr")
        assertEquals("0x410fd050", midr0["value"])
        assertEquals("pin probe (MIDR read from cpuinfo_unpinned)", midr0["source"])
        assertTrue(!(Json.obj(cores[1]["midr"], "midr")["source"] as String).startsWith("pin probe")) // a static MIDR keeps the static text
    }
}
