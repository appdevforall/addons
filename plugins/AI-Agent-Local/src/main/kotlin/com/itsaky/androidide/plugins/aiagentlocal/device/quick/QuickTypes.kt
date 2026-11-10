package com.itsaky.androidide.plugins.aiagentlocal.device.quick

import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.ConditionsSnapshot

/*
 * The seams of the quick measurement (tier T1): every gate, the clock, the screen flag, the dialog and the watchdog are interfaces,
 * so the JVM tests replace each with a fake. Nothing here knows Android.
 */

/** Time and waiting. [sleepMs] may throw [InterruptedException] (the real one does; the pass then counts as cancelled). */
interface Clock {
    fun nowMs(): Long
    fun sleepMs(ms: Long)
}

/** Gate 1: the app's current activity is resumed (in the foreground). */
fun interface ForegroundGate {
    fun isForeground(): Boolean
}

/**
 * Gate 2: no build, sync or indexing is running in the IDE. [startsSeen] counts the builds that started since the gate was made, so a
 * build that began and ended inside one row is still seen.
 */
interface QuietIdeGate {
    fun isBusy(): Boolean
    fun startsSeen(): Long
}

/** The conditions the app knows (gates 3 and 4 read them, and every stored row records them). */
fun interface ConditionsProvider {
    fun read(): ConditionsSnapshot
}

/** `/proc/meminfo` figures in kB; null when it cannot be read. */
class MemInfo(val totalKb: Long, val availKb: Long)

fun interface MemoryProbe {
    fun read(): MemInfo?
}

/** Keeps the screen on for the duration of a pass. [release] is called in every exit path and must be harmless when nothing was set. */
interface ScreenKeeper {
    fun keepOn()
    fun release()
}

/** The user's answer to the one question that comes before anything is measured. */
enum class QuickAnswer { RUN_NOW, SKIP, NO_ACTIVITY }

/** What a row measures, for the progress text; the user interface turns it into a string resource. */
enum class RowLabel { PIN_PROBE, CLUSTER_READ, TWO_FAST_READ, ALL_READ, GPU_READ }

/** One step of the progress text: "row [row] of [total]", what it measures, and a cluster number when that matters. */
class Progress(val row: Int, val total: Int, val label: RowLabel, val cluster: Int?)

/** What the pass is waiting for, shown instead of a row. */
enum class WaitingFor { IDE_QUIET, THERMAL, REST }

/**
 * The user interface of a pass: the question, the progress with a Cancel button, and the end. Implemented on the current activity
 * by the app; faked in tests. [ask] blocks (it is called off the main thread) and must return [QuickAnswer.SKIP] by itself when the
 * user does not answer in time or the dialog goes away.
 */
interface QuickTierUi {
    /** Shows the question; [QuickAnswer.NO_ACTIVITY] when there is no activity to show it on. */
    fun ask(): QuickAnswer

    /** Registers what the Cancel button does; the button belongs to the progress view. */
    fun onCancel(handler: () -> Unit)

    fun showProgress(progress: Progress)
    fun showWaiting(what: WaitingFor)

    /** Removes everything this pass put on screen. Idempotent. */
    fun close()
}

/** Runs [onTimeout] once, [ms] after [start], unless closed first (the overall limit of 180 s). */
fun interface Watchdog {
    fun start(ms: Long, onTimeout: () -> Unit): AutoCloseable
}

/** Why a pass did not measure everything. The code is what `t1-pass.json` records; no value here holds an identifier. */
enum class StopReason(val code: String) {
    NONE("none"),
    USER_SKIPPED("user_skipped"),
    /** Only the Cancel button. */
    USER_CANCELLED("user_cancelled"),

    /** The calling load was cancelled (the thread was interrupted). */
    LOAD_CANCELLED("load_cancelled"),

    /** The plugin was deactivated while the pass ran. */
    PLUGIN_DEACTIVATED("plugin_deactivated"),

