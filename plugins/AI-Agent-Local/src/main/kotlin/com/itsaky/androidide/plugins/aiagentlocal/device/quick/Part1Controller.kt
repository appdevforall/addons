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
    /** False when the host gives no build-status service: a quiet IDE cannot be known, so T1 is not offered (deferred, `ide_gate_unavailable`). */
    val ideGateAvailable: Boolean = true,
    val close: () -> Unit = {},
)

/**
 * The entry points the plugin calls: [onActivated] (tier T0, once per plugin activation, off the main thread) and [beforeModelLoad]
 * (tier T1, at the first local-model load of a session, under the generation mutex, before the model is loaded).
 *
 * **Switch.** With either part of the [Part1Switch] off, neither entry point does anything: the runtime factory is not called, so no
 * process is started, no file is read or written, no dialog is shown. **Nothing here throws** into the plugin (an `Error` too: it is
 * logged by class name and the plugin goes on); a [CancellationException] of the calling coroutine is passed on.
 *
 * **T0.** Static read (the engine's first call checks its version), `bindDevice` with the build fingerprint (never logged), the static
 * object and the probe facts are stored, the stored pin probe of an earlier T1 pass is folded in again, then `regenerate()` matches the
 * device, records the match and writes the descriptor: valid, with the measured leaves unknown or from the stored T1 rows. The T0 thread
 * checks the closed mark before every store write, so after [close] it writes nothing; a new controller made with [previous] waits up to
 * [previousWaitMs] for the old T0 thread to end before it binds the store, and does no Part 1 work in that activation when it does not end.
 *
 * **T1.** Runs when the switch is on, T0 is done, no completed T1 pass is recorded for this device key, and the user has not skipped or
 * cancelled it in this session. It is **not** offered (and not asked) when the last T0 showed that the engine cannot run
 * (`exec_blocked`, `program_missing`, `engine_mismatch`: one log line, the pass record is written once as `skipped` with that reason), when
 * the host has no build-status service, or when another model is still loaded at this load (deferred, `model_resident`). A deferred pass
 * (no activity, busy IDE, low battery) is tried again at the next load; every other end is not asked again in this session.
 *
 * **Worst case before the model loads (first load of a session):** up to [t0WaitMs] (15 s) waiting for T0, up to 90 s for the question,
 * up to 180 s for the pass (counted from "Run now"), plus writing the records. Cancelling the calling coroutine interrupts the wait, an
 * inline T0 and the pass, and each kills the process it runs.
 */
