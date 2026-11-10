package com.itsaky.androidide.plugins.aiagentlocal.device.staticread

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.descriptor.DescriptorGenerator
import com.itsaky.androidide.plugins.aiagentlocal.device.descriptor.PinProbe
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.CommandKind
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineResult
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.Recorded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StaticReaderTest {

    private fun obj(x: Any?) = Json.obj(x, "x")
    private fun at(r: StaticReadResult, vararg path: String): Any? = path.fold(r.static as Any?) { acc, k -> obj(acc)[k] }
    private fun unavailable(r: StaticReadResult) = obj(r.static["unavailable"])
    private var cases = 0

    /** The descriptor of a T0 object; the object and the descriptor are also written for the external judge (generate.py, validate.py). */
    private fun descriptor(r: StaticReadResult): Map<String, Any?> {
        val d = DescriptorGenerator.generateTree(r.static, emptyList(), null, null, 0.05, listOf("static.json"))
        Judge.write("t0-case-${"%02d".format(++cases)}-${java.lang.Long.toHexString(System.nanoTime() % 100000)}", r.static, descriptor = Json.obj(Json.fromOrg(Json.toOrg(d)), "d"))
        return d
    }
    private fun leaf(d: Map<String, Any?>, vararg path: String): Map<String, Any?> = obj(path.fold(d as Any?) { acc, k -> obj(acc)[k] })

    @Test
    fun givenAD5LikePhone_whenReadOnce_thenTheObjectHasEverySectionFromItsSource() {
        val p = D5Like()
        val r = p.reader().read("1.1.0")
        val s = r.static
        assertEquals("part1-static-v1", s["format"])
        assertEquals("1.1.0", s["plugin_version"])
        assertEquals(
            mapOf("platform" to "saturn", "board" to "exdroid", "device" to "C107", "hardware" to "sun55iw3p1", "soc_model" to "A523", "sdk_int" to 34L),
            listOf("platform", "board", "device", "hardware", "soc_model", "sdk_int").associateWith { at(r, "identity", it) },
        )
        assertEquals(listOf("arm64-v8a", "armeabi-v7a", "armeabi"), at(r, "identity", "abi_list"))
        assertEquals("synthetic/d5-like:14/SYN.1/1:user/test-keys", at(r, "identity", "build_fingerprint"))
        val cpus = Json.arr(s["cpus"], "cpus").map { obj(it) }
        assertEquals((0L..7L).toList(), cpus.map { it["index"] })
        assertEquals(listOf(0L, 1L, 2L, 3L), cpus[2]["related_cpus"])
        assertEquals(1416000L, cpus[3]["max_khz"]); assertEquals(725L, cpus[3]["capacity"])
        assertEquals(1800000L, cpus[4]["max_khz"]); assertEquals(1024L, cpus[7]["capacity"])
        assertEquals(listOf(4L, 5L, 6L, 7L), cpus[5]["related_cpus"])
        assertNull(cpus[0]["midr"]); assertNull(cpus[0]["part"])
        assertEquals("0x119fff", at(r, "features", "hwcap")); assertEquals("0x0", at(r, "features", "hwcap2")); assertEquals(4096L, at(r, "features", "page_size"))
        assertEquals(3817152L, at(r, "memory", "memtotal_kb"))
        assertEquals(2300000L, r.memAvailableKb)
        assertEquals(
            mapOf("present" to true, "device_name" to "Mali-G57", "api_version" to "1.3.225", "compute_queue" to true, "shader_float16" to "yes", "int_dot" to true),
            obj(s["vulkan"]).toMap(),
        )
        assertNull(s["opencl_loadable"]); assertNull(s["nnapi_devices"])
        val env = obj(s["environment"])
        assertEquals(0L, env["thermal_status"]); assertNull(env["thermal_headroom"]); assertEquals(100L, env["battery_pct"]); assertEquals(true, env["charging"])
        assertEquals(60L, env["display_refresh_hz"]); assertEquals("1280x800", env["display_px"]); assertEquals(true, env["screen_on"]); assertEquals("/top-app", env["foreground_cpuset"])
        assertEquals(0.12, s["duration_s"])
        assertEquals(120L, r.elapsedMs)
        assertEquals(31.5, r.conditions.batteryTempC) // temperature has no slot in the static object; the snapshot carries it
        // the unavailable entries of a fully successful read, and nothing else
        assertEquals(
            setOf("cpus.midr", "cpus.part", "features.dcache_line_bytes", "opencl_loadable", "nnapi_devices", "environment.thermal_headroom"),
            unavailable(r).keys,
        )
        assertEquals("child-process result does not apply inside the app", unavailable(r)["opencl_loadable"])
        // exactly the two probes ran, and no row
        assertEquals(listOf(CommandKind.STATIC_PROBE, CommandKind.GPU_PROBE), p.engine.commands.map { it.kind })
        // the sources name where each section came from
        val src = obj(s["sources"])
        assertTrue((src["identity"] as String).contains("Build fields"))
        assertTrue((src["cpus"] as String).contains("cpuinfo_max_freq"))
        assertTrue((src["memory"] as String).contains("/proc/meminfo"))
        assertEquals("Build.FINGERPRINT", r.origins["identity.build_fingerprint"])
        assertEquals("engine static probe property ro.board.platform", r.origins["identity.platform"])
        assertEquals("/proc/meminfo", r.origins["memory.memtotal_kb"])
        Judge.write("t0-d5-like", s)
    }

    @Test
    fun givenTheObject_whenTheDescriptorIsGenerated_thenItIsAcceptedAndTheLeavesCarryTheStaticReadSources() {
        val r = D5Like().reader().read()
        val d = descriptor(r)
        assertEquals("saturn", leaf(d, "identity", "platform")["value"])
        assertEquals("static_read", leaf(d, "identity", "platform")["origin"])
        assertEquals(3817152L, leaf(d, "capabilities", "ram_total_kb")["value"])
        assertEquals("0x119fff", leaf(d, "capabilities", "hwcap")["value"])
        assertEquals("unk", leaf(d, "capabilities", "opencl_loadable")["origin"])
        assertEquals("child-process result does not apply inside the app", leaf(d, "capabilities", "opencl_loadable")["reason"])
        val cores = Json.arr(d["cores"], "cores").map { obj(it) }
        assertEquals(8, cores.size)
        assertEquals("unk", obj(cores[0]["midr"])["origin"])
        assertEquals("static_read", obj(cores[0]["max_khz"])["origin"])
        assertEquals(2, Json.arr(d["clusters"], "clusters").size)
    }

    @Test
    fun givenEachSystemReadFails_whenRead_thenTheMatchingUnavailableEntryHasTheReasonAndNothingThrows() {
        // CPU files
        run {
            val p = D5Like()
            p.files.failing["/sys/devices/system/cpu/cpu3/cpufreq/cpuinfo_max_freq"] = "not readable (FileNotFoundException)"
            p.files.files["/sys/devices/system/cpu/cpu5/cpu_capacity"] = "abc\n"
            p.files.files.remove("/sys/devices/system/cpu/cpu6/cpufreq/related_cpus")
            val r = p.reader().read()
            assertEquals("cpu3: not readable (FileNotFoundException)", unavailable(r)["cpus.max_khz"])
            assertEquals("cpu5: not a number", unavailable(r)["cpus.capacity"])
            assertEquals("cpu6: not readable (FileNotFoundException)", unavailable(r)["cpus.related_cpus"])
            val cpus = Json.arr(r.static["cpus"], "c").map { obj(it) }
            assertNull(cpus[3]["max_khz"]); assertNull(cpus[5]["capacity"]); assertNull(cpus[6]["related_cpus"])
            assertEquals(1416000L, cpus[2]["max_khz"]) // the others are read
            descriptor(r) // still a valid input
        }
        // a read that throws
        run {
            val p = D5Like(); p.files.throwFor = "/sys/devices/system/cpu/cpu1/cpu_capacity"
            val r = p.reader().read()
            assertEquals("cpu1: the read failed (IllegalStateException)", unavailable(r)["cpus.capacity"])
        }
        // meminfo fails: ActivityManager is used and named
        run {
            val p = D5Like(); p.files.files.remove("/proc/meminfo")
            val r = p.reader().read()
            assertEquals(3817152L, at(r, "memory", "memtotal_kb")) // 3817152 * 1024 bytes / 1024
            assertTrue((obj(r.static["sources"])["memory"] as String).contains("ActivityManager.MemoryInfo.totalMem"))
            assertEquals(2300000L, r.memAvailableKb)
            assertFalse(unavailable(r).containsKey("memory.memtotal_kb"))
        }
        // both fail
        run {
            val p = D5Like(); p.files.files.remove("/proc/meminfo"); p.memory.result = { Reading.Unavailable("no ActivityManager") }
            val r = p.reader().read()
            assertNull(at(r, "memory", "memtotal_kb"))
            assertEquals("/proc/meminfo: not readable (FileNotFoundException); ActivityManager: no ActivityManager", unavailable(r)["memory.memtotal_kb"])
            assertNull(r.memAvailableKb)
        }
        // meminfo without a MemTotal line
        run {
            val p = D5Like(); p.files.files["/proc/meminfo"] = "MemFree: 1 kB\n"
            val r = p.reader().read()
            assertEquals(3817152L, at(r, "memory", "memtotal_kb")); assertTrue((obj(r.static["sources"])["memory"] as String).contains("no MemTotal line"))
        }
    }

    @Test
    fun givenEachAndroidSourceFails_whenRead_thenTheEnvironmentEntriesSayWhy() {
        val p = D5Like()
        p.thermal.status = { throw IllegalStateException("no service") }
        p.thermal.headroom = { Reading.Unavailable("no PowerManager") }
        p.battery.result = { Reading.Unavailable("no battery broadcast") }
        p.display.result = { throw RuntimeException("display") }
        p.screen.result = { Reading.Unavailable("no PowerManager") }
        val r = p.reader().read()
        val u = unavailable(r)
        assertEquals("the read failed (IllegalStateException)", u["environment.thermal_status"])
        assertEquals("no PowerManager", u["environment.thermal_headroom"])
        assertEquals("no battery broadcast", u["environment.battery_pct"])
        assertEquals("no battery broadcast", u["environment.charging"])
        assertEquals("the read failed (RuntimeException)", u["environment.display_refresh_hz"])
        assertEquals("the read failed (RuntimeException)", u["environment.display_px"])
        assertEquals("no PowerManager", u["environment.screen_on"])
        val env = obj(r.static["environment"])
        assertEquals(listOf("battery_pct", "charging", "display_px", "display_refresh_hz", "screen_on", "thermal_headroom", "thermal_status"), env.filter { it.value == null }.keys.sorted())
        assertEquals("/top-app", env["foreground_cpuset"])
        assertNull(r.conditions.batteryTempC)
        descriptor(r)
    }

    @Test
    fun givenPartialAndroidAnswers_whenRead_thenOnlyTheMissingFieldsAreUnavailable() {
        val p = D5Like()
        p.battery.result = { Reading.Value(BatteryFacts(null, null, null)) }
        p.display.result = { Reading.Value(DisplayFacts(null, 1080, null)) }
        p.thermal.headroom = { Reading.Value(0.4) }
        val r = p.reader().read()
        val u = unavailable(r)
        assertEquals("the battery broadcast has no level", u["environment.battery_pct"])
        assertEquals("the battery broadcast has no charging state", u["environment.charging"])
        assertEquals("the display gave no refresh rate", u["environment.display_refresh_hz"])
        assertEquals("the display gave no resolution", u["environment.display_px"])
        assertFalse(u.containsKey("environment.thermal_headroom"))
        assertEquals(0.4, obj(r.static["environment"])["thermal_headroom"])
        assertEquals("the battery broadcast has no temperature", r.conditions.unavailable["environment.battery_temp_c"])
    }

    @Test
    fun givenTheStaticProbeIsUnavailable_whenRead_thenItsValuesAreUnavailableAndTheCpuListComesFromSysfs() {
        val results = listOf<EngineResult>(
            EngineResult.ExecBlocked("IOException: error=13"), EngineResult.ProgramMissing("libpart1_membw.so was not found"), EngineResult.NotStarted("x"), EngineResult.TimeoutKilled, EngineResult.NoJson(139, ""), EngineResult.Failed(null, 1),
            EngineResult.Refused(mapOf("reason" to "x"), 97), EngineResult.EngineMismatch("part1-engine/0.2.3"),
        )
        for (res in results) {
            val p = D5Like()
            p.engine.static = { res }
            p.files.files["/sys/devices/system/cpu/possible"] = "0-7\n"
            val r = p.reader().read()
            val u = unavailable(r)
            for (k in listOf("identity.platform", "features.hwcap", "features.hwcap2", "features.page_size", "environment.foreground_cpuset")) {
                assertTrue("$res $k", (u[k] as String).isNotEmpty())
            }
            assertNull(at(r, "features", "hwcap"))
            assertNull(at(r, "identity", "platform"))
            assertEquals(8, Json.arr(r.static["cpus"], "c").size) // from /sys/devices/system/cpu/possible
            assertEquals("exdroid", at(r, "identity", "board")) // Build fields are still read
            assertTrue(p.files.reads.contains("/sys/devices/system/cpu/possible"))
            descriptor(r)
        }
        // the reasons name the kind of failure
        val p = D5Like(); p.engine.static = { EngineResult.ExecBlocked("x") }
        assertEquals("ro.board.platform comes from the static probe: the static probe could not be started (exec_blocked)", unavailable(p.reader().read())["identity.platform"])
        val q = D5Like(); q.engine.static = { EngineResult.EngineMismatch("part1-engine/0.2.3") }
        assertTrue((unavailable(q.reader().read())["features.hwcap"] as String).contains("found part1-engine/0.2.3"))
    }

    @Test
    fun givenNeitherTheProbeNorSysfsGivesTheCpuList_whenRead_thenThereAreNoCpusAndTheReasonSaysWhy() {
        val p = D5Like(); p.engine.static = { EngineResult.NoJson(1, "") }
        val r = p.reader().read()
        assertEquals(emptyList<Any?>(), r.static["cpus"])
        assertTrue((unavailable(r)["cpus"] as String).contains("/sys/devices/system/cpu/possible: not readable"))
        val q = D5Like(); q.engine.static = { EngineResult.Ok(Json.parseObject("""{"format":"part1-staticprobe-v1","status":"ok","cpus_possible":"zero to seven"}""")) }
        val r2 = q.reader().read()
        assertTrue((unavailable(r2)["cpus"] as String).contains("cannot be read as a CPU list"))
    }

    @Test
    fun givenTheGpuProbeIsUnavailableOrHasNoVulkan_whenRead_thenVulkanIsUnknownOrAbsent() {
        val p = D5Like(); p.engine.gpu = { EngineResult.TimeoutKilled }
        val r = p.reader().read()
        assertFalse(r.static.containsKey("vulkan"))
        assertEquals("the GPU probe was killed at its time limit", unavailable(r)["vulkan"])
        assertEquals("unk", obj(obj(descriptor(r)["capabilities"])["vulkan"]).let { obj(it["present"])["origin"] })
        val q = D5Like()
        q.engine.gpu = { EngineResult.Ok(Json.parseObject("""{"format":"part1-gpuprobe-v1","status":"ok","vulkan":{"available":false,"reason":"no loader"},"opencl_child":[]}""")) }
        val r2 = q.reader().read()
        assertEquals(mapOf("present" to false), obj(r2.static["vulkan"]).toMap())
        descriptor(r2)
        val w = D5Like()
        w.engine.gpu = { EngineResult.Ok(Json.parseObject("""{"format":"part1-gpuprobe-v1","status":"ok","vulkan":{"available":true,"device_name":"X","api_version":"1.1.0","compute_queue":true,"shader_float16":null,"integer_dot_product":null}}""")) }
        val r3 = w.reader().read()
        assertEquals("the GPU probe has no answer for this value (null)", unavailable(r3)["vulkan.shader_float16"])
        assertEquals("the GPU probe has no answer for this value (null)", unavailable(r3)["vulkan.int_dot"])
        assertNull(obj(r3.static["vulkan"])["int_dot"])
        val x = D5Like()
        x.engine.gpu = { EngineResult.Ok(Json.parseObject("""{"format":"part1-gpuprobe-v1","status":"ok","vulkan":{"available":true,"device_name":"X","api_version":"1.1.0","compute_queue":true,"shader_float16":false,"integer_dot_product":false}}""")) }
        assertEquals("no", obj(x.reader().read().static["vulkan"])["shader_float16"])
        val y = D5Like()
        y.engine.gpu = { EngineResult.Ok(Json.parseObject("""{"format":"part1-gpuprobe-v1","status":"ok"}""")) }
        assertEquals("the GPU probe object has no vulkan.available", unavailable(y.reader().read())["vulkan"])
    }

    @Test
    fun givenAProbeObjectOfTheWrongFormat_whenRead_thenItIsNotUsed() {
        val p = D5Like()
        p.engine.static = { EngineResult.Ok(Json.parseObject("""{"format":"part1-gpuprobe-v1","status":"ok"}""")) }
        val r = p.reader().read()
        assertEquals("the static probe returned an object of format part1-gpuprobe-v1", (unavailable(r)["features.hwcap"] as String))
    }

    @Test
    fun givenOpenClFacts_whenRead_thenTheChildListIsAFactAndPublicLibrariesGiveOnlyAHint() {
        val p = D5Like()
        val r = p.reader().read()
        val child = r.facts["opencl_child"] as List<*>
        assertEquals(4, child.size)
        assertEquals("not_loaded", obj(child[0])["load"])
        assertEquals(false, r.facts["public_libraries_names_opencl"]) // a comment line naming opencl does not count
        assertTrue((obj(r.static["sources"])["opencl"] as String).contains("names no OpenCL library"))
        assertEquals("child-process result does not apply inside the app", unavailable(r)["opencl_loadable"])
        // a library line that names OpenCL
        p.files.files["/vendor/etc/public.libraries.txt"] = "libOpenCL.so\n"
        val r2 = p.reader().read()
        assertEquals(true, r2.facts["public_libraries_names_opencl"])
        assertTrue((obj(r2.static["sources"])["opencl"] as String).contains("names an OpenCL library (a static_read hint, not a load result)"))
        assertNull(r2.static["opencl_loadable"]) // the hint never becomes a value
        // absent file: unknown
        p.files.files.remove("/vendor/etc/public.libraries.txt")
        val r3 = p.reader().read()
        assertNull(r3.facts["public_libraries_names_opencl"])
        assertTrue((obj(r3.static["sources"])["opencl"] as String).contains("was not readable (unknown)"))
    }

    @Test
    fun givenUnknownOrEmptyBuildFields_whenRead_thenThePropertyOfTheProbeIsTheFallbackWithItsSource() {
        val p = D5Like()
        val r0 = StaticReader(p.engine, p.files, FakeBuild(board = "unknown", device = " ", hardware = null, socModel = "UNKNOWN", fingerprint = "", sdkInt = 0, supportedAbis = emptyList()), p.memory, ConditionsReader(p.thermal, p.battery, p.display, p.screen)).read()
        assertEquals("exdroid", at(r0, "identity", "board")); assertEquals("C107", at(r0, "identity", "device"))
        assertEquals("sun55iw3p1", at(r0, "identity", "hardware")); assertEquals("A523", at(r0, "identity", "soc_model"))
        assertEquals("engine static probe property ro.product.board", r0.origins["identity.board"])
        assertTrue((obj(r0.static["sources"])["identity"] as String).contains("board, device, hardware, soc_model taken from the probe's property"))
        assertNull(at(r0, "identity", "build_fingerprint")); assertEquals("Build.FINGERPRINT is empty or unknown", unavailable(r0)["identity.build_fingerprint"])
        assertNull(at(r0, "identity", "sdk_int")); assertEquals("Build.VERSION.SDK_INT is not positive", unavailable(r0)["identity.sdk_int"])
        assertNull(at(r0, "identity", "abi_list")); assertEquals("Build.SUPPORTED_ABIS is empty", unavailable(r0)["identity.abi_list"])
        // neither the Build field nor the property: unavailable with both named
        val p2 = D5Like(); p2.engine.static = { EngineResult.NoJson(1, "") }
        val r1 = StaticReader(p2.engine, p2.files, FakeBuild(socModel = null), p2.memory, ConditionsReader(p2.thermal, p2.battery, p2.display, p2.screen)).read()
        assertEquals("Build.SOC_MODEL is empty or unknown and so is the probe's ro.soc.model", unavailable(r1)["identity.soc_model"])
    }

    @Test
    fun givenFeatureBooleansThatDisagreeWithTheWords_whenRead_thenTheSourceSaysSo() {
        val agree = D5Like().reader().read()
        assertTrue((obj(agree.static["sources"])["features"] as String).contains("the probe's feature booleans agree with the words"))
        val p = D5Like()
        p.engine.static = { EngineResult.Ok(Json.parseObject(Recorded.staticProbe024().toString(Charsets.UTF_8).lines().last { it.isNotBlank() }.replace("\"hwcap\":\"0x119fff\"", "\"hwcap\":\"0xffffffffffffffff\""))) }
        val r = p.reader().read()
        assertEquals("0xffffffffffffffff", at(r, "features", "hwcap")) // a 64-bit word is carried as hex
        assertTrue((obj(r.static["sources"])["features"] as String).contains("differ from the words for: sve"))
        val q = D5Like()
        q.engine.static = { EngineResult.Ok(Json.parseObject(Recorded.staticProbe024().toString(Charsets.UTF_8).lines().last { it.isNotBlank() }.replace("\"sve\":false", "\"sve\":true"))) }
        assertTrue((obj(q.reader().read().static["sources"])["features"] as String).contains("differ from the words for: sve"))
    }

    @Test
    fun givenABadHwcapString_whenRead_thenItIsUnavailableNotGuessed() {
        val p = D5Like()
        p.engine.static = { EngineResult.Ok(Json.parseObject(Recorded.staticProbe024().toString(Charsets.UTF_8).lines().last { it.isNotBlank() }.replace("\"0x119fff\"", "\"119fff\""))) }
        val r = p.reader().read()
        assertNull(at(r, "features", "hwcap"))
        assertEquals("the static probe object has no such value", unavailable(r)["features.hwcap"])
    }

    @Test
    fun givenACallOnTheMainThread_whenRead_thenItRefusesBeforeDoingAnyIo() {
        val p = D5Like()
        try {
            p.reader(onMain = true).read()
            fail("expected an IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals(0, p.engine.commands.size)
            assertEquals(0, p.files.reads.size)
        }
    }

    @Test
    fun givenCpuListTexts_whenParsed_thenTheyFollowTheKernelFormat() {
        assertEquals((0..7).toList(), StaticReader.parseCpuList("0-7\n"))
        assertEquals(listOf(0, 1, 2, 6), StaticReader.parseCpuList("0-2,6"))
        assertEquals(listOf(3), StaticReader.parseCpuList("3"))
        assertEquals(listOf(0, 1), StaticReader.parseCpuList("1,0-1"))
        for (bad in listOf("", "x", "3-1", "0-", "-2", "0,,1", "0-5000")) assertNull(bad, StaticReader.parseCpuList(bad))
    }

    @Test
    fun givenAPinProbe_whenFoldedIntoTheT0Object_thenMidrPartAndImplementerComeFromTheProbeAndTheDescriptorNamesIt() {
        val r = D5Like().reader().read()
        val probe = PinProbe.parse(Recorded.lastLine("d5-pinprobe-v0.2.3.out"))
        val folded = StaticReader.foldPinProbe(r.static, probe)
        val cpus = Json.arr(folded["cpus"], "c").map { obj(it) }
        assertEquals("0x412fd050", cpus[0]["midr"]); assertEquals("0x41", cpus[0]["implementer"]); assertEquals("0xd05", cpus[0]["part"])
        assertEquals("sysfs", cpus[7]["midr_source"])
        val u = obj(folded["unavailable"])
        assertFalse(u.containsKey("cpus.midr")); assertFalse(u.containsKey("cpus.part"))
        assertTrue(u.containsKey("opencl_loadable")) // the others stay
        assertEquals("measured_app", folded["pin_origin"])
        val d = DescriptorGenerator.generateTree(folded, emptyList(), null, null, 0.05, emptyList())
        val core0 = Json.arr(d["cores"], "cores").map { obj(it) }[0]
        assertEquals("pin probe (MIDR read from sysfs)", obj(core0["midr"])["source"])
        assertEquals("measured_app", obj(core0["pin"])["origin"])
        assertEquals("0xd05", obj(core0["part"])["value"])
        // an input that is not changed
        assertNull(Json.arr(r.static["cpus"], "c").map { obj(it) }[0]["midr"])
        // a probe that lacks a CPU leaves the unavailable reason for the values some CPU still lacks
        val partial = PinProbe.parse("""{"format":"part1-pinprobe-v1","engine_version":"part1-engine/0.2.4","gate_basis":"cpuset_top_app","foreground_cpuset":"/top-app","status":"ok","exit_code":0,"duration_ms":1.0,"cpus":[{"index":0,"result":"ok","attempts":1,"time_to_pin_ms":1.0,"midr":"0x412fd050","midr_source":"sysfs"}]}""")
        val f2 = StaticReader.foldPinProbe(r.static, partial)
        assertTrue(obj(f2["unavailable"]).containsKey("cpus.midr"))
        assertEquals("0xd05", obj(Json.arr(f2["cpus"], "c")[0])["part"])
        assertNull(obj(Json.arr(f2["cpus"], "c")[1])["part"])
    }

    @Test
    fun givenTheMidrParser_whenGivenText_thenOnlyHexIsAccepted() {
        assertEquals(0x412fd050L, StaticReader.parseMidr("0x412fd050"))
        assertEquals(0x412fd050L, StaticReader.parseMidr("0x00000000412FD050"))
        assertNull(StaticReader.parseMidr("412fd050")); assertNull(StaticReader.parseMidr("0xzz")); assertNull(StaticReader.parseMidr("0x"))
    }
}
