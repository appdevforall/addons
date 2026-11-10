package com.itsaky.androidide.plugins.aiagentlocal.device.engine

/**
 * What one engine invocation came to. The runner never throws; every outcome is one of these.
 *
 * The engine's exit codes are in `part1-engine/EXIT-CODES.md`. A record the engine wrote without a `tag` has already
 * been given the tag of the row that was started.
 */
sealed class EngineResult {

    /** Exit 0 and `status` `ok`. [json] is the last non-empty stdout line (a row, or a probe object). */
    class Ok(val json: Map<String, Any?>) : EngineResult() {
        override fun toString() = "Ok(${json["format"]})"
    }

    /** An expected, recorded refusal: exit 88 (memory guard), 97 (not foreground) or 3 (unsupported). The engine wrote [json]. */
    class Refused(val json: Map<String, Any?>, val exit: Int) : EngineResult() {
        override fun toString() = "Refused($exit, ${json["reason"]})"
    }

    /** An error exit (1, 2, 87, 124, or an exit 0 whose `status` is not `ok`), with the engine's own object when it wrote one. */
    class Failed(val json: Map<String, Any?>?, val exit: Int) : EngineResult() {
        override fun toString() = "Failed($exit, ${json?.get("reason")})"
    }

    /** The process wrote no JSON object as its last stdout line (killed by the system, loader error, garbage): the raw [exit] and the end of stderr. */
    class NoJson(val exit: Int, val stderrTail: String) : EngineResult() {
        override fun toString() = "NoJson($exit)"
    }

    /** The process could not be started at all (plan 3.5 `exec_blocked`), or the program file was not found. */
    class ExecBlocked(val message: String) : EngineResult() {
        override fun toString() = "ExecBlocked($message)"
    }

    /** The process was still running at its hard limit (`--max-seconds` + 5 s) and was destroyed. No partial result is used. */
    object TimeoutKilled : EngineResult() {
        override fun toString() = "TimeoutKilled"
    }

    /**
     * The static probe's `engine_version` is not the one this plugin was built for ([found] is null when the object had none).
     * Nothing else is run, in this and in every later call of the session; the extraction directory is not repaired.
     */
    class EngineMismatch(val found: String?) : EngineResult() {
        override fun toString() = "EngineMismatch($found)"
    }
}
