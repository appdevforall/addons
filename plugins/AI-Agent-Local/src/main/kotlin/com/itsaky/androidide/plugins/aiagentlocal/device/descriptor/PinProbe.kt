package com.itsaky.androidide.plugins.aiagentlocal.device.descriptor

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.JsonException
import com.itsaky.androidide.plugins.aiagentlocal.device.PyCompat

internal class PinProbeCpu(
    val index: Long,
    val result: String,
    val attempts: Long,
    val timeToPinMs: Number,
    val midr: String?,
    val midrSource: String?,
)

/**
 * A `part1-pinprobe-v1` object (the last stdout line of the engine's `--pin-probe --json`). Unknown extra
 * fields are ignored; required fields, types, enums and patterns of
 * `part1-pinprobe-v1.schema.json` are checked with the path of the offending value.
 */
internal class PinProbe(
    val engineVersion: String,
    val gateBasis: String,
    val foregroundCpuset: String?,
    val status: String,
    val exitCode: Long,
    val cpus: List<PinProbeCpu>,
    val durationMs: Number,
    val reason: String?,
) {
    companion object {
        private val ENGINE = Regex("^[a-z0-9-]+/[A-Za-z0-9._-]+$")
        private val MIDR = Regex("^0x[0-9a-f]{8}$")
        private val SOURCES = setOf("sysfs", "cpuinfo", "sysfs_unpinned", "cpuinfo_unpinned")

        fun parse(text: String, path: String = "pinprobe"): PinProbe = parse(Json.parse(text, path), path)

        fun parse(x: Any?, path: String = "pinprobe"): PinProbe {
            val m = Json.obj(x, path)
            for (k in listOf("format", "engine_version", "gate_basis", "foreground_cpuset", "status", "exit_code", "cpus", "duration_ms")) {
                if (!m.containsKey(k)) throw JsonException("$path.$k", "required field is missing")
            }
            if (m["format"] != PartLib.PINPROBE_FORMAT) throw JsonException("$path.format", "must be ${PartLib.PINPROBE_FORMAT}")
            val engine = Json.str(m["engine_version"], "$path.engine_version")
            if (!ENGINE.matches(engine)) throw JsonException("$path.engine_version", "must be <engine name>/<version>")
            val status = Json.str(m["status"], "$path.status")
            if (status !in setOf("ok", "skipped", "failed")) throw JsonException("$path.status", "must be ok, skipped or failed")
            val dur = m["duration_ms"]
            if (!Json.isNumber(dur) || Json.double(dur, "$path.duration_ms") < 0) {
                throw JsonException("$path.duration_ms", "must be a number, not negative")
            }
            return PinProbe(
                engineVersion = engine,
                gateBasis = Json.str(m["gate_basis"], "$path.gate_basis"),
                foregroundCpuset = m["foreground_cpuset"]?.let { Json.str(it, "$path.foreground_cpuset") },
                status = status,
                exitCode = Json.long(m["exit_code"], "$path.exit_code"),
                cpus = Json.arr(m["cpus"], "$path.cpus").mapIndexed { i, c -> parseCpu(c, "$path.cpus[$i]") },
                durationMs = dur as Number,
                reason = if (m.containsKey("reason")) Json.str(m["reason"], "$path.reason") else null,
            )
        }

        private fun parseCpu(x: Any?, path: String): PinProbeCpu {
            val c = Json.obj(x, path)
            for (k in listOf("index", "result", "attempts", "time_to_pin_ms")) {
                if (!c.containsKey(k)) throw JsonException("$path.$k", "required field is missing")
            }
            val index = Json.long(c["index"], "$path.index")
            val attempts = Json.long(c["attempts"], "$path.attempts")
            val ms = c["time_to_pin_ms"]
            if (index < 0) throw JsonException("$path.index", "must not be negative")
            if (attempts < 0) throw JsonException("$path.attempts", "must not be negative")
            if (!Json.isNumber(ms) || Json.double(ms, "$path.time_to_pin_ms") < 0) {
                throw JsonException("$path.time_to_pin_ms", "must be a number, not negative")
            }
            val result = Json.str(c["result"], "$path.result")
            if (result != "ok" && result != "failed") throw JsonException("$path.result", "must be ok or failed")
            val midr = c["midr"]?.let { Json.str(it, "$path.midr") }
            if (midr != null && !MIDR.matches(midr)) throw JsonException("$path.midr", "must be 0x followed by 8 hex digits")
            val src = c["midr_source"]?.let { Json.str(it, "$path.midr_source") }
            if (midr != null && (src == null || src !in SOURCES)) {
                throw JsonException("$path.midr_source", "required and one of $SOURCES when midr is set")
            }
            if (midr == null && c.containsKey("midr") && src != null) throw JsonException("$path.midr_source", "must be null when midr is null")
            if (src != null && src !in SOURCES) throw JsonException("$path.midr_source", "must be one of $SOURCES or null")
            return PinProbeCpu(index, result, attempts, ms as Number, midr, src)
        }

        /**
         * Folds a pin probe into the static input (a `part1-static-v1` tree): `cpus[].pin` and
         * `pin_conditions` (and `pin_origin`). Returns a new tree; the input is not changed.
         *
         * Rules (there is no Python reference for this step; they follow `appcap_to_static.py`):
         *  - only a probe with status `ok` is folded; a skipped or failed probe changes nothing, so the pin
         *    leaves stay `unk` with the reason "no pin result for this CPU in the static input";
         *  - for each probe CPU whose index is in `cpus[]`: `pin = {result, attempts, time_to_pin_s,
         *    cpuset}` with `time_to_pin_s` = `time_to_pin_ms` / 1000 and `cpuset` = the probe's
         *    `foreground_cpuset`; a probe CPU the static input does not list is ignored;
         *  - `pin_origin` = `measured_app`; `pin_conditions` = the static `environment` values
         *    `thermal_status`, `thermal_headroom`, `screen_on` (when the environment is present) plus
         *    `foreground_cpuset` and `gate_basis` from the probe, `regime` `single_shot` and a note naming
         *    the engine id (the key `thermal_status` is always present, null when the environment is missing,
         *    because rule R3 needs the key). Existing `pin_conditions` keys not listed here are kept.
         */
        fun fold(static: Map<String, Any?>, probe: PinProbe): Map<String, Any?> {
            val out = LinkedHashMap(static)
            if (probe.status != "ok") return out
            val byIndex = probe.cpus.associateBy { it.index }
            out["cpus"] = Json.arr(static["cpus"], "static.cpus").mapIndexed { i, c ->
                val cpu = Json.obj(c, "static.cpus[$i]")
                val p = byIndex[Json.long(cpu["index"], "static.cpus[$i].index")]
                if (p == null) {
                    cpu
                } else {
                    LinkedHashMap(cpu).also {
                        it["pin"] = PartLib.obj(
                            "result" to p.result,
                            "attempts" to p.attempts,
                            "time_to_pin_s" to timeToPinS(p.timeToPinMs),
                            "cpuset" to probe.foregroundCpuset,
                        )
                    }
                }
            }
            val env = static["environment"]
            val cond = LinkedHashMap<String, Any?>()
            (static["pin_conditions"] as? Map<*, *>)?.forEach { (k, v) -> cond[k as String] = v }
            if (env is Map<*, *>) {
                for (k in listOf("thermal_status", "thermal_headroom", "screen_on")) cond[k] = env[k]
            }
            if (!cond.containsKey("thermal_status")) cond["thermal_status"] = null // rule R3 needs the key
            cond["foreground_cpuset"] = probe.foregroundCpuset
            cond["gate_basis"] = probe.gateBasis
            cond["regime"] = "single_shot"
            cond["note"] = "pin probe ${probe.engineVersion}"
            out["pin_origin"] = "measured_app"
            out["pin_conditions"] = cond
            return out
        }

        private fun timeToPinS(ms: Number): Any = Json.number(PyCompat.num(ms) / 1000.0)
    }
}
