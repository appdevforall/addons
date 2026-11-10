package com.itsaky.androidide.plugins.aiagentlocal.device.quick

import java.io.File
import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.Part1TestResources
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.CommandKind
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineCommand
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineExecutor
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineResult
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.Recorded
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.ConditionsSnapshot
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.FakeBattery
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.FakeBuild
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.FakeDisplay
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.FakeEngine
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.FakeFiles
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.FakeMemory
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.FakeScreen
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.FakeThermal
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.ConditionsReader
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.StaticReader

/** A clock the tests move by hand; [sleepMs] advances it. [onSleep] lets a test act while the pass waits. */
class FakeClock : Clock {
    var now = 1_000_000L
    var sleeps = ArrayList<Long>()
    var onSleep: (Long) -> Unit = {}
    var interruptOnSleep = false
    override fun nowMs() = now
    override fun sleepMs(ms: Long) {
        if (interruptOnSleep) throw InterruptedException("test")
        sleeps.add(ms)
        now += ms
        onSleep(now)
    }
}

class FakeForeground(var fg: () -> Boolean = { true }) : ForegroundGate {
    var calls = 0
    override fun isForeground(): Boolean { calls++; return fg() }
}

class FakeIde(var busy: () -> Boolean = { false }) : QuietIdeGate {
    var starts = 0L
    override fun isBusy() = busy()
    override fun startsSeen() = starts
}

/** The conditions of the phone, settable per test; [reads] counts the reads. */
class FakeConditions : ConditionsProvider {
    var battery: () -> Int? = { 90 }
    var status: () -> Int? = { 0 }
    var headroom: () -> Double? = { null }
    var reads = 0
    override fun read(): ConditionsSnapshot {
        reads++
        return ConditionsSnapshot(status(), headroom(), battery(), false, 30.0, 60.0, "1280x800", true, emptyMap())
    }
}

class FakeMemoryProbe(var info: () -> MemInfo? = { MemInfo(3_817_152L, 2_300_000L) }) : MemoryProbe {
    override fun read() = info()
}

class FakeScreenKeeper : ScreenKeeper {
    var on = false
    var keepOnCalls = 0
    var releases = 0
    override fun keepOn() { on = true; keepOnCalls++ }
    override fun release() { on = false; releases++ }
}

class FakeUi(var answer: () -> QuickAnswer = { QuickAnswer.RUN_NOW }) : QuickTierUi {
    var asked = 0
    var closed = 0
    val progress = ArrayList<Progress>()
    val waiting = ArrayList<WaitingFor>()
    var cancelHandler: () -> Unit = {}
    override fun ask(): QuickAnswer { asked++; return answer() }
    override fun onCancel(handler: () -> Unit) { cancelHandler = handler }
    override fun showProgress(progress: Progress) { this.progress.add(progress) }
    override fun showWaiting(what: WaitingFor) { waiting.add(what) }
    override fun close() { closed++ }
}

class FakeWatchdog : Watchdog {
    var started = 0
    var closed = 0
    var ms = 0L
    var fire: () -> Unit = {}
    override fun start(ms: Long, onTimeout: () -> Unit): AutoCloseable {
        started++
        this.ms = ms
        fire = onTimeout
        return AutoCloseable { closed++ }
    }
}

/**
 * The engine, scripted per command. [serve] gives the result of a command; [beforeRun] runs when a command starts (a place to move the clock,
 * start a build, lose the foreground or press Cancel). [running] is true while a process would be alive: the tests assert it is false at the end.
 */
class ScriptedEngine(var serve: (EngineCommand) -> EngineResult) : EngineExecutor {
    val commands = ArrayList<EngineCommand>()
    var beforeRun: (EngineCommand) -> Unit = {}
    var running = false
    var cancelCalls = 0
    var resets = 0
    @Volatile var cancelled = false

    override fun run(command: EngineCommand): EngineResult {
        if (cancelled) return EngineResult.Cancelled
        commands.add(command)
        running = true
        try {
            beforeRun(command)
            if (cancelled) return EngineResult.Cancelled
            return serve(command)
        } finally {
            running = false
        }
    }

    override fun cancel() { cancelCalls++; cancelled = true; running = false }
    var onReset: () -> Unit = {}
    override fun resetCancel() { resets++; cancelled = false; onReset() }
}

/** Recorded D5 and D1 engine lines, served with the packaged engine's version and with the tag the app starts. */
object Lines {
    private const val V = "part1-engine/0.2.4"

