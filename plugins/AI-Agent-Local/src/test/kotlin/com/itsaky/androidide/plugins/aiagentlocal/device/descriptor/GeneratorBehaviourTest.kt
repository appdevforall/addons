package com.itsaky.androidide.plugins.aiagentlocal.device.descriptor

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.JsonException
import com.itsaky.androidide.plugins.aiagentlocal.device.Part1TestResources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Behaviour the golden set cannot show: refused inputs (the Python raises ValueError for these). */
class GeneratorBehaviourTest {

    private fun caseDir(suffix: String) = "part1/golden/" + Part1TestResources.goldenCaseNames().first { it.endsWith(suffix) }

    private fun staticOf(suffix: String) = Part1TestResources.obj(caseDir(suffix) + "/static.json")
    private fun recordOf(suffix: String) = Part1TestResources.obj(caseDir(suffix) + "/dbrecord.json")
    private fun rowLines(suffix: String) = Part1TestResources.text(caseDir(suffix) + "/rows.jsonl").lines()

    private fun gen(static: Map<String, Any?>, rows: List<String> = emptyList(), rec: Map<String, Any?>? = null): JSONObject =
        DescriptorGenerator.generate(
            Json.toOrg(static) as JSONObject, rows, rec?.let { Json.toOrg(it) as JSONObject }, null,
        )

    @Test
    fun givenWrongFormats_whenGenerated_thenTheInputIsRefusedWithAPath() {
        val s = staticOf("t0-no-rows-no-record")
        val rec = recordOf("t1-three-clusters-exact")
        val rows = rowLines("t1-three-clusters-exact")
        val cases: Map<String, () -> Any?> = mapOf(
            "static.format" to { gen(s + ("format" to "other")) },
            "dbrecord.format" to { gen(s, rows, rec + ("format" to "x")) },
            "dbrecord.index.index_sha256" to { gen(s, rows, rec + ("index" to mapOf("index_sha256" to "XYZ"))) },
            "dbrecord.match" to { gen(s, rows, rec + ("match" to mapOf("result" to "maybe", "how_class" to "x"))) },
            "dbrecord.match_source" to { gen(s, rows, rec + ("match_source" to "   ")) },
            "dbrecord.record" to { gen(s, rows, rec + ("record" to null)) },
        )
        for ((path, f) in cases) {
            try {
                f()
                fail("accepted input violating $path")
            } catch (e: JsonException) {
                assertEquals(path, e.path)
            }
        }
    }