    /** Another model was still loaded when the load that would have run the pass began. */
    MODEL_RESIDENT("model_resident"),

    /** The host offers no build-status service, so a quiet IDE cannot be known. */
    IDE_GATE_UNAVAILABLE("ide_gate_unavailable"),

    /** The engine's answer for a row was not a valid row record. */
    ENGINE_RECORD_INVALID("engine_record_invalid"),
    NO_ACTIVITY("no_activity"),
    NOT_FOREGROUND("not_foreground"),
    IDE_BUSY("ide_busy"),
    BATTERY_LOW("battery_low"),
    BATTERY_UNREADABLE("battery_unreadable"),
    BATTERY_DROP("battery_drop"),
    THERMAL("thermal"),
    MEMORY_UNREADABLE("memory_unreadable"),
    STATIC_MISSING("static_missing"),
    PIN_PROBE_FAILED("pin_probe_failed"),
    STORE_FAILED("store_failed"),
    EXEC_BLOCKED("exec_blocked"),
    PROGRAM_MISSING("program_missing"),
    ENGINE_MISMATCH("engine_mismatch"),
    NOT_STARTED("not_started"),
    ENGINE_TIMEOUT("engine_timeout"),
    ENGINE_CRASH("engine_crash"),
    OVERALL_LIMIT("overall_limit"),
    UNEXPECTED("unexpected"),
}

enum class QuickStatus(val code: String) {
    /** Every planned row ran and came to a stored row (a refused or failed row counts: the engine's own answer was stored). */
    COMPLETED("completed"),

    /** The pass stopped; the valid rows are kept and the rest is unknown. */
    ENDED_EARLY("ended_early"),

    /** The user pressed Cancel, the load was cancelled or the plugin was deactivated. */
    CANCELLED("cancelled"),

    /** The user chose Skip. Nothing was measured. */
    SKIPPED("skipped"),

    /** Not now: no activity, quiet IDE or battery not met before the start. Nothing was measured; the next load tries again. */
    DEFERRED("deferred"),

    /** The engine could not be used (exec blocked, program missing, version mismatch, crash). */
    FAILED("failed"),
}

/** What a pass came to. [recorded] is true when `t1-pass.json` was written. */
class QuickResult(
    val status: QuickStatus,
    val reason: StopReason,
    val rowsStored: Int,
    val rowsVoid: Int,
    val elapsedMs: Long,
    val recorded: Boolean,
) {
    override fun toString() = "QuickResult(${status.code}, ${reason.code}, stored=$rowsStored, void=$rowsVoid, ${elapsedMs} ms)"
}

/** The fixed numbers of the pass (plan 3.5, 3.6 and 8.3, and the P1-04A2 prompt). */
object QuickLimits {
    /** Overall limit of one pass from "Run now"; then it is cancelled. */
    const val OVERALL_MS = 180_000L

    /** How long a busy IDE is waited for before the start (then the pass is deferred) and before each row (then the pass ends early). */
    const val IDE_WAIT_MS = 60_000L

    /** How long a hot phone is waited for per row. */
    const val THERMAL_WAIT_MS = 60_000L

    /** The fixed rest when neither thermal status nor headroom can be read (plan 8.3 item 1). */
    const val BLIND_REST_MS = 10_000L

    /** Poll step of every wait. */
    const val POLL_MS = 500L

    const val BATTERY_MIN_PCT = 20
    const val BATTERY_DROP_PCT = 5

    /** `L = min(55 % MemTotal, 70 % MemAvailable)`. */
    const val MEM_TOTAL_PERCENT = 55
    const val MEM_AVAIL_PERCENT = 70

    /** Thermal status values that may start a row: NONE and LIGHT. */
    const val THERMAL_OK_MAX = 1

    /** How long the question stays up before it counts as Skip. */
    const val ASK_TIMEOUT_MS = 90_000L
}