    fun obj(fixture: String, tagFrom: String? = null, tagTo: String? = null): Map<String, Any?> {
        var t = Recorded.lastLine(fixture).replace("part1-engine/0.2.1", V).replace("part1-engine/0.2.3", V)
        if (tagFrom != null) t = t.replace("\"tag\":\"$tagFrom\"", "\"tag\":\"$tagTo\"")
        return Json.parseObject(t)
    }

    fun ok(fixture: String, tagFrom: String? = null, tagTo: String? = null) = EngineResult.Ok(obj(fixture, tagFrom, tagTo))
}

/** A D1-like phone for the reader: eight CPUs in three clusters (0-3, 4-5, 6-7) and the recorded D1 probes. Synthetic fingerprint. */
class D1Like {
    val files = FakeFiles(defaultFiles())
    val build = FakeBuild(board = "raven", device = "raven", hardware = "raven", socModel = "Tensor", fingerprint = "synthetic/d1-like:14/SYN.1/1:user/test-keys", sdkInt = 34)
    val memory = FakeMemory()
    val engine = FakeEngine(
        static = { Lines.ok("d1-staticprobe-v0.2.3.out") },
        gpu = { Lines.ok("d1-gpuprobe-v0.2.3.out") },
    )

    fun reader() = StaticReader(
        engine, files, build, memory, ConditionsReader(FakeThermal(), FakeBattery(), FakeDisplay(), FakeScreen()),
        nowMs = { 0L },
    )

    companion object {
        fun defaultFiles(): MutableMap<String, String> {
            val m = HashMap<String, String>()
            for (i in 0..7) {
                val rel = when { i < 4 -> "0 1 2 3"; i < 6 -> "4 5"; else -> "6 7" }
                m["/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq"] = when { i < 4 -> "1803000\n"; i < 6 -> "2253000\n"; else -> "2802000\n" }
                m["/sys/devices/system/cpu/cpu$i/cpufreq/related_cpus"] = rel + "\n"
                m["/sys/devices/system/cpu/cpu$i/cpu_capacity"] = when { i < 4 -> "250\n"; i < 6 -> "620\n"; else -> "1024\n" }
            }
            m["/proc/meminfo"] = "MemTotal:       11827500 kB\nMemFree:          100000 kB\nMemAvailable:    7593280 kB\n"
            m["/vendor/etc/public.libraries.txt"] = "libvulkan.so\n"
            return m
        }
    }
}

/** A static input with [clusters] (lists of CPUs) and the CPUs in [unpinnable] failing to pin, built by hand for the planner tests. */
fun staticWith(clusters: List<List<Int>>, unpinnable: Set<Int> = emptySet(), vulkanCompute: Boolean = true, maxKhz: (Int) -> Long = { 1000000L }): Map<String, Any?> {
    val cpus = clusters.flatMap { c ->
        c.map { i ->
            linkedMapOf<String, Any?>(
                "index" to i.toLong(), "related_cpus" to c.map { it.toLong() }, "max_khz" to maxKhz(i), "capacity" to 500L,
                "pin" to linkedMapOf<String, Any?>("result" to if (i in unpinnable) "failed" else "ok", "attempts" to 1L, "time_to_pin_s" to 0.0, "cpuset" to "/top-app"),
            )
        }
    }.sortedBy { it["index"] as Long }
    val m = linkedMapOf<String, Any?>("format" to "part1-static-v1", "cpus" to cpus)
    if (vulkanCompute) m["vulkan"] = linkedMapOf<String, Any?>("present" to true, "compute_queue" to true)
    return m
}

/** Resource helpers kept here so the tests of the quick package share one place. */
internal fun d5Static(): Map<String, Any?> = Part1TestResources.obj("part1/golden/" + Part1TestResources.goldenCaseNames().first { it.endsWith("t0-no-rows-no-record") } + "/static.json")

/** The real file operations, with a log of every file the store replaced (by name), so a test can show that no write happens after a point. */
class RecordingOps : com.itsaky.androidide.plugins.aiagentlocal.device.store.FileOps {
    val replaced = java.util.Collections.synchronizedList(ArrayList<String>())
    override fun writeAndSync(file: File, bytes: ByteArray) = com.itsaky.androidide.plugins.aiagentlocal.device.store.FileOps.Real.writeAndSync(file, bytes)
    override fun moveReplacing(from: File, to: File) { com.itsaky.androidide.plugins.aiagentlocal.device.store.FileOps.Real.moveReplacing(from, to); replaced.add(to.name) }
    override fun delete(file: File) = com.itsaky.androidide.plugins.aiagentlocal.device.store.FileOps.Real.delete(file)
}
