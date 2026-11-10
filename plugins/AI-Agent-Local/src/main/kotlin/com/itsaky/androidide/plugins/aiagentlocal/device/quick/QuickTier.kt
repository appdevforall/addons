package com.itsaky.androidide.plugins.aiagentlocal.device.quick

import com.itsaky.androidide.plugins.aiagentlocal.device.JsonException
import com.itsaky.androidide.plugins.aiagentlocal.device.descriptor.PinProbe
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineCommand
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineCommands
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineExecutor
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineResult
import com.itsaky.androidide.plugins.aiagentlocal.device.match.DeviceIndex
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.StaticReader
import com.itsaky.androidide.plugins.aiagentlocal.device.store.AppendResult
import com.itsaky.androidide.plugins.aiagentlocal.device.store.Part1Store
import com.itsaky.androidide.plugins.aiagentlocal.device.store.RegenerateResult
import com.itsaky.androidide.plugins.aiagentlocal.device.store.RowRecord

/**
 * One quick-measurement (tier T1) pass: the question, the gates, the rows, the records. Blocking; call it off the main thread, under
 * the generation mutex, before the model is loaded. **Never throws** (an unexpected exception becomes `unexpected`), never makes
 * model loading wait for more than [QuickLimits.OVERALL_MS] after "Run now", and leaves the screen flag, the dialog and the engine
 * process cleaned up in every exit path.
 *
 * **Flow.** The stored static input (T0) must exist. Battery is checked, then the foreground and the quiet IDE (without waiting: if either fails the pass is deferred and the user is
 * not asked), then the one question is asked (Skip, no activity and a timeout end the pass before anything runs). After "Run now" the screen is kept on and the 180 s watchdog starts. Then: pin probe
 * (all CPUs) folded into the static input; one `C2-read-k<id>` per cluster (first pinnable CPU, a substitute from the same cluster, or an
 * unpinned row); clusters ranked by the measured value; `C3-read-2fast` on the two fastest pinnable CPUs; `C4-read-all` on all pinnable
 * CPUs; `C7-gpu-alone` when the GPU probe found a Vulkan compute device. Command lines come only from [EngineCommands].
 *
 * **Gates**, before the start and before every row (a fake each): foreground; quiet IDE (after "Run now" a busy IDE is waited for up to 60 s: before the
 * first row the pass is then deferred, before a later row it ends early); battery (at least 20 %, stop at a drop of 5 points since the start; an unreadable
 * level does not pass); thermal (status NONE or LIGHT to start; a hotter phone is waited for up to 60 s per row, the wait is recorded; a
 * fixed 10 s rest between rows only when neither status nor headroom can be read); memory (`L = min(55 % MemTotal, 70 % MemAvailable)`,
 * read just before the row, with `--fit-buffer`).
 *
 * **Voids.** A row is void (not stored, counted in `rows_void` and listed in the pass record) when the foreground was lost, a build started
 * during it or the pass was cancelled during it (`void_cancelled`). A build that started during a row voids it and the row is repeated once; a second void ends the pass early. A refusal for the
 * foreground (exit 97) voids **that row** and stops the pass: rows stored earlier in the same pass stay (each passed its own engine gate
 * and the app's foreground check after it), the pass ends `ended_early` with reason `not_foreground`, and T1 is offered again next session. Other results than `Ok` that the engine wrote as a row (memory guard, a
 * failure, unsupported) are stored with their status; they never feed a leaf (the generator enforces that).
 *
 * **End.** The valid rows are in the store; [Part1Store.regenerate] runs; `t1-pass.json` records the status, the reason, the total time
 * and what every row came to (only when a process was started). A pass that ends early keeps the valid rows and leaves the rest unknown.
 *
 * Log lines name rows and outcomes only. Nothing here reads, logs or shows the build fingerprint or the device key.
 */
