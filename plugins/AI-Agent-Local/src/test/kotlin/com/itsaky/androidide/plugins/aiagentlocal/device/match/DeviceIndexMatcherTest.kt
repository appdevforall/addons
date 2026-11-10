package com.itsaky.androidide.plugins.aiagentlocal.device.match

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.Part1TestResources
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIndexMatcherTest {

    private val real = Part1TestResources.realIndex()
    private val vectorsDoc = Part1TestResources.obj("part1/vectors.json")
    private val fixture = DeviceIndex.fromTree(Json.obj(vectorsDoc["fixture_index"], "fixture_index"))

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun givenTheShippedAsset_whenHashed_thenItIsTheFileThePythonReferenceBuiltAndTheIndexShaMatches() {
        val file: File = Part1TestResources.indexAssetFile()
        assertTrue(file.path.endsWith("src/main/assets/part1/part1-dbindex-v1.json.gz"))
        assertEquals(DeviceIndex.ASSET_GZ_SHA256, sha256(file.readBytes()))
        assertEquals(DeviceIndex.ASSET_JSON_SHA256, real.sha256)
        assertEquals(DeviceIndex.ASSET_JSON_SHA256, Part1TestResources.obj("part1/match-differential.json")["index_sha256"])
    }

    @Test
    fun givenPlainJsonText_whenLoaded_thenTheShaIsOfTheBytesGiven() {
        val bytes = """{"records":[],"generic_board_patterns":[],"shared_boards":[]}""".toByteArray()
        val idx = DeviceIndex.load(ByteArrayInputStream(bytes))
        assertEquals(sha256(bytes), idx.sha256)
        assertEquals(0, idx.records.size)
    }

    @Test
    fun givenThePackagedAndTheSourceAssetName_whenLoadedThroughAnOpener_thenEitherWorksAndTheShaIsTheSame() {
        val gz = Part1TestResources.indexAssetFile().readBytes()
        val plain = java.util.zip.GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes() }
        // AGP packages the asset decompressed and without the .gz suffix
        val packaged = DeviceIndex.loadFromAssets { name ->
            if (name == DeviceIndex.PACKAGED_ASSET_PATH) ByteArrayInputStream(plain) else throw java.io.FileNotFoundException(name)
        }
        val source = DeviceIndex.loadFromAssets { name ->
            if (name == DeviceIndex.ASSET_PATH) ByteArrayInputStream(gz) else throw java.io.FileNotFoundException(name)
        }
        assertEquals(DeviceIndex.ASSET_JSON_SHA256, packaged.sha256)
        assertEquals(DeviceIndex.ASSET_JSON_SHA256, source.sha256)
        assertEquals(real.records.size, packaged.records.size)
    }

    @Test
    fun givenEveryVectorOfVectorsJson_whenMatched_thenResultKeyAndHowClassAreAsExpected() {
        val vectors = Json.arr(vectorsDoc["vectors"], "vectors").map { Json.obj(it, "vector") }
        assertTrue("expected the 64 vectors of vectors_version 2", vectors.size >= 64)
        var real = 0
        var fix = 0
        val failures = ArrayList<String>()
        for (v in vectors) {
            val input = Json.obj(v["input"], "input")
            val expect = Json.obj(v["expect"], "expect")
            val idx = if (v["index"] == "real") { real++; this.real } else { fix++; fixture }
            val got = DeviceIndexMatcher.match(
                input["platform"] as String, input["board"] as String, input["device"] as String, input["hardware"] as String, idx,
            )
            if (got.result != expect["result"] || got.key != expect["key"] || got.howClass != expect["how_class"]) {
                failures.add("${v["id"]}: got ${got.result}/${got.key}/${got.howClass}, expected ${expect["result"]}/${expect["key"]}/${expect["how_class"]}")
            }
        }
        assertEquals(emptyList<String>(), failures)
        assertTrue("real-index vectors: $real", real >= 30)
        assertTrue("fixture vectors: $fix", fix >= 30)
    }

    @Test
    fun givenManyMoreInputs_whenMatched_thenEveryFieldIncludingHowEqualsMatchPy() {
        val diff = Part1TestResources.obj("part1/match-differential.json")
        val failures = ArrayList<String>()
        for ((name, idx) in listOf("real" to real, "fixture" to fixture)) {
            val cases = Json.arr(diff[name], name).map { Json.obj(it, name) }
            assertTrue(cases.size >= 200)
            for (c in cases) {
                val i = Json.arr(c["input"], "input").map { it as String }
                val e = Json.obj(c["expect"], "expect")
                val got = DeviceIndexMatcher.match(i[0], i[1], i[2], i[3], idx)
                if (got.toTree() != e) failures.add("$name $i: got ${got.toTree()} expected $e")
            }
        }
        assertEquals(emptyList<String>(), failures.take(5))
    }

    @Test
    fun givenABareStringSocRecord_whenMatchedBySocOnly_thenItIsNotASiblingSeedThroughItsIdentity() {
        val idx = DeviceIndex.fromTree(
            mapOf(
                "generic_board_patterns" to emptyList<Any?>(), "shared_boards" to emptyList<Any?>(),
                "records" to listOf(
                    mapOf("key" to "zz-bare", "identity" to mapOf("soc" to "other", "board" to "bare", "device_ids" to emptyList<Any?>(), "soc_bare_string" to true), "n_measured_leaves" to 5L),
                    mapOf("key" to "yy-seed", "identity" to mapOf("soc" to "other", "board" to "seed", "device_ids" to emptyList<Any?>()), "n_measured_leaves" to 1L),
                ),
            ),
        )
        val m = DeviceIndexMatcher.match("other", "nomatch", "d", "h", idx)
        assertEquals("soc_sibling", m.result)
        assertEquals("yy-seed", m.key) // zz-bare has more leaves but is skipped through its identity
    }
}
