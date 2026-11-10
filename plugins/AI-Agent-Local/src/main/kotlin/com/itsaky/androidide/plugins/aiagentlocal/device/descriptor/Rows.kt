package com.itsaky.androidide.plugins.aiagentlocal.device.descriptor

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.JsonException

/** The pin result recorded in a row (`pin` object of part1-rows-v1). */
internal data class RowPin(
    val result: String,
    val attempts: Long?,
    val timeToPinMs: Number?,
    val substitutedFor: Long?,
)

/**
 * One record of a `part1-rows-v1` row log. Unknown extra fields are ignored (the engine may add
 * optional ones); required fields and the types, enums, patterns and conditional rules of
 * `part1-rows-v1.schema.json` are checked, and a violation is a [JsonException] with the path.
 * [conditions] keeps only the keys the schema defines.
 */
internal class Row(
    val tag: String,
    val kitTag: String?,
    val kind: String,
    val tier: String?,
    val status: String,
    val cpuSet: List<Long>,
    val threads: Long,
    val bufferMib: Number?,
    val gbsMedian: Number?,
    val gbsMin: Number?,
    val gbsMax: Number?,
    val repeats: Long,
    val singleShot: Boolean,
    val passes: Long?,
    val minfltTimed: Long?,
    val reason: String?,
    val variant: String?,
    val pin: RowPin,
    val exitCode: Long,
    val gateBasis: String,
    val conditions: Map<String, Any?>,
    val durationS: Number?,
    val durationBasis: String?,
    val engineVersion: String,
    val origin: String,
    val labels: List<String>,
) {
    companion object {
        private val TAG = Regex(
            "^(pinprobe|C1-sweep-k[0-9]+|C2-read-k[0-9]+|C3-read-2fast|C4-read-all|C5-write-k[0-9]+|C6-copy-k[0-9]+|C7-gpu-alone|C8-gpu-with-cpustream|C9-prefetch-k[0-9]+-d[0-9]+|PC-pacer-[a-z0-9]+-(on|off)|S1-gpu-10min|S2-read-10min)$",
        )
        private val ENGINE = Regex("^[a-z0-9-]+/[A-Za-z0-9._-]+$")
        private val KINDS = setOf("pinprobe", "read", "write", "copy", "gpu", "sweep", "prefetch", "pacer", "sustained")
        private val STATUSES = setOf("ok", "void", "unsupported", "skipped", "failed")
        private val PIN_RESULTS = setOf("ok", "substituted", "unpinned", "failed", "not_applicable")
        private val ORIGINS = setOf("measured_app", "measured_shell")
        private val REQUIRED = listOf(
            "format", "tag", "kind", "status", "cpu_set", "threads", "buffer_mib", "gbs_median", "gbs_min", "gbs_max",
            "repeats", "single_shot", "variant", "pin", "exit_code", "gate_basis", "conditions", "duration_s",
            "engine_version", "origin", "labels",
        )

        fun parse(x: Any?, path: String = "row"): Row {
            val m = Json.obj(x, path)
            for (k in REQUIRED) if (!m.containsKey(k)) throw JsonException("$path.$k", "required field is missing")
            if (m["format"] != PartLib.ROWS_FORMAT) throw JsonException("$path.format", "must be ${PartLib.ROWS_FORMAT}")
            val tag = Json.str(m["tag"], "$path.tag")
            if (!TAG.matches(tag)) throw JsonException("$path.tag", "not a row tag of part1-rows-v1: $tag")
            val engine = Json.str(m["engine_version"], "$path.engine_version")
            if (!ENGINE.matches(engine)) throw JsonException("$path.engine_version", "must be <engine name>/<version>")
            val status = enum(m["status"], STATUSES, "$path.status")
            val cpuSet = Json.arr(m["cpu_set"], "$path.cpu_set").mapIndexed { i, v -> nonNegLong(v, "$path.cpu_set[$i]") }
            if (cpuSet.toSet().size != cpuSet.size) throw JsonException("$path.cpu_set", "items must be unique")
            val repeats = Json.long(m["repeats"], "$path.repeats")
            if (repeats < 1) throw JsonException("$path.repeats", "must be at least 1")
            val single = Json.bool(m["single_shot"], "$path.single_shot")
            val median = numOrNull(m["gbs_median"], "$path.gbs_median", true)
            val gmin = numOrNull(m["gbs_min"], "$path.gbs_min", true)
            val gmax = numOrNull(m["gbs_max"], "$path.gbs_max", true)
            // conditional rules (allOf)
            if (status == "ok" && median == null) throw JsonException("$path.gbs_median", "must be a number when status is ok")
            if (repeats >= 2) {
                if (gmin == null) throw JsonException("$path.gbs_min", "must be a number when repeats is 2 or more")
                if (gmax == null) throw JsonException("$path.gbs_max", "must be a number when repeats is 2 or more")
                if (single) throw JsonException("$path.single_shot", "must be false when repeats is 2 or more")
            }
            if (single && repeats != 1L) throw JsonException("$path.repeats", "must be 1 when single_shot is true")
            val labels = Json.arr(m["labels"], "$path.labels").mapIndexed { i, v -> Json.str(v, "$path.labels[$i]") }
            return Row(
                tag = tag,
                kitTag = optStr(m, "kit_tag", path),
                kind = enum(m["kind"], KINDS, "$path.kind"),
                tier = if (m.containsKey("tier")) enum(m["tier"], setOf("T1", "T2", "T3"), "$path.tier") else null,
                status = status,
                cpuSet = cpuSet,
                threads = nonNegLong(m["threads"], "$path.threads"),
                bufferMib = numOrNull(m["buffer_mib"], "$path.buffer_mib", false),
                gbsMedian = median,
                gbsMin = gmin,
                gbsMax = gmax,
                repeats = repeats,
                singleShot = single,
                passes = longOrNull(m["passes"], "$path.passes"),
                minfltTimed = if (m.containsKey("minflt_timed")) nonNegLong(m["minflt_timed"], "$path.minflt_timed") else null,
                reason = optStr(m, "reason", path),
                variant = m["variant"]?.let { Json.str(it, "$path.variant") },
                pin = parsePin(m["pin"], "$path.pin"),
                exitCode = Json.long(m["exit_code"], "$path.exit_code"),
                gateBasis = Json.str(m["gate_basis"], "$path.gate_basis"),
                conditions = parseConditions(m["conditions"], "$path.conditions"),
                durationS = numOrNull(m["duration_s"], "$path.duration_s", true),
                durationBasis = optStr(m, "duration_basis", path),
                engineVersion = engine,
                origin = enum(m["origin"], ORIGINS, "$path.origin"),
                labels = labels,
            )
        }

        private fun parsePin(x: Any?, path: String): RowPin {
            val p = Json.obj(x, path)
            for (k in listOf("result", "attempts", "time_to_pin_ms")) {
                if (!p.containsKey(k)) throw JsonException("$path.$k", "required field is missing")
            }
            val attempts = longOrNull(p["attempts"], "$path.attempts")
            if (attempts != null && attempts < 0) throw JsonException("$path.attempts", "must not be negative")
            return RowPin(
                result = enum(p["result"], PIN_RESULTS, "$path.result"),
                attempts = attempts,
                timeToPinMs = numOrNull(p["time_to_pin_ms"], "$path.time_to_pin_ms", true),
                substitutedFor = longOrNull(p["substituted_for"], "$path.substituted_for"),
            )
        }

        private fun parseConditions(x: Any?, path: String): Map<String, Any?> {
            val c = Json.obj(x, path)
            val out = LinkedHashMap<String, Any?>()
            for (k in PartLib.CONDITION_KEYS) {
                if (!c.containsKey(k)) continue
                val v = c[k]
                val p = "$path.$k"
                when (k) {
                    "thermal_status" -> longOrNull(v, p)
                    "thermal_headroom", "battery_pct", "display_refresh_hz", "buffer_mib", "start_temp_c",
                    "memavail_mib", "memtotal_mib",
                    -> numOrNull(v, p, false)
                    "charging", "screen_on", "comparable" -> if (v != null) Json.bool(v, p)
                    "display_px", "foreground_cpuset", "gate_basis", "cpu_set", "pin", "kit", "note" -> if (v != null) Json.str(v, p)
                    "regime" -> if (v != null) enum(v, setOf("single_shot", "median_of_repeats", "sustained", "static"), p)
                    "threads" -> if (v != null) nonNegLong(v, p)
                    "labels" -> Json.arr(v, p).forEachIndexed { i, s -> Json.str(s, "$p[$i]") }
                }
                out[k] = v
            }
            return out
        }

        private fun enum(x: Any?, allowed: Set<String>, path: String): String {
            val s = Json.str(x, path)
            if (s !in allowed) throw JsonException(path, "must be one of ${allowed.sorted()}, got '$s'")
            return s
        }

        private fun nonNegLong(x: Any?, path: String): Long {
            val v = Json.long(x, path)
            if (v < 0) throw JsonException(path, "must not be negative")
            return v
        }

        private fun longOrNull(x: Any?, path: String): Long? = x?.let { Json.long(it, path) }

        private fun numOrNull(x: Any?, path: String, nonNegative: Boolean): Number? {
            if (x == null) return null
            if (!Json.isNumber(x)) throw JsonException(path, "expected a number or null, got ${Json.describe(x)}")
            if (nonNegative && Json.double(x, path) < 0) throw JsonException(path, "must not be negative")
            return x as Number
        }

        private fun optStr(m: Map<String, Any?>, key: String, path: String): String? =
            if (m.containsKey(key)) Json.str(m[key], "$path.$key") else null
    }
}

/** A `part1-rows-v1` row log: JSON Lines, blank lines and lines starting with `#` ignored. */
internal object RowLog {
    fun parse(lines: List<String>): List<Row> {
        val rows = ArrayList<Row>()
        lines.forEachIndexed { i, raw ->
            val line = com.itsaky.androidide.plugins.aiagentlocal.device.PyCompat.strip(raw)
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val n = i + 1
            rows.add(Row.parse(Json.parse(line, "line $n"), "line $n"))
        }
        return rows
    }
}
