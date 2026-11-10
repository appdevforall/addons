package com.itsaky.androidide.plugins.aiagentlocal.device.descriptor

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.JsonException
import com.itsaky.androidide.plugins.aiagentlocal.device.Part1TestResources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PinProbeTest {

    private fun probe(extra: String = "", status: String = "ok", cpus: String? = null): String = """
        {"format":"part1-pinprobe-v1","engine_version":"part1-engine/0.2.3","gate_basis":"cpuset_top_app",
         "foreground_cpuset":"/top-app","status":"$status","exit_code":0,"duration_ms":16000.5,
         "cpus":${cpus ?: """[
           {"index":0,"result":"ok","attempts":1,"time_to_pin_ms":2000.0,"midr":"0x410fd050","midr_source":"sysfs"},
           {"index":1,"result":"failed","attempts":3,"time_to_pin_ms":6500,"midr":null,"midr_source":null},
           {"index":2,"result":"ok","attempts":2,"time_to_pin_ms":2500.5}]"""}
         $extra}
    """.trimIndent()

    private fun staticInput(): Map<String, Any?> {
        val d = "part1/golden/" + Part1TestResources.goldenCaseNames().first { it.endsWith("t0-no-pin-results") }
        return Part1TestResources.obj("$d/static.json")
    }

    @Test
    fun givenAPinProbe_whenParsed_thenFieldsAreTypedAndUnknownFieldsIgnored() {
        val p = PinProbe.parse(probe(""","future_top":1"""))
        assertEquals("part1-engine/0.2.3", p.engineVersion)
        assertEquals(3, p.cpus.size)
        assertEquals("0x410fd050", p.cpus[0].midr)
        assertNull(p.cpus[1].midr)
        assertEquals(6500L, p.cpus[1].timeToPinMs)
        assertEquals("/top-app", p.foregroundCpuset)
    }

    @Test
    fun givenSchemaViolations_whenParsed_thenEachIsRefusedWithAPath() {
        val cases = mapOf(
            "pinprobe.status" to probe(status = "maybe"),
            "pinprobe.cpus[0].midr" to probe(cpus = """[{"index":0,"result":"ok","attempts":1,"time_to_pin_ms":1,"midr":"0xZZ","midr_source":"sysfs"}]"""),
            "pinprobe.cpus[0].midr_source" to probe(cpus = """[{"index":0,"result":"ok","attempts":1,"time_to_pin_ms":1,"midr":"0x410fd050"}]"""),
            "pinprobe.cpus[0].result" to probe(cpus = """[{"index":0,"result":"slow","attempts":1,"time_to_pin_ms":1}]"""),
            "pinprobe.cpus[0].attempts" to probe(cpus = """[{"index":0,"result":"ok","time_to_pin_ms":1}]"""),
            "pinprobe.format" to probe().replace("part1-pinprobe-v1", "part1-pinprobe-v2"),
            "pinprobe.engine_version" to probe().replace("part1-engine/0.2.3", "part1 engine"),
        )
        for ((path, text) in cases) {
            try {
                PinProbe.parse(text)
                fail("accepted a probe violating $path")
            } catch (e: JsonException) {
                assertEquals(path, e.path)
            }
        }
    }

    @Test
    fun givenAnOkProbe_whenFolded_thenPinsAndPinConditionsAreSetAndTheInputIsUntouched() {
        val s = staticInput()
        val folded = PinProbe.fold(s, PinProbe.parse(probe()))
        val cpus = Json.arr(folded["cpus"], "cpus").map { Json.obj(it, "cpu") }
        val pin0 = Json.obj(cpus[0]["pin"], "pin")
        assertEquals("ok", pin0["result"]); assertEquals(1L, pin0["attempts"]); assertEquals(2L, pin0["time_to_pin_s"]); assertEquals("/top-app", pin0["cpuset"])
        assertEquals("failed", Json.obj(cpus[1]["pin"], "pin")["result"])
        assertEquals(2.5005, Json.obj(cpus[2]["pin"], "pin")["time_to_pin_s"])
        assertNull(cpus[3]["pin"]) // a CPU the probe did not cover keeps no pin
        assertEquals("measured_app", folded["pin_origin"])
        val cond = Json.obj(folded["pin_conditions"], "cond")
        assertEquals("cpuset_top_app", cond["gate_basis"])
        assertEquals("/top-app", cond["foreground_cpuset"])
        assertEquals("single_shot", cond["regime"])
        assertTrue(cond.containsKey("thermal_status"))
        assertTrue(Json.arr(s["cpus"], "c").all { (it as Map<*, *>)["pin"] == null }) // input unchanged
    }

    @Test
    fun givenAFoldedStatic_whenTheDescriptorIsGenerated_thenThePinLeavesAreMeasured() {
        val folded = PinProbe.fold(staticInput(), PinProbe.parse(probe()))
        val d = DescriptorGenerator.generateTree(folded, emptyList(), null, null, 0.05, emptyList())
        val core0 = Json.obj(Json.arr(d["cores"], "cores")[0], "core0")
        val pin = Json.obj(core0["pin"], "pin")
        assertEquals("measured_app", pin["origin"])
        assertEquals("ok", pin["value"])
        assertEquals("cpuset_top_app", Json.obj(pin["conditions"], "c")["gate_basis"])
        val core3 = Json.obj(Json.arr(d["cores"], "cores")[3], "core3")
        assertEquals("unk", Json.obj(core3["pin"], "pin")["origin"])
    }

    @Test
    fun givenASkippedOrFailedProbe_whenFolded_thenNothingChanges() {
        for (status in listOf("skipped", "failed")) {
            val s = staticInput()
            assertEquals(emptyList<String>(), Json.diff(s, PinProbe.fold(s, PinProbe.parse(probe(status = status, cpus = "[]")))))
        }
    }

    @Test
    fun givenAProbeAsOrgJson_whenParsedThroughTheTreeHelper_thenItParses() {
        val tree = Json.fromOrg(JSONObject(probe()))
        assertEquals(3, PinProbe.parse(tree).cpus.size)
    }
}
