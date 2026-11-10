package com.itsaky.androidide.plugins.aiagentlocal.device.descriptor

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.JsonException
import com.itsaky.androidide.plugins.aiagentlocal.device.Part1TestResources
import java.math.BigInteger
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
    fun givenAnIntegerBeyondTheLongRangeInHwcap_whenDecoded_thenTheBitsAreStillNamedExactly() {
        val s = staticOf("t0-no-rows-no-record")
        val feats = Json.obj(s["features"], "f") + ("hwcap" to BigInteger("18446744073709551615")) + ("hwcap2" to null)
        val d = Json.fromOrg(gen(s + ("features" to feats))) as Map<*, *>
        val f = ((d["capabilities"] as Map<*, *>)["features"] as Map<*, *>)
        val names = f["value"] as List<*>
        assertEquals(48, names.size) // all 48 HWCAP names set, bits 48..63 unnamed
        assertTrue((f["note"] as String).contains("hwcap bit 63"))
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
}
