package com.itsaky.androidide.plugins.aiagentlocal.device.descriptor

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.Part1TestResources
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The golden set: synthetic input triples (static, rows, record) and the descriptor `generate.py` produced
 * for each. The Kotlin generator must return the same JSON (numbers equal as numbers, key order free).
 * Every fingerprint in the set is synthetic.
 */
class GoldenSetTest {

    @Test
    fun givenEveryGoldenTriple_whenGenerated_thenTheDescriptorEqualsTheOneGenerateUsingPythonProduced() {
        val names = Part1TestResources.goldenCaseNames()
        assertTrue("at least 40 triples, found ${names.size}", names.size >= 40)
        val failures = ArrayList<String>()
        for (name in names) {
            val base = "part1/golden/$name"
            val args = Part1TestResources.obj("$base/args.json")
            val static = JSONObject(Part1TestResources.text("$base/static.json"))
            val rows = optional("$base/rows.jsonl")?.lines() ?: emptyList()
            val rec = optional("$base/dbrecord.json")?.let { JSONObject(it) }
            val got = DescriptorGenerator.generate(
                static, rows, rec,
                pluginVersion = args["plugin_version"] as String?,
                peakTolerance = Json.double(args["peak_tolerance"], "peak_tolerance"),
                inputNames = Json.arr(args["input_names"], "input_names").map { it as String },
            )
            val diff = Json.diff(Part1TestResources.tree("$base/expected.json"), Json.fromOrg(got))
            if (diff.isNotEmpty()) failures.add("$name differs at $diff")
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun givenTheGoldenSet_whenRead_thenEveryFingerprintIsSyntheticAndNoLabPathAppears() {
        val dir = File(GoldenSetTest::class.java.classLoader!!.getResource("part1/golden")!!.toURI())
        val fp = Regex("\"build_fingerprint\":\\s*\"([^\"]*)\"")
        var seen = 0
        for (f in dir.walkTopDown().filter { it.isFile }) {
            val text = f.readText()
            assertTrue("${f.name} names a lab path", !text.contains("/home/"))
            for (m in fp.findAll(text)) {
                seen++
                assertTrue("non-synthetic fingerprint in ${f.parentFile.name}", m.groupValues[1].startsWith("syn/"))
            }
        }
        assertTrue(seen >= 40)
    }

    private fun optional(path: String): String? =
        GoldenSetTest::class.java.classLoader!!.getResourceAsStream(path)?.use { String(it.readBytes(), Charsets.UTF_8) }
}
