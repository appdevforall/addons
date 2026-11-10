package com.itsaky.androidide.plugins.aiagentlocal.device.quick

import com.itsaky.androidide.plugins.aiagentlocal.device.Part1TestResources
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineCommand
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineResult
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineRunner
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.D5Like
import com.itsaky.androidide.plugins.aiagentlocal.device.store.BindResult
import com.itsaky.androidide.plugins.aiagentlocal.device.store.Part1Store
import java.io.File

/**
 * A pass on fakes: the store is real (a temporary folder), bound and holding the T0 object of a D5-like or D1-like phone; the engine
 * serves the recorded rows of that phone and moves the fake clock by each row's recorded duration; every gate is a fake that passes.
 */
class Rig(root: File, val phone: String = "D5", prepare: Boolean = true) {
    val dir = File(root, "part1")
    val store = Part1Store(dir)
    val clock = FakeClock()
    val fg = FakeForeground()
    val ide = FakeIde()
    val cond = FakeConditions()
    val mem = FakeMemoryProbe()
    val screen = FakeScreenKeeper()
    val ui = FakeUi()
    val wd = FakeWatchdog()
    val logs = ArrayList<String>()
    val engine = ScriptedEngine { serve(it) }
    val index = Part1TestResources.realIndex()
    val fingerprint: String
    var rowDurationOverrideMs: Long? = null
    /** Replaces the served answer of a tag. */
    val overrides = HashMap<String, (EngineCommand) -> EngineResult>()

    init {
        val read = if (phone == "D5") D5Like().reader().read("1.1.0") else D1Like().reader().read("1.1.0")
        fingerprint = (read.static["identity"] as Map<*, *>)["build_fingerprint"] as String
        if (prepare) { // the T0 object is already stored (a controller test lets the controller store it)
            check(store.bindDevice(fingerprint, EngineRunner.EXPECTED_ENGINE_VERSION) is BindResult.Fresh)
            check(store.writeStatic(read.static))
        }
    }

    fun tagOf(c: EngineCommand): String = c.tag ?: when (c.kind) {
        com.itsaky.androidide.plugins.aiagentlocal.device.engine.CommandKind.PIN_PROBE -> "pinprobe"
        else -> "?"
    }

    private fun fixture(tag: String): Pair<String, Pair<String, String>?>? = if (phone == "D5") when (tag) {
        "pinprobe" -> "d5-pinprobe-v0.2.3.out" to null
        "C2-read-k0" -> "d5-row-c2-ok-v0.2.1.out" to null
        "C2-read-k1" -> "d5-row-c2-fast-ok-v0.2.1.out" to ("C2-read-k4" to "C2-read-k1")
        "C3-read-2fast" -> "d5-row-c3-ok.out" to null
        "C4-read-all" -> "d5-row-c4-ok.out" to null
        "C7-gpu-alone" -> "d5-gpu-row-ok.out" to null
        else -> null
    } else when (tag) {
        "pinprobe" -> "d1-pinprobe-v0.2.3.out" to null
        "C2-read-k0" -> "d1-row-c2-k0-v0.2.1.out" to null
        "C2-read-k1" -> "d1-row-c2-k4-v0.2.1.out" to ("C2-read-k4" to "C2-read-k1")
        "C2-read-k2" -> "d1-row-c2-k6-v0.2.1.out" to ("C2-read-k6" to "C2-read-k2")
        "C3-read-2fast" -> "d1-row-c3-v0.2.1.out" to null
        "C4-read-all" -> "d1-row-c4-v0.2.1.out" to null
        "C7-gpu-alone" -> "d1-gpu-row-unstable-v0.2.3.out" to null
        else -> null
    }

    private fun serve(c: EngineCommand): EngineResult {
        val tag = tagOf(c)
        overrides[tag]?.let { return it(c) }
        val f = fixture(tag) ?: throw AssertionError("no recorded answer for $tag")
        val r = Lines.ok(f.first, f.second?.first, f.second?.second)
        val seconds = (r.json["duration_s"] as? Number)?.toDouble() ?: ((r.json["duration_ms"] as? Number)?.toDouble()?.div(1000.0) ?: 2.0)
        clock.now += rowDurationOverrideMs ?: (seconds * 1000).toLong()
        return r
    }

    fun tier(): QuickTier = QuickTier(
        engine = engine, store = store, index = { index }, foreground = fg, ide = ide, conditions = cond, memory = mem,
        screen = screen, ui = ui, clock = clock, watchdog = wd, pluginVersion = "1.1.0", epochMs = { 1_700_000_000_000L }, log = { logs.add(it) }, handOff = { it.run() },
    )

    fun run(): QuickResult = tier().run()

    /** Tags in the order the pass started them. */
    fun tags(): List<String> = engine.commands.map { tagOf(it) }

    fun noTempFiles(): Boolean = dir.walkTopDown().none { it.isFile && it.name.endsWith(".tmp") }

    fun pass(): Map<String, Any?> = store.readPassRecord() ?: throw AssertionError("no pass record")
}
