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
            System.getProperty("part1.goldenOutDir")?.let { File(it, "$name.json").writeText(got.toString(1)) }
            val diff = Json.diff(Part1TestResources.tree("$base/expected.json"), Json.fromOrg(got))
            if (diff.isNotEmpty()) failures.add("$name differs at $diff")
        }
        assertEquals(emptyList<String>(), failures)
    }

    /**
     * The synthetic-fingerprint guard (review F15): every committed test resource of the Part 1 code (all three input
     * kinds, the expected descriptors, vectors, differential and rejection files) is scanned. Two checks:
     *  - the JSON trees: every value of a key `build_fingerprint` (a string, or the `value` of a leaf) must start with
     *    `syn/` or `synthetic/`;
     *  - the raw text: no string shaped like an Android build fingerprint (`brand/product/device:release/id/incr:type/tags`)
     *    may appear unless it starts with `syn/` or `synthetic/`; no lab path (`/home/`) appears.
     */
    @Test
    fun givenEveryPart1TestResource_whenScanned_thenEveryFingerprintIsSyntheticAndNoLabPathAppears() {
        val dir = File(GoldenSetTest::class.java.classLoader!!.getResource("part1")!!.toURI())
        val shape = Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+:[^/\\s\"]+/[^/\\s\"]+/[^/\\s\"]+:[A-Za-z]+/[A-Za-z0-9,._-]+")
        val key = Regex("\"build_fingerprint\"")
        var files = 0
        var seen = 0
        val bad = ArrayList<String>()
        for (f in dir.walkTopDown().filter { it.isFile }) {
            files++
            val text = f.readText()
            val where = "${f.parentFile.name}/${f.name}"
            if (text.contains("/home/")) bad.add("$where names a lab path")
            for (m in shape.findAll(text)) {
                if (!isSynthetic(m.value)) bad.add("$where holds a fingerprint-shaped string")
            }
            if (!key.containsMatchIn(text)) continue
            val trees: List<Any?> = when {
                f.name.endsWith(".json") -> listOf(Json.parse(text, where))
                // review F11: a row log is one JSON object per line (blank and `#` lines are comments)
                f.name.endsWith(".jsonl") -> jsonlTrees(text, where)
                else -> emptyList()
            }
            for (t in trees) for (v in fingerprintValues(t)) {
                seen++
                if (!isSynthetic(v)) bad.add("$where holds a non-synthetic build_fingerprint")
            }
        }
        assertEquals(emptyList<String>(), bad)
        assertTrue("files scanned: $files", files > 300)
        assertTrue("fingerprint values seen: $seen", seen >= 100)
    }

    /** The JSON objects of a `.jsonl` text: blank lines and lines starting with `#` are skipped. */
    private fun jsonlTrees(text: String, where: String): List<Any?> =
        text.lines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }.mapIndexed { i, l -> Json.parse(l, "$where:${i + 1}") }

    @Test
    fun givenAJsonlRowLog_whenScanned_thenItsBuildFingerprintKeysAreFound() {
        val fake = listOf("brand", "product", "device").joinToString("/") + ":14/ID1.0/1234:user/release-keys"
        val text = "# comment\n\n{\"tag\":\"A\",\"conditions\":{\"build_fingerprint\":\"syn/x:1\"}}\n{\"build_fingerprint\":\"$fake\"}\n"
        val values = jsonlTrees(text, "x.jsonl").flatMap { fingerprintValues(it) }
        assertEquals(listOf("syn/x:1", fake), values)
        assertTrue(values.any { !isSynthetic(it) })
    }

    private fun isSynthetic(v: String) = v.startsWith("syn/") || v.startsWith("synthetic/")

    /** Every `build_fingerprint` value in a tree: a string, or the `value` of a leaf object; null is not a value. */
    private fun fingerprintValues(x: Any?): List<String> {
        val out = ArrayList<String>()
        when (x) {
            is Map<*, *> -> for ((k, v) in x) {
                if (k == "build_fingerprint") {
                    val inner = if (v is Map<*, *>) v["value"] else v
                    if (inner is String) out.add(inner)
                } else {
                    out.addAll(fingerprintValues(v))
                }
            }
            is List<*> -> x.forEach { out.addAll(fingerprintValues(it)) }
        }
        return out
    }

    @Test
    fun givenTheGuard_whenAFingerprintShapedStringIsNotSynthetic_thenItIsDetected() {
        val shape = Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+:[^/\\s\"]+/[^/\\s\"]+/[^/\\s\"]+:[A-Za-z]+/[A-Za-z0-9,._-]+")
        // a made-up string of the real shape, built from parts so that this file itself holds no such string
        val fake = listOf("brand", "product", "device").joinToString("/") + ":14/ID1.0/1234:user/release-keys"
        assertTrue(shape.containsMatchIn(fake))
        assertTrue(!isSynthetic(fake))
        assertEquals(listOf(fake), fingerprintValues(mapOf("identity" to mapOf("build_fingerprint" to mapOf("value" to fake)))))
        assertEquals(listOf(fake), fingerprintValues(listOf(mapOf("build_fingerprint" to fake))))
        assertTrue(isSynthetic("syn/fingerprint:1") && isSynthetic("synthetic/x"))
    }

    private fun optional(path: String): String? =
        GoldenSetTest::class.java.classLoader!!.getResourceAsStream(path)?.use { String(it.readBytes(), Charsets.UTF_8) }
}
