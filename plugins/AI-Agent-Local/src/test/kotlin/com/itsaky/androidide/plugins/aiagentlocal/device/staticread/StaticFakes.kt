package com.itsaky.androidide.plugins.aiagentlocal.device.staticread

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineCommand
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineExecutor
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineResult
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.Recorded
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.CommandKind
import java.io.File

class FakeFiles(val files: MutableMap<String, String>, val failing: MutableMap<String, String> = HashMap(), var throwFor: String? = null) : SysFiles {
    val reads = ArrayList<String>()
    override fun readText(path: String): Reading<String> {
        reads.add(path)
        if (path == throwFor) throw IllegalStateException("boom")
        failing[path]?.let { return Reading.Unavailable(it) }
        return files[path]?.let { Reading.Value(it) } ?: Reading.Unavailable("not readable (FileNotFoundException)")
    }
}

class FakeBuild(
    override val board: String? = "exdroid",
    override val device: String? = "C107",
    override val hardware: String? = "sun55iw3p1",
    override val socModel: String? = "A523",
    override val socManufacturer: String? = "Allwinner",
    override val fingerprint: String? = "synthetic/d5-like:14/SYN.1/1:user/test-keys",
    override val sdkInt: Int = 34,
    override val supportedAbis: List<String> = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
) : BuildSource

class FakeMemory(var result: () -> Reading<MemoryFacts> = { Reading.Value(MemoryFacts(3_817_152L * 1024, 2_300_000L * 1024, false, 200_000L * 1024)) }) : MemorySource {
    override fun read() = result()
}

class FakeThermal(var status: () -> Reading<Int> = { Reading.Value(0) }, var headroom: () -> Reading<Double> = { Reading.Value(Double.NaN) }) : ThermalSource {
    override fun status() = status.invoke()
    override fun headroom() = headroom.invoke()
}

class FakeBattery(var result: () -> Reading<BatteryFacts> = { Reading.Value(BatteryFacts(100, true, 31.5)) }) : BatterySource {
    override fun read() = result()
}

class FakeDisplay(var result: () -> Reading<DisplayFacts> = { Reading.Value(DisplayFacts(60.0, 1280, 800)) }) : DisplaySource {
    override fun read() = result()
}

class FakeScreen(var result: () -> Reading<Boolean> = { Reading.Value(true) }) : ScreenSource {
    override fun isScreenOn() = result()
}

class FakeEngine(var static: () -> EngineResult, var gpu: () -> EngineResult) : EngineExecutor {
    val commands = ArrayList<EngineCommand>()
    override fun run(command: EngineCommand): EngineResult {
        commands.add(command)
        return when (command.kind) {
            CommandKind.STATIC_PROBE -> static()
            CommandKind.GPU_PROBE -> gpu()
            else -> throw AssertionError("T0 must not run $command")
        }
    }
}

/** A D5-like phone: eight CPUs in two clusters, the recorded probes, plausible system values. All fingerprints are synthetic. */
class D5Like {
    val files = FakeFiles(defaultFiles())
    val build = FakeBuild()
    val memory = FakeMemory()
    val thermal = FakeThermal()
    val battery = FakeBattery()
    val display = FakeDisplay()
    val screen = FakeScreen()
    val engine = FakeEngine(
        static = { EngineResult.Ok(Json.parseObject(Recorded.staticProbe024().toString(Charsets.UTF_8).lines().last { it.isNotBlank() })) },
        gpu = { EngineResult.Ok(Json.parseObject(Recorded.gpuProbe024().toString(Charsets.UTF_8).lines().last { it.isNotBlank() })) },
    )
    var clock = 0L
    var tick = 120L

    fun reader(onMain: Boolean = false) = StaticReader(
        engine, files, build, memory, ConditionsReader(thermal, battery, display, screen),
        nowMs = { val t = clock; clock += tick; t }, onMainThread = { onMain },
    )

    companion object {
        fun defaultFiles(): MutableMap<String, String> {
            val m = HashMap<String, String>()
            for (i in 0..7) {
                val little = i < 4
                m["/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq"] = if (little) "1416000\n" else "1800000\n"
                m["/sys/devices/system/cpu/cpu$i/cpufreq/related_cpus"] = if (little) "0 1 2 3\n" else "4 5 6 7\n"
                m["/sys/devices/system/cpu/cpu$i/cpu_capacity"] = if (little) "725\n" else "1024\n"
            }
            m["/proc/meminfo"] = "MemTotal:        3817152 kB\nMemFree:          100000 kB\nMemAvailable:    2300000 kB\nBuffers:            1000 kB\n"
            m["/vendor/etc/public.libraries.txt"] = "# comment mentioning opencl\nlibGLES_mali.so\nlibvulkan.so\n"
            return m
        }
    }
}

/** Writes the objects of a test for the external judge (`generate.py`, `validate.py`) when `-Dpart1.engineOutDir=...` is given. */
object Judge {
    private fun pretty(m: Map<String, Any?>): String = (Json.toOrg(m) as org.json.JSONObject).toString(1)

    fun write(name: String, static: Map<String, Any?>, rows: List<String> = emptyList(), dbrecord: Map<String, Any?>? = null, descriptor: Map<String, Any?>? = null) {
        val base = System.getProperty("part1.engineOutDir") ?: return
        val d = File(base, name).also { it.mkdirs() }
        File(d, "static.json").writeText(pretty(static))
        if (rows.isNotEmpty()) File(d, "rows.jsonl").writeText(rows.joinToString("\n") + "\n")
        dbrecord?.let { File(d, "dbrecord.json").writeText(pretty(it)) }
        descriptor?.let { File(d, "kotlin-descriptor.json").writeText(pretty(it)) }
    }
}