    @Test
    fun givenABadPinOriginWithPinConditions_whenGenerated_thenItIsAnError() {
        try {
            gen(staticOf("t0-no-rows-no-record") + ("pin_origin" to "bogus"))
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains("bad origin"))
        }
    }

    @Test
    fun givenAHexWordWithAllSixtyFourBitsSet_whenDecoded_thenTheBitsAreStillNamedExactly() {
        val s = staticOf("t0-no-rows-no-record")
        val feats = Json.obj(s["features"], "f") + ("hwcap" to "0xffffffffffffffff") + ("hwcap2" to null)
        val d = Json.fromOrg(gen(s + ("features" to feats))) as Map<*, *>
        val caps = d["capabilities"] as Map<*, *>
        val f = caps["features"] as Map<*, *>
        val names = f["value"] as List<*>
        assertEquals(48, names.size) // all 48 HWCAP names set, bits 48..63 unnamed
        assertTrue((f["note"] as String).contains("hwcap bit 63"))
        assertEquals("0xffffffffffffffff", (caps["hwcap"] as Map<*, *>)["value"]) // a word of 2^53 or more is a hex string in the descriptor
    }

    @Test
    fun givenWordsBelowAndAtTwoToThe53_whenGenerated_thenTheDescriptorAlwaysHoldsALowerCaseHexString() {
        val s = staticOf("t0-no-rows-no-record")
        fun words(hw: Any?, hw2: Any?): Map<*, *> {
            val feats = Json.obj(s["features"], "f") + ("hwcap" to hw) + ("hwcap2" to hw2)
            return (Json.fromOrg(gen(s + ("features" to feats))) as Map<*, *>)["capabilities"] as Map<*, *>
        }
        val c = words((1L shl 53) - 1, "0x20000000000000")
        assertEquals("0x1fffffffffffff", (c["hwcap"] as Map<*, *>)["value"]) // an integer input below 2^53 is still accepted; the leaf is a string
        assertEquals("0x20000000000000", (c["hwcap2"] as Map<*, *>)["value"])
        val u = words("0x1FFFFFFFFFFFFF", "0x1") // upper-case hex digits are allowed; the descriptor is lower-case
        assertEquals("0x1fffffffffffff", (u["hwcap"] as Map<*, *>)["value"])
        assertEquals("0x1", (u["hwcap2"] as Map<*, *>)["value"])
        val z = words(0L, "0x0")
        assertEquals("0x0", (z["hwcap"] as Map<*, *>)["value"])
        assertEquals("0x0", (z["hwcap2"] as Map<*, *>)["value"])
        for (k in listOf("hwcap", "hwcap2")) assertTrue((z[k] as Map<*, *>)["value"] is String)
    }

    @Test
    fun givenBadWordsAndBigNumbers_whenGenerated_thenEachIsRefusedWithAPathLikeGeneratePy() {
        val s = staticOf("t0-no-rows-no-record")
        val rows = rowLines("t1-three-clusters-exact")
        val rec = recordOf("t1-three-clusters-exact")
        fun withFeature(k: String, v: Any?) = s + ("features" to (Json.obj(s["features"], "f") + (k to v)))
        val two53 = 1L shl 53
        val cases: Map<String, () -> Any?> = mapOf(
            "static.features.hwcap" to { gen(withFeature("hwcap", two53)) }, // an integer at 2^53
            "static.features.hwcap2" to { gen(withFeature("hwcap2", java.math.BigInteger("18446744073709551615"))) },
            "static.features.hwcap#neg" to { gen(withFeature("hwcap", -1L)) },
            "static.features.hwcap#float" to { gen(withFeature("hwcap", 1.5)) },
            "static.features.hwcap#bool" to { gen(withFeature("hwcap", true)) },
            "static.features.hwcap#empty" to { gen(withFeature("hwcap", "")) },
            "static.features.hwcap#no0x" to { gen(withFeature("hwcap", "119fff")) },
            "static.features.hwcap#bare" to { gen(withFeature("hwcap", "0x")) },
            "static.features.hwcap#17digits" to { gen(withFeature("hwcap", "0x10000000000000000")) },
            "static.features.hwcap#nonhex" to { gen(withFeature("hwcap", "0xzz")) },
            "static.features.hwcap#decimal" to { gen(withFeature("hwcap", "123")) },
            "static.features.hwcap#space" to { gen(withFeature("hwcap", " 0x1")) },
            "static.features.hwcap#list" to { gen(withFeature("hwcap", listOf(1L))) },
            "static.memory.memtotal_kb" to { gen(s + ("memory" to mapOf("memtotal_kb" to two53))) },
            "dbrecord.record.n_measured_leaves" to { gen(s, rows, rec + ("record" to (Json.obj(rec["record"], "r") + ("n_measured_leaves" to two53)))) },
        )
        for ((path, f) in cases) {
            val want = path.substringBefore('#')
            try {
                f()
                fail("accepted input violating $path")
            } catch (e: JsonException) {
                assertEquals(path, want, e.path)
            }
        }
        // a row with an integer of 2^53 or more
        try {
            DescriptorGenerator.generate(Json.toOrg(s) as JSONObject, rows.map { if (it.contains("C4-read-all")) it.replace("\"exit_code\": 0", "\"exit_code\": 9007199254740992") else it }, null, null)
            fail("a row with an integer of 2^53 was accepted")
        } catch (e: JsonException) {
            assertTrue(e.path, e.path.startsWith("line ") && e.path.endsWith(".exit_code"))
        }
    }

    @Test
    fun givenAPinOriginOfNull_whenGenerated_thenItIsAnError() {
        // generate.py: static.get("pin_origin", "measured_app") is None, and leaf() raises ValueError("bad origin None")
        try {
            gen(staticOf("t0-no-rows-no-record") + ("pin_origin" to null))
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains("bad origin"))
        }
    }

    @Test
    fun givenAnyGoldenInput_whenGeneratedTwice_thenTheResultsAreEqual() {
        val dir = caseDir("t1-three-clusters-exact")
        val s = JSONObject(Part1TestResources.text("$dir/static.json"))
        val rows = Part1TestResources.text("$dir/rows.jsonl").lines()
        val r = JSONObject(Part1TestResources.text("$dir/dbrecord.json"))
        val a = Json.fromOrg(DescriptorGenerator.generate(s, rows, r, "1"))
        val b = Json.fromOrg(DescriptorGenerator.generate(s, rows, r, "1"))
        assertEquals(emptyList<String>(), Json.diff(a, b))
    }

    @Test
    fun givenAWordAsALongAtTheBoundary_whenRead_thenTwoToThe53IsRefusedAndOneBelowIsKept() {
        // the JSON reader refuses such integers earlier; the word reader is also a boundary of its own
        assertEquals(java.math.BigInteger.valueOf((1L shl 53) - 1), DescriptorGenerator.word((1L shl 53) - 1, "w"))
        try {
            DescriptorGenerator.word(1L shl 53, "w")
            fail("2^53 accepted")
        } catch (e: JsonException) {
            assertEquals("w", e.path)
        }
        assertEquals(null, DescriptorGenerator.word(null, "w"))
        assertEquals(java.math.BigInteger("18446744073709551615"), DescriptorGenerator.word("0xffffffffffffffff", "w"))
    }

    @Test
    fun givenMemberValuesAsALongAndADoubleOfTheSameNumber_whenAClusterIsBuilt_thenTheyAreOneValue() {
        // a tree that did not come from JSON text may hold 1800000 and 1800000.0 side by side (generate.py B2)
        val s = staticOf("t0-no-rows-no-record")
        val cpus = Json.arr(s["cpus"], "cpus").mapIndexed { i, c ->
            if (i == 1) Json.obj(c, "cpu") + ("max_khz" to 1800000.0) else c
        }
        val d = DescriptorGenerator.generateTree(s + ("cpus" to cpus), emptyList(), null, null, 0.05, emptyList())
        val k0 = Json.obj(Json.arr(d["clusters"], "clusters")[0], "k0")
        assertEquals("static_read", Json.obj(k0["max_khz"], "max_khz")["origin"])
    }
}
