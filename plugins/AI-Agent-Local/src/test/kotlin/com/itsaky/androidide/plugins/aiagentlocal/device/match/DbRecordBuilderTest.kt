package com.itsaky.androidide.plugins.aiagentlocal.device.match

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.Part1TestResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DbRecordBuilderTest {

    private val real = Part1TestResources.realIndex()

    @Test
    fun givenIdentityValues_whenARecordIsBuilt_thenItEqualsWhatBuildExamplesPyBuilds() {
        val diff = Part1TestResources.obj("part1/match-differential.json")
        val cases = Json.arr(diff["dbrecords"], "dbrecords").map { Json.obj(it, "case") }
        assertTrue(cases.size >= 50)
        val failures = ArrayList<String>()
        for (c in cases) {
            val i = Json.arr(c["input"], "input").map { it as String }
            val expect = Json.obj(c["expect"], "expect")
            val got = Json.fromOrg(DbRecordBuilder.build(i[0], i[1], i[2], i[3], expect["match_source"] as String, real))
            val d = Json.diff(expect, got)
            if (d.isNotEmpty()) failures.add("$i differs at $d")
        }
        assertEquals(emptyList<String>(), failures.take(5))
        assertTrue(cases.any { (it["expect"] as Map<*, *>)["record"] != null })
        assertTrue(cases.any { (it["expect"] as Map<*, *>)["record"] == null })
    }

    @Test
    fun givenNoMatch_whenARecordIsBuilt_thenRecordIsNullAndTheIndexShaIsOfTheDecompressedIndex() {
        val rec = Json.obj(Json.fromOrg(DbRecordBuilder.build("", "", "", "", "synthetic", real)), "record")
        assertEquals("none", Json.obj(rec["match"], "m")["result"])
        assertEquals(null, rec["record"])
        assertEquals(DeviceIndex.ASSET_JSON_SHA256, Json.obj(rec["index"], "i")["index_sha256"])
        assertEquals("part1-dbrecord-v1", rec["format"])
    }
}
