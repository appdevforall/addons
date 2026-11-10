package com.itsaky.androidide.plugins.aiagentlocal.device.descriptor

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.JsonException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RowsTest {

    private fun rowJson(mutate: (MutableMap<String, Any?>) -> Unit = {}): String {
        val r = linkedMapOf<String, Any?>(
            "format" to "part1-rows-v1", "tag" to "C2-read-k0", "kind" to "read", "status" to "ok", "cpu_set" to listOf(0),
            "threads" to 1, "buffer_mib" to 256.0, "gbs_median" to 4.5, "gbs_min" to null, "gbs_max" to null, "repeats" to 1,
            "single_shot" to true, "variant" to "synthetic", "pin" to linkedMapOf("result" to "ok", "attempts" to 1, "time_to_pin_ms" to 1.5),
            "exit_code" to 0, "gate_basis" to "cpuset_top_app", "conditions" to linkedMapOf("thermal_status" to 0, "regime" to "single_shot"),
            "duration_s" to 9.0, "engine_version" to "synthetic-engine/1", "origin" to "measured_app", "labels" to listOf<String>(),
        )
        mutate(r)
        return (Json.toOrg(r) as org.json.JSONObject).toString()
    }

    private fun parse(mutate: (MutableMap<String, Any?>) -> Unit = {}): Row = RowLog.parse(listOf(rowJson(mutate))).single()

    @Test
    fun givenAValidLine_whenParsed_thenTheFieldsAreTyped() {
        val r = parse()
        assertEquals("C2-read-k0", r.tag)
        assertEquals(listOf(0L), r.cpuSet)
        assertEquals(4.5, r.gbsMedian)
        assertEquals(1L, r.pin.attempts)
        assertEquals(9L, r.durationS) // 9.0 is the integer 9 as a value
        assertNull(r.kitTag)
    }

    @Test
    fun givenUnknownFieldsAtEveryLevel_whenParsed_thenTheyAreIgnoredNeverAnError() {
        val r = parse {
            it["future_field"] = listOf(1, 2)
            (it["pin"] as MutableMap<String, Any?>)["future_pin_field"] = "x"
            (it["conditions"] as MutableMap<String, Any?>)["future_condition"] = 7
        }
        assertEquals("ok", r.pin.result)
        assertTrue(!r.conditions.containsKey("future_condition"))
        assertEquals(0L, r.conditions["thermal_status"])
    }

    @Test
    fun givenBlankAndCommentLines_whenTheLogIsParsed_thenTheyAreSkippedAndErrorsNameTheLine() {
        val rows = RowLog.parse(listOf("# header", "", "  ", rowJson(), "   # trailing"))
        assertEquals(1, rows.size)
        try {
            RowLog.parse(listOf("# c", rowJson(), rowJson { it["tag"] = "bogus" }))
            fail()
        } catch (e: JsonException) {
            assertTrue(e.message, e.path.startsWith("line 3"))
        }
    }

    @Test
    fun givenViolationsOfTheRowSchema_whenParsed_thenEachIsRefusedWithAPath() {
        val bad: Map<String, (MutableMap<String, Any?>) -> Unit> = mapOf(
            "line 1.labels" to { it.remove("labels") },
            "line 1.format" to { it["format"] = "other" },
            "line 1.tag" to { it["tag"] = "C2-read-kx" },
            "line 1.engine_version" to { it["engine_version"] = "kit v4d" },
            "line 1.status" to { it["status"] = "weird" },
            "line 1.gbs_median" to { it["gbs_median"] = null },
            "line 1.repeats" to { it["repeats"] = 0 },
            "line 1.gbs_min" to { it["repeats"] = 3; it["single_shot"] = false },
            "line 1.single_shot" to { it["repeats"] = 3; it["gbs_min"] = 1.0; it["gbs_max"] = 2.0 },
            "line 1.cpu_set" to { it["cpu_set"] = listOf(1, 1) },
            "line 1.threads" to { it["threads"] = -1 },
            "line 1.pin.result" to { (it["pin"] as MutableMap<String, Any?>)["result"] = "maybe" },
            "line 1.pin.attempts" to { (it["pin"] as MutableMap<String, Any?>).remove("attempts") },
            "line 1.conditions.regime" to { (it["conditions"] as MutableMap<String, Any?>)["regime"] = "weird" },
            "line 1.conditions.charging" to { (it["conditions"] as MutableMap<String, Any?>)["charging"] = "yes" },
            "line 1.origin" to { it["origin"] = "kit" },
            "line 1.gbs_median" to { it["gbs_median"] = "4.5" }, // a numeric string is not a number
            "line 1.exit_code" to { it["exit_code"] = 1.5 },
        )
        for ((path, mutate) in bad) {
            try {
                parse(mutate)
                fail("accepted a row violating $path")
            } catch (e: JsonException) {
                assertEquals(path, e.path)
            }
        }
    }
}
