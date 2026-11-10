package com.itsaky.androidide.plugins.aiagentlocal.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.plugins.aiagentlocal.device.descriptor.DescriptorGenerator
import com.itsaky.androidide.plugins.aiagentlocal.device.match.DeviceIndex
import com.itsaky.androidide.plugins.aiagentlocal.device.match.DeviceIndexMatcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the Part 1 golden set and the match vectors on a device, where `org.json` is Android's own
 * implementation (JVM unit tests use the `org.json:json` library instead). The golden set and the vectors
 * are the same files the unit tests read (`src/test/resources/part1`, shipped in the test APK's assets);
 * the index is the plugin's own asset. Compiled in the job that wrote it, not run there: it needs a phone.
 */
@RunWith(AndroidJUnit4::class)
class Part1DeviceInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testAssets = instrumentation.context.assets
    private val appAssets = instrumentation.targetContext.assets

    private fun testText(path: String): String = testAssets.open(path).use { String(it.readBytes(), Charsets.UTF_8) }

    @Test
    fun givenEveryGoldenTriple_whenGeneratedWithAndroidOrgJson_thenTheDescriptorEqualsTheExpectedOne() {
        val names = testAssets.list("part1/golden")!!.sorted()
        assertTrue("at least 40 triples, found ${names.size}", names.size >= 40)
        val failures = ArrayList<String>()
        for (name in names) {
            val base = "part1/golden/$name"
            val files = testAssets.list(base)!!.toSet()
            val args = Json.parseObject(testText("$base/args.json"))
            val rows = if ("rows.jsonl" in files) testText("$base/rows.jsonl").lines() else emptyList()
            val rec = if ("dbrecord.json" in files) JSONObject(testText("$base/dbrecord.json")) else null
            val got = DescriptorGenerator.generate(
                JSONObject(testText("$base/static.json")), rows, rec,
                pluginVersion = args["plugin_version"] as String?,
                peakTolerance = Json.double(args["peak_tolerance"], "peak_tolerance"),
                inputNames = Json.arr(args["input_names"], "input_names").map { it as String },
            )
            val diff = Json.diff(Json.parse(testText("$base/expected.json")), Json.fromOrg(got))
            if (diff.isNotEmpty()) failures.add("$name differs at $diff")
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun givenEveryMatchVector_whenMatchedAgainstTheShippedIndex_thenTheResultIsAsExpected() {
        val real = DeviceIndex.load(appAssets.open(DeviceIndex.ASSET_PATH))
        assertEquals(DeviceIndex.ASSET_JSON_SHA256, real.sha256)
        val doc = Json.parseObject(testText("part1/vectors.json"))
        val fixture = DeviceIndex.fromTree(Json.obj(doc["fixture_index"], "fixture_index"))
        val failures = ArrayList<String>()
        for (v in Json.arr(doc["vectors"], "vectors").map { Json.obj(it, "vector") }) {
            val input = Json.obj(v["input"], "input")
            val expect = Json.obj(v["expect"], "expect")
            val got = DeviceIndexMatcher.match(
                input["platform"] as String, input["board"] as String, input["device"] as String, input["hardware"] as String,
                if (v["index"] == "real") real else fixture,
            )
            if (got.result != expect["result"] || got.key != expect["key"] || got.howClass != expect["how_class"]) failures.add(v["id"] as String)
        }
        assertEquals(emptyList<String>(), failures)
    }
}
