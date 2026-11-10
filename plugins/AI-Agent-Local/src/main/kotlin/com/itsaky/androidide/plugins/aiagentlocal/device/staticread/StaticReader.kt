package com.itsaky.androidide.plugins.aiagentlocal.device.staticread

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.descriptor.PinProbe
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineCommands
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineExecutor
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineResult
import java.math.BigInteger

/** The outcome of one T0 read. [static] is a `part1-static-v1` object (plain tree). */
class StaticReadResult(
    val static: Map<String, Any?>,
    val conditions: ConditionsSnapshot,
    /** `MemAvailable` of `/proc/meminfo` in kB, for the memory guard of the next job; null when not read. */
    val memAvailableKb: Long?,
    /** Where each value of the static object came from, by `section.field`. */
    val origins: Map<String, String>,
    /** Facts that have no slot in `part1-static-v1`: the engine's `opencl_child` list, the `public.libraries.txt` hint. */
    val facts: Map<String, Any?>,
    val elapsedMs: Long,
) {
    /** Never prints the static object: it holds `Build.FINGERPRINT`. */
    override fun toString() = "StaticReadResult(${elapsedMs} ms, ${(static["cpus"] as? List<*>)?.size ?: 0} CPUs)"
}

/**
 * The tier T0 static read (plan 3.3): a `part1-static-v1` object from the engine's static and GPU probes, plain file reads and
 * Android APIs behind small interfaces. Every value records where it came from ([StaticReadResult.origins], and
 * `sources` in the object); a read that fails becomes an `unavailable` entry with the reason, never a guess and never an
 * exception. It reads no serial number, Android ID, IMEI, account, IP or MAC, no path outside the plugin and the
 * fixed system files named here, no project content, model name or prompt. `Build.FINGERPRINT` goes into the object and
 * nowhere else; this class logs nothing.
 *
 * `Build.SOC_MANUFACTURER` is not read: the static input has no slot for it and the descriptor does not use it, so the read was
 * dropped (P1-04A1F). The facts without a slot (`opencl_child` as the GPU probe gave it, the `public.libraries.txt` hint) are
 * in [StaticReadResult.facts]; the store keeps them in `probe-facts.json`.
 *
 * **Time (T0 budget of 2 s).** Measured on the two phones (P1-03P app runs): static probe 24-83 ms, GPU probe 90-297 ms; the
 * worst pair is 83 + 297 = 380 ms, which leaves about 1.6 s for about 30 small sysfs and procfs reads and six Android API
 * calls. The probes' `--max-seconds` (2 and 5) are limits, not durations, and do not change those times. Worst case, with a
 * hanging probe, the hard kill is `--max-seconds` + 5 s: the static probe is killed at 7 s and the GPU probe at 10 s (it was
 * 8 s with `--max-seconds 3`), so a hung probe takes the T0 read far past 2 s. That is by design.
 *
 * Blocking (two short engine processes and about 30 small file reads): call it from a background thread.
 */
