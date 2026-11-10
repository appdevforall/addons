package com.itsaky.androidide.plugins.aiagentlocal.device.descriptor

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.JsonException
import com.itsaky.androidide.plugins.aiagentlocal.device.Part1TestResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every rejection path of `Rows.kt` and `PinProbe.kt`, compared with the JSON schemas that `generate.py`
 * (`check_rows`) and the engine ship. The resources hold about 900 row variants and 400 probe variants (each
 * field deleted, replaced by 14 other values, plus enum, pattern and conditional-rule cases) and the verdict
 * of the schema for each. `kotlin` is the verdict of the schema without `additionalProperties: false`: the Kotlin
 * parsers ignore unknown keys (the engine may add optional fields) while the Python check rejects them, an
 * intended difference (review F13). `python` is the verdict of the schema as shipped.
 */
class RejectionTest {

    private class Verdicts(val total: Int, val bad: List<String>, val intended: Int, val accepted: Int)

    private fun run(resource: String, parse: (Any?) -> Unit): Verdicts {
        val cases = Json.arr(Part1TestResources.tree(resource), resource)
        val bad = ArrayList<String>()
        var intended = 0
        var accepted = 0
        for (c0 in cases) {
            val c = Json.obj(c0, "case")
            val accepts = try {
                parse(c["doc"])
                true
            } catch (e: JsonException) {
                false
            }
            if (accepts) accepted++
            if (c["python"] != c["kotlin"]) intended++
            if (accepts != c["kotlin"]) bad.add("${c["name"]}: kotlin accepts=$accepts, the schema says ${c["kotlin"]}")
        }
        return Verdicts(cases.size, bad, intended, accepted)
    }

    @Test
    fun givenRowVariants_whenParsed_thenEveryOneIsAcceptedOrRefusedLikeTheRowsSchema() {
        val v = run("part1/rows-rejections.json") { Row.parse(it, "row") }
        assertTrue(v.total >= 900)
        assertTrue("both outcomes are exercised", v.accepted > 100 && v.accepted < v.total - 100)
        assertEquals(emptyList<String>(), v.bad.take(30))
        assertTrue("only the unknown-key cases differ from the shipped schema", v.intended in 1..10)
    }

    @Test
    fun givenPinProbeVariants_whenParsed_thenEveryOneIsAcceptedOrRefusedLikeThePinProbeSchema() {
        val v = run("part1/pinprobe-rejections.json") { PinProbe.parse(it, "probe") }
        assertTrue(v.total >= 400)
        assertTrue("both outcomes are exercised", v.accepted > 30 && v.accepted < v.total - 100)
        assertEquals(emptyList<String>(), v.bad.take(30))
        assertTrue(v.intended in 1..10)
    }
}
