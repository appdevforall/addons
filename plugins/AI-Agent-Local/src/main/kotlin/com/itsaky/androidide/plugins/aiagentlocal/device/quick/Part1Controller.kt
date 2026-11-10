package com.itsaky.androidide.plugins.aiagentlocal.device.quick

import com.itsaky.androidide.plugins.aiagentlocal.device.descriptor.PinProbe
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineExecutor
import com.itsaky.androidide.plugins.aiagentlocal.device.JsonException
import com.itsaky.androidide.plugins.aiagentlocal.device.match.DeviceIndex
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.StaticReader
import com.itsaky.androidide.plugins.aiagentlocal.device.store.BindResult
import com.itsaky.androidide.plugins.aiagentlocal.device.store.Part1Store
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** Part 1 runs only when this says so: the host's Experiments switch **and** the plugin preference `deviceCharacterization` (default off). */
fun interface Part1Switch {
    fun isOn(): Boolean

    companion object {
        /** [experiments] is asked first; the preference is not even read while Experiments is off. A failing service counts as off. */
        fun of(experiments: () -> Boolean, preference: () -> Boolean): Part1Switch = Part1Switch {
            try {
                experiments() && preference()
            } catch (e: Exception) {
                false
            }
        }
    }
}

/** Everything Part 1 uses, built only when the switch is on ([Part1Controller] calls the factory then and not before). */
class Part1Runtime(
    val reader: StaticReader,
    val store: Part1Store,
    val index: () -> DeviceIndex,
    val engineId: String,
    val pluginVersion: String?,
    val newQuickTier: () -> QuickTier,
    /** The engine runner, to cancel a process still running when the plugin is deactivated. */
    val engine: EngineExecutor? = null,
    val close: () -> Unit = {},
)

/**
 * The entry points the plugin calls: [onActivated] (tier T0, once per plugin activation, off the main thread) and [beforeModelLoad]
 * (tier T1, at the first local-model load of a session, under the generation mutex, before the model is loaded).
 *
 * **Switch.** With either part of the [Part1Switch] off, neither entry point does anything: the runtime factory is not called, so no
 * process is started, no file is read or written, no dialog is shown. **Nothing here throws** into the plugin; a failure is a log line
 * and the plugin goes on.
 *
 * **T0.** Static read (the engine's first call checks its version), `bindDevice` with the build fingerprint (never logged), the static
 * object and the probe facts are stored, the stored pin probe of an earlier T1 pass is folded in again, then `regenerate()` matches the
 * device, records the match and writes the descriptor: valid, with the measured leaves unknown or from the stored T1 rows.
 *
 * **T1.** Runs when the switch is on, T0 is done, no completed T1 pass is recorded for this device key, and the user has not skipped or
 * cancelled it in this session. A deferred pass (no activity, busy IDE, low battery) is tried again at the next load; every other end
 * is not asked again in this session. Cancelling the calling coroutine interrupts the pass, which then destroys its process.
 */
class Part1Controller(
    private val switch: Part1Switch,
    private val runtimeFactory: () -> Part1Runtime,
    private val background: (Runnable) -> Unit,
    private val log: (String) -> Unit = {},
    private val t0WaitMs: Long = 15_000L,
) {
    private val lock = Any()
    private var runtime: Part1Runtime? = null
    private val t0Started = AtomicBoolean(false)
    private val t0Done = CountDownLatch(1)
    @Volatile private var t0Ok = false
    @Volatile private var sessionDone = false
    @Volatile private var closed = false

    private fun runtime(): Part1Runtime = synchronized(lock) { runtime ?: runtimeFactory().also { runtime = it } }

    /** Starts tier T0 in the background when the switch is on. Never throws, never blocks. */
    fun onActivated() {
        try {
            if (!switch.isOn()) return
            if (!t0Started.compareAndSet(false, true)) return
            background(Runnable { runT0() })
        } catch (e: Exception) {
            t0Started.set(true)
            t0Done.countDown()
            log("Part 1: T0 could not be started (${e.javaClass.simpleName})")
        }
    }

    private fun runT0() {
        try {
            val rt = runtime()
            val read = rt.reader.read(rt.pluginVersion)
            val identity = (read.static["identity"] as? Map<*, *>).orEmpty()
            val fingerprint = identity["build_fingerprint"] as? String
            when (val bound = rt.store.bindDevice(fingerprint, rt.engineId)) {
                is BindResult.Unkeyed -> { log("Part 1: no build fingerprint, nothing is stored"); return }
                is BindResult.IoFailed -> { log("Part 1: the store could not be bound (${bound.reason})"); return }
                else -> log("Part 1: store bound (${bound.javaClass.simpleName})")
            }
            var static: Map<String, Any?> = read.static
            val pin = (rt.store.readPassRecord()?.get("pin_probe"))
            if (pin != null) {
                try {
                    static = StaticReader.foldPinProbe(static, PinProbe.parse(pin))
                } catch (e: JsonException) {
                    log("Part 1: the stored pin probe is not usable and is ignored")
                }
            }
            if (!rt.store.writeStatic(static)) { log("Part 1: static.json could not be written"); return }
            if (!rt.store.writeProbeFacts(read.facts, System.currentTimeMillis())) log("Part 1: probe-facts.json could not be written")
            val regenerated = rt.store.regenerate(rt.index(), rt.pluginVersion)
            log("Part 1: T0 done (${regenerated.javaClass.simpleName}, ${read.elapsedMs} ms)")
            t0Ok = true
        } catch (e: Exception) {
            log("Part 1: T0 failed (${e.javaClass.simpleName})")
        } finally {
            t0Done.countDown()
        }
    }

    /**
     * Tier T1 at a local-model load. Returns when the pass (if any) is over; the caller then loads the model. Never throws, except
     * that a [CancellationException] of the calling coroutine is passed on (after the pass has been stopped).
     */
    suspend fun beforeModelLoad() {
        try {
            if (closed || sessionDone || !switch.isOn()) return
            if (t0Started.compareAndSet(false, true)) runT0() // the switch was turned on after the activation: read now
            else if (!t0Done.await(t0WaitMs, TimeUnit.MILLISECONDS)) { log("Part 1: T0 is not done; T1 waits for the next load"); return }
            if (!t0Ok) return
            val rt = runtime()
            if (hasValidT1(rt.store)) return
            val tier = rt.newQuickTier()
            val result = runInterruptible(Dispatchers.IO) { tier.run() }
            if (result.status != QuickStatus.DEFERRED) sessionDone = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Part 1: T1 failed (${e.javaClass.simpleName})")
        }
    }

    /** True when the pass record of this device key says the last pass was completed. */
    private fun hasValidT1(store: Part1Store): Boolean = store.readPassRecord()?.get("status") == QuickStatus.COMPLETED.code

    /** Called when the plugin is deactivated: stops what runs and releases what was built. Idempotent. */
    fun close() {
        closed = true
        val rt = synchronized(lock) { runtime } ?: return
        runCatching { rt.engine?.cancel() }
        runCatching { rt.close() }
    }
}