class StaticReader(
    private val engine: EngineExecutor,
    private val files: SysFiles,
    private val build: BuildSource,
    private val memory: MemorySource,
    private val conditions: ConditionsReader,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onMainThread: () -> Boolean = { false },
) {
    companion object {
        const val FORMAT = "part1-static-v1"
        const val CPU_ROOT = "/sys/devices/system/cpu"
        const val MEMINFO = "/proc/meminfo"
        const val PUBLIC_LIBRARIES = "/vendor/etc/public.libraries.txt"
        const val OPENCL_REASON = "child-process result does not apply inside the app"
        const val MIDR_REASON = "MIDR is read by the pin probe, which the T0 read does not run"
        private val HEX_WORD = Regex("0x[0-9a-f]{1,16}")

        /** Parses a kernel CPU list such as `0-7` or `0-3,6`; null when it is not one. */
        fun parseCpuList(text: String): List<Int>? {
            val out = ArrayList<Int>()
            for (part in text.trim().split(',')) {
                val m = Regex("(\\d{1,4})(?:-(\\d{1,4}))?").matchEntire(part.trim()) ?: return null
                val a = m.groupValues[1].toInt()
                val b = m.groupValues[2].ifEmpty { m.groupValues[1] }.toInt()
                if (b < a || b - a > 1023) return null
                for (i in a..b) if (i !in out) out.add(i)
            }
            return out.sorted().takeIf { it.isNotEmpty() }
        }

        /**
         * Folds a pin probe into a static input ([PinProbe.fold]) and then fills `implementer` and `part` of each CPU from its MIDR
         * when it has none (`implementer` = bits 24..31, `part` = bits 4..15, as hex strings like `0x41`, `0xd05`).
         * Stale `unavailable` reasons for values every CPU now has are removed.
         */
        internal fun foldPinProbe(static: Map<String, Any?>, probe: PinProbe): Map<String, Any?> {
            val folded = LinkedHashMap(PinProbe.fold(static, probe))
            val cpus = (folded["cpus"] as? List<*>)?.map { c ->
                @Suppress("UNCHECKED_CAST")
                val m = LinkedHashMap(c as Map<String, Any?>)
                val midr = (m["midr"] as? String)?.let { parseMidr(it) }
                if (midr != null) {
                    if (m["implementer"] == null) m["implementer"] = "0x" + ((midr shr 24) and 0xff).toString(16)
                    if (m["part"] == null) m["part"] = "0x" + ((midr shr 4) and 0xfff).toString(16)
                }
                m
            } ?: return folded
            folded["cpus"] = cpus
            @Suppress("UNCHECKED_CAST")
            val un = (folded["unavailable"] as? Map<String, Any?>)?.let { LinkedHashMap(it) }
            if (un != null) {
                for ((field, key) in listOf("midr" to "cpus.midr", "part" to "cpus.part", "implementer" to "cpus.implementer")) {
                    @Suppress("UNCHECKED_CAST")
                    if (cpus.all { (it as Map<String, Any?>)[field] != null }) un.remove(key)
                }
                folded["unavailable"] = un
            }
            return folded
        }

        internal fun parseMidr(text: String): Long? =
            if (Regex("0x[0-9a-fA-F]{1,16}").matches(text)) text.substring(2).toLongOrNull(16) else null
    }

    /**
     * Runs the whole T0 read. [pluginVersion] goes into the object when given. Throws [IllegalStateException] only when called
     * on the main thread (a programming error); no read failure throws.
     */
    fun read(pluginVersion: String? = null): StaticReadResult {
        check(!onMainThread()) { "the T0 static read does blocking I/O and must not run on the main thread" }
        val t0 = nowMs()
        val un = LinkedHashMap<String, Any?>()
        val sources = LinkedHashMap<String, Any?>()
        val origins = LinkedHashMap<String, String>()
        val facts = LinkedHashMap<String, Any?>()

        // ---- the engine's two probes
        val sp = probeObject(engine.run(EngineCommands.staticProbe()), "part1-staticprobe-v1", "the static probe")
        val gp = probeObject(engine.run(EngineCommands.gpuProbe()), "part1-gpuprobe-v1", "the GPU probe")
        val spObj = (sp as? Probe.Object)?.json
        val gpObj = (gp as? Probe.Object)?.json
        val spWhy = (sp as? Probe.Missing)?.reason
        val gpWhy = (gp as? Probe.Missing)?.reason
        @Suppress("UNCHECKED_CAST")
        val props = spObj?.get("properties") as? Map<String, Any?>

        // ---- identity
        val identity = LinkedHashMap<String, Any?>()
        val fallbacks = ArrayList<String>()
        fun ident(key: String, buildValue: String?, buildName: String, propName: String?) {
            val b = clean(buildValue)
            val p = propName?.let { clean(props?.get(it) as? String) }
            when {
                b != null -> { identity[key] = b; origins["identity.$key"] = buildName }
                p != null -> {
                    identity[key] = p; origins["identity.$key"] = "engine static probe property $propName"
                    fallbacks.add(key)
                }
                else -> {
                    identity[key] = null
                    un["identity.$key"] = "$buildName is empty or unknown" + if (propName != null) " and so is the probe's $propName" else ""
                }
            }
        }
        val platform = clean(props?.get("ro.board.platform") as? String)
        identity["platform"] = platform
        if (platform != null) origins["identity.platform"] = "engine static probe property ro.board.platform"
        else un["identity.platform"] = spWhy?.let { "ro.board.platform comes from the static probe: $it" } ?: "ro.board.platform is empty"
        ident("board", build.board, "Build.BOARD", "ro.product.board")
        ident("device", build.device, "Build.DEVICE", "ro.product.device")
        ident("hardware", build.hardware, "Build.HARDWARE", "ro.hardware")
        ident("soc_model", build.socModel, "Build.SOC_MODEL", "ro.soc.model")
        identity["sdk_int"] = build.sdkInt.takeIf { it > 0 }
        if (identity["sdk_int"] != null) origins["identity.sdk_int"] = "Build.VERSION.SDK_INT" else un["identity.sdk_int"] = "Build.VERSION.SDK_INT is not positive"
        identity["abi_list"] = build.supportedAbis.takeIf { it.isNotEmpty() }
        if (identity["abi_list"] != null) origins["identity.abi_list"] = "Build.SUPPORTED_ABIS" else un["identity.abi_list"] = "Build.SUPPORTED_ABIS is empty"
        val fp = clean(build.fingerprint)
        identity["build_fingerprint"] = fp
        if (fp != null) origins["identity.build_fingerprint"] = "Build.FINGERPRINT" else un["identity.build_fingerprint"] = "Build.FINGERPRINT is empty or unknown"
        sources["identity"] = "Build fields (BOARD, DEVICE, HARDWARE, SOC_MODEL, FINGERPRINT, VERSION.SDK_INT, SUPPORTED_ABIS); platform from the engine static probe (ro.board.platform)" +
            if (fallbacks.isEmpty()) "" else "; ${fallbacks.joinToString(", ")} taken from the probe's property because the Build field was empty"

        // ---- CPUs
        val possibleText: String? = (spObj?.get("cpus_possible") as? String) ?: run {
            when (val r = readFile("$CPU_ROOT/possible")) {
                is Reading.Value -> r.value
                is Reading.Unavailable -> { un["cpus"] = "the CPU list is unavailable: ${spWhy ?: "the static probe has no cpus_possible"}; $CPU_ROOT/possible: ${r.reason}"; null }
            }
        }
        val indices = possibleText?.let { parseCpuList(it) }
        if (possibleText != null && indices == null) un["cpus"] = "the CPU list '${possibleText.trim().take(40)}' cannot be read as a CPU list"
        val cpus = ArrayList<Map<String, Any?>>()
        val freqWhy = ArrayList<String>(); val relWhy = ArrayList<String>(); val capWhy = ArrayList<String>()
        for (i in indices.orEmpty()) {
            val base = "$CPU_ROOT/cpu$i"
            val maxKhz = readLong("$base/cpufreq/cpuinfo_max_freq") { freqWhy.add("cpu$i: $it") }
            val related = readRelated("$base/cpufreq/related_cpus") { relWhy.add("cpu$i: $it") }
            val cap = readLong("$base/cpu_capacity") { capWhy.add("cpu$i: $it") }
            cpus.add(linkedMapOf("index" to i.toLong(), "related_cpus" to related, "max_khz" to maxKhz, "capacity" to cap, "midr" to null, "implementer" to null, "part" to null))
        }
        if (freqWhy.isNotEmpty()) un["cpus.max_khz"] = freqWhy.joinToString("; ")
        if (relWhy.isNotEmpty()) un["cpus.related_cpus"] = relWhy.joinToString("; ")
        if (capWhy.isNotEmpty()) un["cpus.capacity"] = capWhy.joinToString("; ")
        if (cpus.isNotEmpty()) {
            un["cpus.midr"] = MIDR_REASON
            un["cpus.part"] = MIDR_REASON
            origins["cpus"] = "sysfs cpufreq (cpuinfo_max_freq, related_cpus) and cpu_capacity per CPU; CPU list from " + if (spObj?.get("cpus_possible") is String) "the engine static probe" else "$CPU_ROOT/possible"
        }
        sources["cpus"] = "T0 static read: sysfs $CPU_ROOT/cpu<N>/cpufreq/cpuinfo_max_freq, cpufreq/related_cpus and cpu_capacity; MIDR, implementer and part come from the pin probe"

        // ---- features
        val hwcap = hexWord(spObj?.get("hwcap"))
        val hwcap2 = hexWord(spObj?.get("hwcap2"))
        val pageSize = (spObj?.get("page_size") as? Long)?.takeIf { it > 0 }
        val why = spWhy ?: "the static probe object has no such value"
        if (hwcap == null) un["features.hwcap"] = why else origins["features.hwcap"] = "engine static probe (getauxval AT_HWCAP)"
        if (hwcap2 == null) un["features.hwcap2"] = why else origins["features.hwcap2"] = "engine static probe (getauxval AT_HWCAP2)"
        if (pageSize == null) un["features.page_size"] = why else origins["features.page_size"] = "engine static probe (sysconf _SC_PAGESIZE)"
        un["features.dcache_line_bytes"] = "the data cache line size (CTR_EL0) is not part of the static probe"
        var featuresSource = "engine static probe: HWCAP and HWCAP2 words (getauxval)"
        if (hwcap != null && hwcap2 != null) {
            val disagree = featureDisagreements(spObj?.get("features"), hwcap, hwcap2)
            featuresSource += if (disagree.isEmpty()) "; the probe's feature booleans agree with the words" else "; the probe's feature booleans differ from the words for: ${disagree.joinToString(", ")}"
        }
        sources["features"] = featuresSource

        // ---- memory
        var memAvail: Long? = null
        var memTotal: Long? = null
        val meminfo = readFile(MEMINFO)
        if (meminfo is Reading.Value) {
            memTotal = meminfoKb(meminfo.value, "MemTotal")
            memAvail = meminfoKb(meminfo.value, "MemAvailable")
        }
        var memSource = "$MEMINFO (MemTotal)"
        if (memTotal != null) {
            origins["memory.memtotal_kb"] = MEMINFO
        } else {
            val whyMem = (meminfo as? Reading.Unavailable)?.reason ?: "no MemTotal line"
            val am = readGuarded { memory.read() }
            if (am is Reading.Value) {
                memTotal = am.value.totalBytes / 1024
                memSource = "ActivityManager.MemoryInfo.totalMem (the $MEMINFO read failed: $whyMem)"
                origins["memory.memtotal_kb"] = "ActivityManager.MemoryInfo.totalMem"
                if (memAvail == null) memAvail = am.value.availBytes / 1024
            } else {
                un["memory.memtotal_kb"] = "$MEMINFO: $whyMem; ActivityManager: ${(am as Reading.Unavailable).reason}"
            }
        }
        sources["memory"] = memSource

        // ---- Vulkan and OpenCL
        val vulkan: Map<String, Any?>?
        if (gpObj != null) {
            @Suppress("UNCHECKED_CAST")
            val vk = gpObj["vulkan"] as? Map<String, Any?>
            if (vk == null || vk["available"] !is Boolean) {
                vulkan = null
                un["vulkan"] = "the GPU probe object has no vulkan.available"
            } else if (vk["available"] == false) {
                vulkan = linkedMapOf("present" to false)
                origins["vulkan.present"] = "engine GPU probe"
            } else {
                val f16 = when (vk["shader_float16"]) { true -> "yes"; false -> "no"; else -> null }
                vulkan = linkedMapOf(
                    "present" to true, "device_name" to (vk["device_name"] as? String), "api_version" to (vk["api_version"] as? String),
                    "compute_queue" to (vk["compute_queue"] as? Boolean), "shader_float16" to f16, "int_dot" to (vk["integer_dot_product"] as? Boolean),
                )
                for (k in listOf("device_name", "api_version", "compute_queue", "shader_float16", "int_dot")) {
                    if (vulkan[k] == null) un["vulkan.$k"] = "the GPU probe has no answer for this value (null)" else origins["vulkan.$k"] = "engine GPU probe"
                }
            }
            // kept as the probe gave it (name, loaded, outcome, error, signal; unnamed candidates too), not the derived view
            facts["opencl_child"] = (gpObj["opencl_child"] as? List<*>).orEmpty().map { it }
        } else {
            vulkan = null
            un["vulkan"] = gpWhy ?: "the GPU probe gave no object"
        }
        sources["vulkan"] = "engine GPU probe (libpart1_gpubw.so --probe): Vulkan instance and physical-device queries only"
        val publicLibs = readFile(PUBLIC_LIBRARIES)
        val hint: Boolean? = (publicLibs as? Reading.Value)?.let { r -> r.value.lines().any { it.trim().lowercase().let { l -> !l.startsWith("#") && l.contains("opencl") } } }
        facts["public_libraries_names_opencl"] = hint
        un["opencl_loadable"] = OPENCL_REASON
        sources["opencl"] = "engine GPU probe opencl_child (a fact for a child process only); $PUBLIC_LIBRARIES " + when (hint) {
            true -> "names an OpenCL library (a static_read hint, not a load result)"
            false -> "names no OpenCL library"
            null -> "was not readable (unknown)"
        }
        un["nnapi_devices"] = "NNAPI devices are not read by the T0 static read"

        // ---- environment
        val cond = conditions.read()
        val env = linkedMapOf<String, Any?>(
            "thermal_status" to cond.thermalStatus, "thermal_headroom" to cond.thermalHeadroom, "battery_pct" to cond.batteryPct,
            "charging" to cond.charging, "display_refresh_hz" to cond.displayRefreshHz, "display_px" to cond.displayPx,
            "screen_on" to cond.screenOn, "foreground_cpuset" to (spObj?.get("foreground_cpuset") as? String),
        )
        for ((k, v) in env) {
            if (v == null) {
                un["environment.$k"] = cond.unavailable["environment.$k"] ?: if (k == "foreground_cpuset") (spWhy ?: "the static probe could not read /proc/self/cpuset") else "not read"
            } else {
                origins["environment.$k"] = if (k == "foreground_cpuset") "engine static probe (own /proc/self/cpuset)" else "Android system API"
            }
        }
        sources["environment"] = "PowerManager (thermal status and headroom, screen), battery broadcast, default display; foreground cpuset from the engine static probe"

        val static = LinkedHashMap<String, Any?>()
        static["format"] = FORMAT
        static["sources"] = sources
        static["identity"] = identity
        static["cpus"] = cpus
        static["features"] = linkedMapOf("hwcap" to hwcap, "hwcap2" to hwcap2, "page_size" to pageSize, "dcache_line_bytes" to null)
        static["memory"] = linkedMapOf("memtotal_kb" to memTotal)
        if (vulkan != null) static["vulkan"] = vulkan
        static["opencl_loadable"] = null
        static["nnapi_devices"] = null
        static["environment"] = env
        if (pluginVersion != null) static["plugin_version"] = pluginVersion
        val elapsed = nowMs() - t0
        static["duration_s"] = elapsed / 1000.0
        sources["run"] = "T0 static read wall time (two engine probes and the file and API reads)"
        static["unavailable"] = un
        // one plain tree: Int becomes Long, keys are visited in order, a value no JSON can hold is refused here
        val tree = Json.obj(Json.fromOrg(Json.toOrg(static)), "static")
        return StaticReadResult(tree, cond, memAvail, origins, facts, elapsed)
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private sealed class Probe {
        class Object(val json: Map<String, Any?>) : Probe()
        class Missing(val reason: String) : Probe()
    }

    private fun probeObject(r: EngineResult, format: String, what: String): Probe = when (r) {
        is EngineResult.Ok ->
            if (r.json["format"] == format) Probe.Object(r.json) else Probe.Missing("$what returned an object of format ${r.json["format"]}")
        is EngineResult.Refused -> Probe.Missing("$what was refused (exit ${r.exit})")
        is EngineResult.Failed -> Probe.Missing("$what failed (exit ${r.exit})")
        is EngineResult.NoJson -> Probe.Missing("$what wrote no JSON object (exit ${r.exit})")
        is EngineResult.ExecBlocked -> Probe.Missing("$what could not be started (exec_blocked)")
        is EngineResult.ProgramMissing -> Probe.Missing("$what could not be started (the program file was not found)")
        is EngineResult.NotStarted -> Probe.Missing("$what was not started (${r.message})")
        is EngineResult.TimeoutKilled -> Probe.Missing("$what was killed at its time limit")
        is EngineResult.EngineMismatch -> Probe.Missing("the engine version is not the expected one (found ${r.found ?: "none"})")
    }

    private fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }

    private fun hexWord(x: Any?): String? = (x as? String)?.takeIf { HEX_WORD.matches(it) }

    private fun readFile(path: String): Reading<String> = readGuarded { files.readText(path) }

    private fun <T> readGuarded(call: () -> Reading<T>): Reading<T> = try {
        call()
    } catch (e: Exception) {
        Reading.Unavailable("the read failed (${e.javaClass.simpleName})")
    }

    private inline fun readLong(path: String, onFail: (String) -> Unit): Long? = when (val r = readFile(path)) {
        is Reading.Value -> r.value.trim().takeIf { Regex("\\d{1,15}").matches(it) }?.toLong() ?: run { onFail("not a number"); null }
        is Reading.Unavailable -> { onFail(r.reason); null }
    }

    private inline fun readRelated(path: String, onFail: (String) -> Unit): List<Long>? = when (val r = readFile(path)) {
        is Reading.Value -> {
            val parts = r.value.trim().split(Regex("[ ,\\t\\n]+")).filter { it.isNotEmpty() }
            if (parts.isNotEmpty() && parts.all { Regex("\\d{1,4}").matches(it) }) parts.map { it.toLong() }.sorted() else { onFail("not a CPU list"); null }
        }
        is Reading.Unavailable -> { onFail(r.reason); null }
    }

    private fun meminfoKb(text: String, key: String): Long? =
        text.lines().firstNotNullOfOrNull { line ->
            Regex("$key:\\s+(\\d{1,15})\\s*kB").matchEntire(line.trim())?.groupValues?.get(1)?.toLong()
        }

    /** Names of the probe's feature booleans that differ from the bits of the words (arm64 `hwcap.h`). */
    private fun featureDisagreements(features: Any?, hwcap: String, hwcap2: String): List<String> {
        val f = features as? Map<*, *> ?: return listOf("features missing")
        val w1 = BigInteger(hwcap.substring(2), 16)
        val w2 = BigInteger(hwcap2.substring(2), 16)
        val expect = mapOf(
            "asimd" to w1.testBit(1), "asimdhp" to w1.testBit(10), "asimddp" to w1.testBit(20), "sve" to w1.testBit(22),
            "sve2" to w2.testBit(1), "i8mm" to w2.testBit(13), "bf16" to w2.testBit(14), "sme" to w2.testBit(23),
        )
        return expect.filter { (k, v) -> f[k] != v }.keys.sorted()
    }
}
