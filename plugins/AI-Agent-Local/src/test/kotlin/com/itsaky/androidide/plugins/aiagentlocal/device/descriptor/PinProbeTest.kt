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

    // ---- "Folding a pin probe" rules (part1-inputs-v1.md), P1-04C C4

    private fun pinnedStatic(): Map<String, Any?> {
        val d = "part1/golden/" + Part1TestResources.goldenCaseNames().first { it.endsWith("t0-no-rows-no-record") }
        return Part1TestResources.obj("$d/static.json") // 8 CPUs, every one with an older pin result "ok", MIDR set
    }

    private fun cpuOf(folded: Map<String, Any?>, i: Int) = Json.obj(Json.arr(folded["cpus"], "cpus")[i], "cpu")

    private fun generated(folded: Map<String, Any?>) = DescriptorGenerator.generateTree(folded, emptyList(), null, null, 0.05, emptyList())

    private fun coreOf(d: Map<String, Any?>, i: Int) = Json.obj(Json.arr(d["cores"], "cores")[i], "core")

    @Test
    fun givenAnOkProbeMissingACpuThatHadAnOlderPin_whenFolded_thenTheOlderPinIsNotKeptAndTheLeavesAreUnkWithTheReason() {
        val s = pinnedStatic()
        assertTrue(Json.arr(s["cpus"], "c").all { (it as Map<*, *>)["pin"] != null })
        val folded = PinProbe.fold(s, PinProbe.parse(probe()))
        assertTrue(!cpuOf(folded, 3).containsKey("pin"))
        assertEquals("CPU not in the pin probe", Json.obj(folded["unavailable"], "u")["cpus.pin.3"])
        assertTrue(!Json.obj(folded["unavailable"], "u").containsKey("cpus.pin.0")) // listed CPUs get no such reason
        assertEquals("synthetic: not probed", Json.obj(folded["unavailable"], "u")["opencl_loadable"]) // other entries are kept
        val d = generated(folded)
        for (leaf in listOf("pin", "pin_attempts")) {
            val l = Json.obj(coreOf(d, 3)[leaf], leaf)
            assertEquals("unk", l["origin"])
            assertEquals("CPU not in the pin probe", l["reason"])
        }
        assertEquals("measured_app", Json.obj(coreOf(d, 0)["pin"], "pin")["origin"])
    }

    @Test
    fun givenAProbeCpuThatFailedToPin_whenFolded_thenItFoldsAsFailedWithItsAttempts() {
        val folded = PinProbe.fold(pinnedStatic(), PinProbe.parse(probe()))
        val pin = Json.obj(cpuOf(folded, 1)["pin"], "pin")
        assertEquals("failed", pin["result"])
        assertEquals(3L, pin["attempts"])
        assertEquals(6.5, pin["time_to_pin_s"])
        val d = generated(folded)
        assertEquals("failed", Json.obj(coreOf(d, 1)["pin"], "pin")["value"])
        assertEquals(3L, Json.obj(coreOf(d, 1)["pin_attempts"], "a")["value"])
    }

    @Test
    fun givenAProbeAndADifferentStaticEnvironmentCpuset_whenFolded_thenTheCpusetIsTheProbes() {
        val s = pinnedStatic()
        val env = Json.obj(s["environment"], "env")
        assertEquals("/top-app", env["foreground_cpuset"])
        val folded = PinProbe.fold(s, PinProbe.parse(probe().replace("\"/top-app\"", "\"/foreground\"")))
        assertEquals("/foreground", Json.obj(folded["pin_conditions"], "c")["foreground_cpuset"])
        assertEquals("/foreground", Json.obj(cpuOf(folded, 0)["pin"], "pin")["cpuset"])
        assertEquals("/top-app", Json.obj(Json.obj(folded["environment"], "e"), "e")["foreground_cpuset"]) // the environment is not changed
    }

    @Test
    fun givenAProbeMidr_whenTheStaticCpuHasNone_thenItIsCarriedWithItsSource() {
        val s = LinkedHashMap(pinnedStatic())
        s["cpus"] = Json.arr(s["cpus"], "c").mapIndexed { i, c ->
            val m = LinkedHashMap(Json.obj(c, "cpu"))
            if (i == 0) m["midr"] = null
            if (i == 1) m.remove("midr")
            m
        }
        val folded = PinProbe.fold(s, PinProbe.parse(probe()))
        assertEquals("0x410fd050", cpuOf(folded, 0)["midr"])
        assertEquals("sysfs", cpuOf(folded, 0)["midr_source"])
        assertEquals(null, cpuOf(folded, 1)["midr"]) // the probe has no MIDR for CPU 1
        assertTrue(!cpuOf(folded, 1).containsKey("midr_source"))
        assertEquals("pin probe part1-engine/0.2.3", Json.obj(folded["pin_conditions"], "c")["note"]) // no disagreement, no extra note
    }

    @Test
    fun givenAProbeMidrThatDisagreesWithTheStaticOne_whenFolded_thenTheStaticValueIsKeptWithANote() {
        val s = pinnedStatic()
        val staticMidr = cpuOf(s, 0)["midr"]
        val folded = PinProbe.fold(s, PinProbe.parse(probe()))
        assertEquals(staticMidr, cpuOf(folded, 0)["midr"])
        assertTrue(!cpuOf(folded, 0).containsKey("midr_source"))
        assertEquals(
            "pin probe part1-engine/0.2.3; MIDR of CPU 0 differs between the static read and the pin probe; the static value is kept",
            Json.obj(folded["pin_conditions"], "c")["note"],
        )
    }

    @Test
    fun givenAProbeMidrEqualToTheStaticOneInAnotherCase_whenFolded_thenThereIsNoNote() {
        val s = LinkedHashMap(pinnedStatic())
        s["cpus"] = Json.arr(s["cpus"], "c").mapIndexed { i, c -> if (i == 0) LinkedHashMap(Json.obj(c, "cpu")).also { it["midr"] = "0x410FD050" } else c }
        val folded = PinProbe.fold(s, PinProbe.parse(probe()))
        assertEquals("0x410FD050", cpuOf(folded, 0)["midr"])
        assertEquals("pin probe part1-engine/0.2.3", Json.obj(folded["pin_conditions"], "c")["note"])
    }

    @Test
    fun givenAProbeCpuThatTheStaticInputDoesNotList_whenFolded_thenItIsIgnored() {
        val folded = PinProbe.fold(
            pinnedStatic(),
            PinProbe.parse(probe(cpus = """[{"index":99,"result":"ok","attempts":1,"time_to_pin_ms":1.0,"midr":"0x410fd050","midr_source":"sysfs"}]""")),
        )
        assertEquals(8, Json.arr(folded["cpus"], "c").size)
        assertTrue(Json.arr(folded["cpus"], "c").none { (it as Map<*, *>).containsKey("midr_source") })
        assertTrue(Json.arr(folded["cpus"], "c").all { !(it as Map<*, *>).containsKey("pin") }) // none of the 8 is in this probe
    }

    @Test
    fun givenAnOkProbe_whenFolded_thenPinOriginAndConditionsFollowTheRules() {
        val s = LinkedHashMap(pinnedStatic())
        s["environment"] = null
        s["pin_conditions"] = mapOf("kit_marker" to "kept", "labels" to listOf("x"))
        s["pin_origin"] = "measured_shell"
        val folded = PinProbe.fold(s, PinProbe.parse(probe()))
        assertEquals("measured_app", folded["pin_origin"])
        val c = Json.obj(folded["pin_conditions"], "c")
        assertEquals("kept", c["kit_marker"]) // an existing key is kept
        assertTrue(c.containsKey("thermal_status") && c["thermal_status"] == null) // the key rule R3 needs
        assertEquals("single_shot", c["regime"])
        assertEquals("cpuset_top_app", c["gate_basis"])
    }
}