class QuickTier(
    private val engine: EngineExecutor,
    private val store: Part1Store,
    private val index: () -> DeviceIndex,
    private val foreground: ForegroundGate,
    private val ide: QuietIdeGate,
    private val conditions: ConditionsProvider,
    private val memory: MemoryProbe,
    private val screen: ScreenKeeper,
    private val ui: QuickTierUi,
    private val clock: Clock,
    private val watchdog: Watchdog,
    private val pluginVersion: String? = null,
    private val epochMs: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
    /** Runs a task on another thread (the Cancel button's kill); replaced in tests. */
    private val handOff: (Runnable) -> Unit = { r -> Thread(r, "part1-t1-cancel").apply { isDaemon = true }.start() },
) {
    /** Unwinds the pass to the one place that decides the status. */
    private class Stop(val reason: StopReason) : RuntimeException(reason.code, null, false, false)

    private class RowNote(val tag: String, val outcome: String, val wallMs: Long)

    private inner class Pass(val startMs: Long) {
        var engineStarted = false
        var rowsStored = 0
        var rowsVoid = 0
        var incomplete = false
        var pinProbe: Map<String, Any?>? = null
        var pinFolded = false
        var startPct = 0
        var rowsRun = 0
        var waitIdeMs = 0L
        var waitThermalMs = 0L
        var waitRestMs = 0L
        val notes = ArrayList<RowNote>()
        var static: Map<String, Any?> = emptyMap()
        var total = 1
    }

    @Volatile private var cancelReason: StopReason? = null

    /**
     * Set by `cancel(PLUGIN_DEACTIVATED)` and **never reset**: a deactivation is permanent for this tier. [run] does not clear it, and once it is set
     * the pass thread makes no further store write (no row, no static input, no descriptor, no pass record). Rows stored before it stay.
     * A write that is already inside the store when the flag is set cannot be taken back; a regenerate under way when the flag arrives is allowed to
     * finish. The flag is read again after the regenerate step, so the pass record is not written and the result is `CANCELLED` / `plugin_deactivated`;
     * the remaining window is the few instructions between that last read and the record write.
     */
    @Volatile private var deactivated = false

    /**
     * Cancels the pass (from the Cancel button, the watchdog, a cancelled load or the plugin's deactivation): destroys the running process and
     * stops the next step. [StopReason.PLUGIN_DEACTIVATED] also makes the cancel permanent for this tier (see [deactivated]).
     */
    fun cancel(reason: StopReason = StopReason.USER_CANCELLED) {
        if (reason == StopReason.PLUGIN_DEACTIVATED) deactivated = true
        if (cancelReason == null) cancelReason = reason
        engine.cancel()
    }

    private fun deactivatedResult(): QuickResult {
        log("T1 stopped: ${StopReason.PLUGIN_DEACTIVATED.code}")
        return QuickResult(QuickStatus.CANCELLED, StopReason.PLUGIN_DEACTIVATED, 0, 0, 0, false)
    }

    /**
     * What the Cancel button does. It runs on the UI thread, so it only marks the cancel and hands the kill (which can wait up to 3 s
     * for the process) to another thread through [handOff].
     */
    private fun cancelFromButton() {
        if (cancelReason == null) cancelReason = StopReason.USER_CANCELLED
        handOff(Runnable { runCatching { engine.cancel() } })
    }

    /** Runs the pass. See the class comment. */
    fun run(): QuickResult {
        if (deactivated) return deactivatedResult() // first: no store read, no question, no engine
        cancelReason = null
        engine.resetCancel()
        if (deactivated) return deactivatedResult() // a deactivation that came while the cancel state was being reset
        var pass: Pass? = null
        var watch: AutoCloseable? = null
        var screenSet = false
        try {
            val static = store.readStatic() ?: return deferred(StopReason.STATIC_MISSING)
            precheckBattery()?.let { return deferred(it) }
            // F26: no question when the foreground or the IDE already fails; these two checks do not wait
            if (!foreground.isForeground()) return deferred(StopReason.NOT_FOREGROUND)
            if (ide.isBusy()) return deferred(StopReason.IDE_BUSY)
            if (deactivated) return deactivatedResult()
            val answer = try {
                ui.ask()
            } catch (e: InterruptedException) {
                // the load was cancelled while the question was up: not a choice of the user
                Thread.currentThread().interrupt()
                return if (deactivated) deactivatedResult() else QuickResult(QuickStatus.CANCELLED, StopReason.LOAD_CANCELLED, 0, 0, 0, false).also { log("T1 stopped: ${StopReason.LOAD_CANCELLED.code}") }
            }
            if (deactivated) return deactivatedResult()
            when (answer) {
                QuickAnswer.NO_ACTIVITY -> return deferred(StopReason.NO_ACTIVITY)
                QuickAnswer.SKIP -> return QuickResult(QuickStatus.SKIPPED, StopReason.USER_SKIPPED, 0, 0, 0, false).also { log("T1 skipped by the user") }
                QuickAnswer.RUN_NOW -> Unit
            }
            val p = Pass(clock.nowMs())
            pass = p
            p.static = static
            ui.onCancel { cancelFromButton() } // returns at once: it runs on the UI thread
            watch = watchdog.start(QuickLimits.OVERALL_MS) { cancel(StopReason.OVERALL_LIMIT) }
            screen.keepOn()
            screenSet = true
            val stop = try {
                measure(p)
                null
            } catch (s: Stop) {
                s
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Stop(StopReason.LOAD_CANCELLED)
            }
            return finish(p, stop)
        } catch (e: Exception) {
            log("T1 stopped unexpectedly (${e.javaClass.simpleName})")
            return pass?.let { runCatching { finish(it, Stop(StopReason.UNEXPECTED)) }.getOrNull() }
                ?: QuickResult(QuickStatus.FAILED, StopReason.UNEXPECTED, pass?.rowsStored ?: 0, pass?.rowsVoid ?: 0, 0, false)
        } finally {
            runCatching { watch?.close() }
            runCatching { if (screenSet) screen.release() }
            runCatching { ui.close() }
            // a cancel that came late must not leave a process or a flag behind for the next pass
            if (cancelReason != null) runCatching { engine.cancel() }
        }
    }

    private fun deferred(reason: StopReason): QuickResult {
        log("T1 deferred: ${reason.code}")
        return QuickResult(QuickStatus.DEFERRED, reason, 0, 0, 0, false)
    }

    private fun precheckBattery(): StopReason? {
        val pct = conditions.read().batteryPct ?: return StopReason.BATTERY_UNREADABLE
        return if (pct < QuickLimits.BATTERY_MIN_PCT) StopReason.BATTERY_LOW else null
    }

    // ---- the pass ------------------------------------------------------------------------------------------------

    private fun measure(p: Pass) {
        p.startPct = conditions.read().batteryPct ?: throw Stop(StopReason.BATTERY_UNREADABLE)
        val cpuIndices = (p.static["cpus"] as? List<*>).orEmpty().mapNotNull { ((it as? Map<*, *>)?.get("index") as? Number)?.toInt() }
        if (cpuIndices.isEmpty()) throw Stop(StopReason.STATIC_MISSING)
        val vulkan = RowPlanner.hasVulkanCompute(p.static)
        p.total = RowPlanner.totalRows(RowPlanner.clusters(p.static), vulkan, assumeAllPinnable = true)

        // 1. pin probe, folded into the static input
        val pin = doRow(p, RowLabel.PIN_PROBE, null, "pinprobe") { EngineCommands.pinProbe(cpuIndices) }
        val probe = pin.json?.let { j ->
            try { PinProbe.parse(j) } catch (e: JsonException) { null }
        }
        if (pin.state != RowState.STORED_PROBE || probe == null || probe.status != "ok") throw Stop(StopReason.PIN_PROBE_FAILED)
        p.pinProbe = pin.json
        val folded = StaticReader.foldPinProbe(p.static, probe)
        if (deactivated) throw Stop(StopReason.PLUGIN_DEACTIVATED)
        if (!store.writeStatic(folded)) throw Stop(StopReason.STORE_FAILED)
        p.pinFolded = true
        p.static = folded

        val clusters = RowPlanner.clusters(folded)
        p.total = RowPlanner.totalRows(clusters, vulkan)

        // 2. one single-core read per cluster
        val measured = HashMap<Int, Double>()
        for (c in clusters) {
            val choice = RowPlanner.c2Choice(c)
            val r = doRow(p, RowLabel.CLUSTER_READ, c.id, "C2-read-k${c.id}", adjust = { rec ->
                if (choice.substitutedFor != null) substituted(rec, choice.substitutedFor) else rec
            }) { l -> EngineCommands.c2Read(c.id, choice.cpu, l, allowUnpinned = choice.unpinned) }
            r.gbs?.let { measured[c.id] = it }
        }

        // 3. the two fastest pinnable CPUs, 4. all pinnable CPUs, 5. the GPU alone
        val ranking = RowPlanner.ranking(clusters, measured)
        RowPlanner.twoFastest(clusters, ranking)?.let { two ->
            doRow(p, RowLabel.TWO_FAST_READ, null, "C3-read-2fast") { l -> EngineCommands.c3Read2Fast(two[0], two[1], l) }
        }
        val all = RowPlanner.allPinnable(clusters)
        if (all.isNotEmpty()) doRow(p, RowLabel.ALL_READ, null, "C4-read-all") { l -> EngineCommands.c4ReadAll(all, l) }
        if (vulkan) doRow(p, RowLabel.GPU_READ, null, "C7-gpu-alone") { l -> EngineCommands.c7GpuAlone(l) }
    }

    private fun substituted(rec: Map<String, Any?>, from: Int): Map<String, Any?> {
        val pin = (rec["pin"] as? Map<*, *>) ?: return rec
        if (pin["result"] != "ok") return rec
        val np = LinkedHashMap<String, Any?>()
        for ((k, v) in pin) np[k as String] = v
        np["result"] = "substituted"
        np["substituted_for"] = from.toLong()
        return LinkedHashMap(rec).also { it["pin"] = np }
    }

    private enum class RowState { STORED, STORED_PROBE, NOT_STORED }

    private class RowOutcome(val state: RowState, val json: Map<String, Any?>?, val gbs: Double?)

    /** The row [tag] with its gates, the void rules and the repeat-once rule. [build] gets the memory limit in MiB (the pin probe ignores it). */
    private fun doRow(
        p: Pass,
        label: RowLabel,
        cluster: Int?,
        tag: String,
        adjust: (Map<String, Any?>) -> Map<String, Any?> = { it },
        build: (Long) -> EngineCommand,
    ): RowOutcome {
        val isProbe = label == RowLabel.PIN_PROBE
        for (attempt in 1..2) {
            gates(p)
            p.rowsRun++
            ui.showProgress(Progress(p.rowsRun.coerceAtMost(p.total), p.total, label, cluster))
            val limit = if (isProbe) 0L else (memory.read() ?: throw Stop(StopReason.MEMORY_UNREADABLE)).let { MemoryGuard.limitMib(it) }
            val command = build(limit)
            val cond = conditions.read()
            val startsBefore = ide.startsSeen()
            val t0 = clock.nowMs()
            p.engineStarted = true
            val result = engine.run(command)
            val wall = clock.nowMs() - t0
            interruptedOrCancelled()?.let {
                p.rowsVoid++ // the row was cancelled while it ran: void, counted and listed
                p.notes.add(RowNote(tag, "void_cancelled", wall))
                throw Stop(it)
            }
            val buildDuring = ide.startsSeen() != startsBefore || ide.isBusy()
            val lostForeground = !foreground.isForeground()

            when (result) {
                is EngineResult.Cancelled -> throw Stop(cancelReason ?: StopReason.USER_CANCELLED)
                is EngineResult.ExecBlocked -> throw Stop(StopReason.EXEC_BLOCKED)
                is EngineResult.ProgramMissing -> throw Stop(StopReason.PROGRAM_MISSING)
                is EngineResult.EngineMismatch -> throw Stop(StopReason.ENGINE_MISMATCH)
                is EngineResult.NotStarted -> throw Stop(StopReason.NOT_STARTED)
                is EngineResult.TimeoutKilled -> throw Stop(StopReason.ENGINE_TIMEOUT)
                is EngineResult.NoJson -> throw Stop(StopReason.ENGINE_CRASH)
                is EngineResult.Refused -> if (result.exit == FOREGROUND_REFUSAL) {
                    p.rowsVoid++
                    p.notes.add(RowNote(tag, "void_foreground_refused", wall))
                    throw Stop(StopReason.NOT_FOREGROUND)
                }
                else -> Unit
            }
            if (buildDuring) {
                p.rowsVoid++
                p.notes.add(RowNote(tag, "void_build_started", wall))
                log("row $tag void: a build started")
                if (attempt == 1) continue
                throw Stop(StopReason.IDE_BUSY)
            }
            if (lostForeground) {
                p.rowsVoid++
                p.notes.add(RowNote(tag, "void_foreground_lost", wall))
                log("row $tag void: foreground lost")
                throw Stop(StopReason.NOT_FOREGROUND)
            }

            val json: Map<String, Any?>? = when (result) {
                is EngineResult.Ok -> result.json
                is EngineResult.Refused -> result.json
                is EngineResult.Failed -> result.json
                else -> null
            }
            if (json == null) { // Failed with no object of its own (for example an interrupted wait)
                interruptedOrCancelled()?.let { throw Stop(it) }
                throw Stop(StopReason.ENGINE_CRASH)
            }
            if (isProbe) {
                return if (result is EngineResult.Ok) {
                    p.notes.add(RowNote(tag, "ok", wall))
                    RowOutcome(RowState.STORED_PROBE, json, null)
                } else {
                    p.notes.add(RowNote(tag, "not_ok", wall))
                    RowOutcome(RowState.NOT_STORED, json, null)
                }
            }
            if (deactivated) throw Stop(StopReason.PLUGIN_DEACTIVATED) // no row is written after a deactivation
            val record = adjust(withAppFacts(json, cond, wall, p))
            return when (val a = store.appendRow(record)) {
                is AppendResult.Appended -> {
                    p.rowsStored++
                    val status = json["status"] as? String ?: "?"
                    p.notes.add(RowNote(tag, if (result is EngineResult.Ok) "ok" else "stored_$status", wall))
                    log("row $tag stored: $status")
                    RowOutcome(RowState.STORED, json, if (result is EngineResult.Ok) (json["gbs_median"] as? Number)?.toDouble() else null)
                }
                is AppendResult.IoFailed -> throw Stop(StopReason.STORE_FAILED)
                is AppendResult.Rejected -> {
                    // the engine's own answer is not a valid row (it must not be forced in); the rest of the pass goes on
                    p.incomplete = true
                    p.notes.add(RowNote(tag, "not_storable", wall))
                    log("row $tag not stored: the engine's record is not a valid row")
                    RowOutcome(RowState.NOT_STORED, json, null)
                }
            }
        }
        throw Stop(StopReason.UNEXPECTED) // unreachable: the second attempt returns or throws
    }

    /** The conditions the app knows (read before the row started) and the app's wall-clock time of the row, added to the engine's record. */
    private fun withAppFacts(row: Map<String, Any?>, cond: com.itsaky.androidide.plugins.aiagentlocal.device.staticread.ConditionsSnapshot, wallMs: Long, p: Pass): Map<String, Any?> {
        val withCond = RowRecord.withConditions(row, cond)
        @Suppress("UNCHECKED_CAST")
        val c = LinkedHashMap(withCond["conditions"] as Map<String, Any?>)
        val sentence = "app: wall ${"%.2f".format(java.util.Locale.ROOT, wallMs / 1000.0)} s; thermal wait ${"%.1f".format(java.util.Locale.ROOT, p.waitThermalMs / 1000.0)} s in this pass"
        val old = c["note"] as? String
        c["note"] = if (old.isNullOrEmpty()) sentence else "$old; $sentence"
        return LinkedHashMap(withCond).also { it["conditions"] = c }
    }

    // ---- gates ---------------------------------------------------------------------------------------------------

    private fun interruptedOrCancelled(): StopReason? {
        if (deactivated) return StopReason.PLUGIN_DEACTIVATED
        cancelReason?.let { return it }
        if (Thread.currentThread().isInterrupted) return StopReason.LOAD_CANCELLED
        return null
    }

    /** All gates before a row or the start. Throws [Stop] with the reason of the first that does not pass. */
    private fun gates(p: Pass) {
        interruptedOrCancelled()?.let { throw Stop(it) }
        if (clock.nowMs() - p.startMs >= QuickLimits.OVERALL_MS) throw Stop(StopReason.OVERALL_LIMIT)
        if (!foreground.isForeground()) throw Stop(StopReason.NOT_FOREGROUND)
        if (ide.isBusy()) {
            ui.showWaiting(WaitingFor.IDE_QUIET)
            val waited = waitUntil(QuickLimits.IDE_WAIT_MS) { !ide.isBusy() }
            p.waitIdeMs += waited.first
            if (!waited.second) throw Stop(StopReason.IDE_BUSY)
            if (!foreground.isForeground()) throw Stop(StopReason.NOT_FOREGROUND)
        }
        thermal(p)
        val pct = conditions.read().batteryPct ?: throw Stop(StopReason.BATTERY_UNREADABLE)
        if (pct < QuickLimits.BATTERY_MIN_PCT) throw Stop(StopReason.BATTERY_LOW)
        if (p.startPct - pct >= QuickLimits.BATTERY_DROP_PCT) throw Stop(StopReason.BATTERY_DROP)
    }

    private fun thermal(p: Pass) {
        val cond = conditions.read()
        val status = cond.thermalStatus
        if (status != null) {
            if (status <= QuickLimits.THERMAL_OK_MAX) return
            ui.showWaiting(WaitingFor.THERMAL)
            val waited = waitUntil(QuickLimits.THERMAL_WAIT_MS) { (conditions.read().thermalStatus ?: 0) <= QuickLimits.THERMAL_OK_MAX }
            p.waitThermalMs += waited.first
            if (!waited.second) throw Stop(StopReason.THERMAL)
            return
        }
        if (cond.thermalHeadroom != null) return // status unreadable, headroom is a number: recorded with the row, no rest
        if (p.rowsRun > 0) { // neither can be read: the fixed rest between heavy rows (plan 8.3 item 1)
            ui.showWaiting(WaitingFor.REST)
            sleepChecked(QuickLimits.BLIND_REST_MS)
            p.waitRestMs += QuickLimits.BLIND_REST_MS
        }
    }

    /** Polls [ok] every [QuickLimits.POLL_MS] until it holds or [maxMs] have passed. Returns (ms waited, whether it held). Checks the cancel flag at every step. */
    private fun waitUntil(maxMs: Long, ok: () -> Boolean): Pair<Long, Boolean> {
        val t0 = clock.nowMs()
        while (true) {
            interruptedOrCancelled()?.let { throw Stop(it) }
            if (ok()) return (clock.nowMs() - t0) to true
            val waited = clock.nowMs() - t0
            if (waited >= maxMs) return waited to false
            clock.sleepMs(minOf(QuickLimits.POLL_MS, maxMs - waited))
        }
    }

    private fun sleepChecked(ms: Long) {
        val t0 = clock.nowMs()
        while (clock.nowMs() - t0 < ms) {
            interruptedOrCancelled()?.let { throw Stop(it) }
            clock.sleepMs(minOf(QuickLimits.POLL_MS, ms - (clock.nowMs() - t0)))
        }
    }

    // ---- the end -------------------------------------------------------------------------------------------------

    private fun finish(p: Pass, stop: Stop?): QuickResult {
        var dead = deactivated // a deactivated pass writes no descriptor and no pass record
        var regenerated = true
        if (!dead && (p.rowsStored > 0 || p.pinFolded)) {
            regenerated = try {
                store.regenerate(index(), pluginVersion) is RegenerateResult.Regenerated
            } catch (e: Exception) { // the index asset could not be read: the rows stay stored, the descriptor is regenerated at the next activation
                false
            }
            if (!regenerated) log("T1: the descriptor could not be regenerated")
        }
        dead = deactivated // read again after the regenerate step, which can take a while: the flag may have come during it
        val reason = if (dead) StopReason.PLUGIN_DEACTIVATED else stop?.reason ?: if (p.incomplete) StopReason.ENGINE_RECORD_INVALID else StopReason.NONE
        val status = when {
            dead -> QuickStatus.CANCELLED
            stop == null && !p.incomplete -> QuickStatus.COMPLETED
            stop == null -> QuickStatus.ENDED_EARLY
            else -> statusFor(stop.reason, p)
        }
        val elapsed = clock.nowMs() - p.startMs
        var recorded = false
        if (!dead && p.engineStarted) {
            recorded = store.writePassRecord(passRecord(p, status, reason, elapsed, regenerated), epochMs())
            if (!recorded) log("T1: the pass record could not be written")
        }
        if (dead) log("T1 stopped: ${reason.code}") else log("T1 ${status.code} (${reason.code}): ${p.rowsStored} rows stored, ${p.rowsVoid} void, $elapsed ms")
        return QuickResult(status, reason, p.rowsStored, p.rowsVoid, elapsed, recorded)
    }

    private fun statusFor(reason: StopReason, p: Pass): QuickStatus = when (reason) {
        StopReason.USER_CANCELLED, StopReason.LOAD_CANCELLED, StopReason.PLUGIN_DEACTIVATED -> QuickStatus.CANCELLED
        StopReason.USER_SKIPPED -> QuickStatus.SKIPPED
        StopReason.NO_ACTIVITY, StopReason.STATIC_MISSING, StopReason.MODEL_RESIDENT, StopReason.IDE_GATE_UNAVAILABLE -> QuickStatus.DEFERRED
        StopReason.EXEC_BLOCKED, StopReason.PROGRAM_MISSING, StopReason.ENGINE_MISMATCH, StopReason.NOT_STARTED,
        StopReason.ENGINE_CRASH, StopReason.ENGINE_TIMEOUT, StopReason.PIN_PROBE_FAILED, StopReason.UNEXPECTED,
        -> if (p.rowsStored == 0) QuickStatus.FAILED else QuickStatus.ENDED_EARLY
        StopReason.OVERALL_LIMIT, StopReason.STORE_FAILED -> QuickStatus.ENDED_EARLY
        else -> if (!p.engineStarted) QuickStatus.DEFERRED else QuickStatus.ENDED_EARLY // a gate that did not pass
    }

    private fun passRecord(p: Pass, status: QuickStatus, reason: StopReason, elapsedMs: Long, regenerated: Boolean): Map<String, Any?> {
        val rec = LinkedHashMap<String, Any?>()
        rec["tier"] = "T1"
        rec["status"] = status.code
        rec["reason"] = reason.code
        rec["elapsed_ms"] = elapsedMs
        rec["rows_stored"] = p.rowsStored.toLong()
        rec["rows_void"] = p.rowsVoid.toLong()
        rec["descriptor_regenerated"] = regenerated
        rec["battery_start_pct"] = p.startPct.toLong()
        rec["waits_ms"] = linkedMapOf<String, Any?>("ide" to p.waitIdeMs, "thermal" to p.waitThermalMs, "rest" to p.waitRestMs)
        rec["rows"] = p.notes.map { linkedMapOf<String, Any?>("tag" to it.tag, "outcome" to it.outcome, "wall_ms" to it.wallMs) }
        // the pin probe as the engine gave it, so the next T0 read can fold it into the static input again
        (p.pinProbe ?: store.readPassRecord()?.get("pin_probe") as? Map<String, Any?>)?.let { rec["pin_probe"] = it }
        return rec
    }

    companion object {
        /** The engine's exit code for "not in the foreground" (EXIT-CODES.md). */
        const val FOREGROUND_REFUSAL = 97
    }
}