class Part1Controller(
    private val switch: Part1Switch,
    private val runtimeFactory: () -> Part1Runtime,
    private val background: (Runnable) -> Unit,
    private val log: (String) -> Unit = {},
    private val t0WaitMs: Long = 15_000L,
    /** The controller of the activation before this one (the plugin was deactivated and activated again), or null. */
    previous: Part1Controller? = null,
    private val previousWaitMs: Long = PREVIOUS_T0_WAIT_MS,
    private val epochMs: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /** How long a new activation waits for the old activation's T0 thread to end. */
        const val PREVIOUS_T0_WAIT_MS = 12_000L
    }

    @Volatile private var previous: Part1Controller? = previous // dropped once waited for or closed, so old controllers are not chained
    private val lock = Any()
    private var runtime: Part1Runtime? = null
    private val t0Started = AtomicBoolean(false)
    private val t0Pending = AtomicBoolean(false)
    private val t0Done = CountDownLatch(1)
    @Volatile private var t0Ok = false
    @Volatile private var engineUnusable: String? = null
    @Volatile private var sessionDone = false
    @Volatile private var closed = false
    @Volatile private var activeTier: QuickTier? = null
    private val ideUnavailableLogged = AtomicBoolean(false)

    private fun runtime(): Part1Runtime = synchronized(lock) { runtime ?: runtimeFactory().also { runtime = it } }

    /** Waits up to [ms] for this controller's T0 to end; true at once when none was started or it has ended. */
    private fun awaitT0End(ms: Long): Boolean = !t0Pending.get() || t0Done.await(ms, TimeUnit.MILLISECONDS)

    /** Starts tier T0 in the background when the switch is on. Never throws, never blocks. */
    fun onActivated() {
        try {
            if (!switch.isOn()) return
            if (!t0Started.compareAndSet(false, true)) return
            t0Pending.set(true)
            background(Runnable { runT0() })
        } catch (e: Throwable) {
            t0Started.set(true)
            t0Done.countDown()
            log("Part 1: T0 could not be started (${e.javaClass.simpleName})")
        }
    }

    private fun runT0() {
        t0Pending.set(true)
        try {
            if (closed) return
            try {
                val before = previous
                if (before != null && !before.awaitT0End(previousWaitMs)) {
                    log("Part 1: the earlier T0 is still running; no Part 1 work in this activation")
                    return
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            previous = null
            if (closed) return
            val rt = runtime()
            val read = rt.reader.read(rt.pluginVersion)
            if (closed) return
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
            if (closed) return
            if (!rt.store.writeStatic(static)) { log("Part 1: static.json could not be written"); return }
            if (closed) return
            if (!rt.store.writeProbeFacts(read.facts, epochMs())) log("Part 1: probe-facts.json could not be written")
            if (closed) return
            val regenerated = rt.store.regenerate(rt.index(), rt.pluginVersion)
            log("Part 1: T0 done (${regenerated.javaClass.simpleName}, ${read.elapsedMs} ms)")
            engineUnusable = read.engineUnusable
            t0Ok = true
        } catch (e: Throwable) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            log("Part 1: T0 failed (${e.javaClass.simpleName})")
        } finally {
            t0Done.countDown()
        }
    }

    /**
     * Tier T1 at a local-model load. Returns when the pass (if any) is over; the caller then loads the model. Never throws, except
     * that a [CancellationException] of the calling coroutine is passed on (after the wait, the inline T0 or the pass has been
     * interrupted and its process killed). [otherModelResident] is true when a different model is still loaded: T1 is then deferred.
     */
    suspend fun beforeModelLoad(otherModelResident: Boolean = false) {
        try {
            if (closed || sessionDone || !switch.isOn()) return
            if (otherModelResident) { log("Part 1: T1 deferred: ${StopReason.MODEL_RESIDENT.code}"); return }
            if (t0Started.compareAndSet(false, true)) {
                runInterruptible(Dispatchers.IO) { runT0() } // the switch was turned on after the activation: read now
            } else if (!runInterruptible(Dispatchers.IO) { t0Done.await(t0WaitMs, TimeUnit.MILLISECONDS) }) {
                log("Part 1: T0 is not done; T1 waits for the next load")
                return
            }
            if (closed || !t0Ok) return
            val rt = runtime()
            if (hasValidT1(rt.store)) return
            engineUnusable?.let { reason ->
                sessionDone = true
                log("Part 1: T1 is not offered, the engine cannot run ($reason)")
                recordSkipped(rt, reason)
                return
            }
            if (!rt.ideGateAvailable) {
                if (ideUnavailableLogged.compareAndSet(false, true)) log("Part 1: T1 deferred: ${StopReason.IDE_GATE_UNAVAILABLE.code}")
                return
            }
            val tier = rt.newQuickTier()
            activeTier = tier
            try {
                if (closed) return
                val result = runInterruptible(Dispatchers.IO) { tier.run() }
                if (result.status != QuickStatus.DEFERRED) sessionDone = true
            } finally {
                activeTier = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("Part 1: T1 failed (${e.javaClass.simpleName})")
        }
    }

    /** The one pass record of a session in which the engine cannot run: status `skipped`, the engine's reason; the stored pin probe is kept. */
    private fun recordSkipped(rt: Part1Runtime, reason: String) {
        val record = linkedMapOf<String, Any?>(
            "tier" to "T1", "status" to QuickStatus.SKIPPED.code, "reason" to reason, "elapsed_ms" to 0L,
            "rows_stored" to 0L, "rows_void" to 0L, "rows" to emptyList<Any?>(),
        )
        rt.store.readPassRecord()?.get("pin_probe")?.let { record["pin_probe"] = it }
        if (closed) return
        if (!rt.store.writePassRecord(record, epochMs())) log("Part 1: the pass record could not be written")
    }

    /** True when the pass record of this device key says the last pass was completed. */
    private fun hasValidT1(store: Part1Store): Boolean = store.readPassRecord()?.get("status") == QuickStatus.COMPLETED.code

    /** Called when the plugin is deactivated: stops what runs and releases what was built. Idempotent. */
    fun close() {
        previous = null
        closed = true // first: the T0 thread writes nothing after this mark
        runCatching { activeTier?.cancel(StopReason.PLUGIN_DEACTIVATED) }
        val rt = synchronized(lock) { runtime } ?: return
        runCatching { rt.engine?.cancel() }
        runCatching { rt.close() }
    }
}
