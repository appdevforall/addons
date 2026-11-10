package com.itsaky.androidide.plugins.aiagentlocal.device.descriptor

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.JsonException
import com.itsaky.androidide.plugins.aiagentlocal.device.PyCompat
import com.itsaky.androidide.plugins.aiagentlocal.device.descriptor.PartLib.leaf
import com.itsaky.androidide.plugins.aiagentlocal.device.descriptor.PartLib.unk
import java.math.BigInteger
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Port of `descriptor-v02/generate.py` (function `generate`): builds a descriptor schema v0.2 document
 * from the three inputs of `part1-inputs-v1.md`: the static JSON (a `part1-static-v1` object), the row log
 * (`part1-rows-v1` lines, optional) and the database record (`part1-dbrecord-v1`, optional).
 * No device-specific constants. The result equals the Python result as JSON (key order is free, numbers
 * are equal as numbers).
 */
object DescriptorGenerator {

    const val GENERATOR = "generate.py 0.2"
    const val SCHEMA_VERSION = "0.2"
    const val DEFAULT_PEAK_TOLERANCE = 0.05

    private val STATIC_COND: Map<String, Any?> = mapOf("regime" to "static")
    private const val PIN_NO_CONDITIONS = "pin result recorded without its conditions"
    private val SANITY_MAP = listOf(
        "single_core_read" to "read_1core_gbs", "single_core_write" to "write_1core_gbs",
        "all_core_read" to "read_allcore_gbs", "gpu_read_alone" to "gpu_read_alone_gbs",
    )

    /**
     * Generates the descriptor. [rowLines] are the lines of the row log (blank lines and `#` lines are
     * skipped). [inputNames] go to `meta.generated_from`. Throws [JsonException] for an input that does not
     * match its format.
     */
    fun generate(
        static: JSONObject,
        rowLines: List<String>,
        dbRecord: JSONObject?,
        pluginVersion: String?,
        peakTolerance: Double = DEFAULT_PEAK_TOLERANCE,
        inputNames: List<String> = emptyList(),
    ): JSONObject {
        val tree = generateTree(
            Json.obj(Json.fromOrg(static, "static"), "static"),
            RowLog.parse(rowLines),
            dbRecord?.let { Json.obj(Json.fromOrg(it, "dbrecord"), "dbrecord") },
            pluginVersion, peakTolerance, inputNames,
        )
        return Json.toOrg(tree) as JSONObject
    }

    internal fun generateTree(
        static: Map<String, Any?>,
        rows: List<Row>,
        dbrec: Map<String, Any?>?,
        pluginVersion: String?,
        peakTolerance: Double,
        inputNames: List<String>,
    ): Map<String, Any?> {
        checkFormats(static, dbrec)
        val cx = Ctx(wordsToInt(static), rows, dbrec)
        val cores = buildCores(cx)
        val ident = buildIdentity(cx)
        val d = Obj()
        d["schema_version"] = SCHEMA_VERSION
        val clusters = buildClusters(cx, cores.list, cores.info)
        val threads = buildThreads(cx, peakTolerance)
        val notes = ArrayList<String>(cx.notes)
        if (cx.skipped.isNotEmpty()) {
            notes.add(
                "${cx.skipped.size} row(s) with status other than ok were ignored: " +
                    cx.skipped.joinToString(", ") { "${it.tag}=${it.status}" },
            )
        }
        d["meta"] = PartLib.obj("generator" to GENERATOR, "generated_from" to inputNames.toList(), "notes" to notes)
        d["run"] = buildRun(cx, pluginVersion, rows, ident)
        d["identity"] = ident
        buildEnvironment(cx)?.let { d["environment"] = it }
        d["cores"] = cores.list
        d["clusters"] = clusters
        d["threads"] = threads
        d["capabilities"] = buildCapabilities(cx)
        val bw = buildBandwidth(cx)
        if (bw.isNotEmpty()) d["bandwidth"] = bw
        buildGpu(cx)?.let { d["gpu"] = it }
        val (db, gh) = buildDatabase(cx)
        if (db != null) d["database"] = db
        if (gh != null) d["governor_hints"] = gh
        buildCaches(cx)?.let { d["caches"] = it }
        return d
    }

    // ------------------------------------------------------------------ input checks

    private fun checkFormats(static: Map<String, Any?>, dbrec: Map<String, Any?>?) {
        if (static["format"] != PartLib.STATIC_FORMAT) {
            throw JsonException("static.format", "static input: format must be ${PartLib.STATIC_FORMAT}")
        }
        if (dbrec == null) return
        if (dbrec["format"] != PartLib.DBRECORD_FORMAT) {
            throw JsonException("dbrecord.format", "db-record input: format must be ${PartLib.DBRECORD_FORMAT}")
        }
        val ix = dbrec["index"]
        val sha = (ix as? Map<*, *>)?.get("index_sha256")
        if (!(sha is String && Regex("[0-9a-f]{64}").matches(sha))) {
            throw JsonException(
                "dbrecord.index.index_sha256",
                "db-record input: index.index_sha256 (sha256 of the index file the record was taken from, 64 lower-case hex) is required",
            )
        }
        val m = dbrec["match"]
        if (!(m is Map<*, *> && m["result"] in listOf("exact", "soc_sibling", "none") && PyCompat.truthy(m["how_class"]))) {
            throw JsonException("dbrecord.match", "db-record input: match must carry result (exact|soc_sibling|none) and how_class")
        }
        val ms = dbrec["match_source"]
        if (!(ms is String && PyCompat.strip(ms).isNotEmpty())) {
            throw JsonException("dbrecord.match_source", "db-record input: match_source (who ran the match rule and on which identity values) is required text")
        }
        if (m["result"] != "none" && dbrec["record"] !is Map<*, *>) {
            throw JsonException("dbrecord.record", "db-record input: a match other than none needs the index record")
        }
    }

    /**
     * A copy of the static input whose `features.hwcap` and `features.hwcap2` are `BigInteger` (or null): the input
     * spells them as hex strings (see [word]). Mirrors `generate.words_to_int`. The input is not changed.
     */
    private fun wordsToInt(static: Map<String, Any?>): Map<String, Any?> {
        val f = static["features"] as? Map<*, *> ?: return static
        val out = LinkedHashMap(static)
        val feats = LinkedHashMap<String, Any?>()
        f.forEach { (k, v) -> feats[k as String] = v }
        feats["hwcap"] = word(f["hwcap"], "static.features.hwcap")
        feats["hwcap2"] = word(f["hwcap2"], "static.features.hwcap2")
        out["features"] = feats
        return out
    }

    // ------------------------------------------------------------------ context

    private class Ctx(val static: Map<String, Any?>, allRows: List<Row>, val dbrec: Map<String, Any?>?) {
        val rows: List<Row> = allRows.filter { it.status == "ok" }
        val skipped: List<Row> = allRows.filter { it.status != "ok" }
        val record: Map<String, Any?>? = (dbrec?.get("record") as? Map<*, *>)?.let { Json.obj(it, "dbrecord.record") }
        val unavail: Map<String, Any?> = orEmpty(static["unavailable"], "static.unavailable")
        val src: Map<String, Any?> = orEmpty(static["sources"], "static.sources")
        val haveRows: Boolean = allRows.isNotEmpty()
        val notes = ArrayList<String>()

        /** `unavailable[key]` when it is a non-empty text, else null. */
        fun why(key: String): String? {
            val v = unavail[key]
            return if (PyCompat.truthy(v)) Json.str(v, "static.unavailable.$key") else null
        }

        fun source(key: String): String? = src[key]?.let { Json.str(it, "static.sources.$key") }

        /** A static-read leaf, or unk with the reason the static file gives. */
        fun sleaf(value: Any?, key: String, srcKey: String, unit: String? = null, source: String? = null): Obj {
            if (value == null) return unk(why(key) ?: "not provided by the static read", unit = unit)
            return leaf(value, "static_read", STATIC_COND, unit = unit, source = if (!source.isNullOrEmpty()) source else source(srcKey))
        }

        fun noRow(what: String): String =
            if (!haveRows) "T0 descriptor: no measurement rows were supplied ($what)" else "the row log has no usable $what row"

        fun rowsWith(prefix: String): List<Row> = rows.filter { it.tag.startsWith(prefix) }
    }

    private fun orEmpty(x: Any?, path: String): Map<String, Any?> = if (PyCompat.truthy(x)) Json.obj(x, path) else emptyMap()

    // ------------------------------------------------------------------ leaf helpers

    private fun rowLabels(row: Row): List<String> {
        val labels = ArrayList(row.labels)
        if ("unpinned" !in labels && (row.pin.result == "unpinned" || (row.cpuSet.isEmpty() && row.threads > 0))) labels.add("unpinned")
        return labels
    }

    private fun rowConditions(row: Row): Obj {
        val c = Obj(row.conditions)
        if (c["cpu_set"] == null) c["cpu_set"] = PartLib.cpuSetStr(row.cpuSet).ifEmpty { null }
        if (c["pin"] == null) c["pin"] = row.pin.result
        if (c["buffer_mib"] == null) c["buffer_mib"] = row.bufferMib
        if (c["gate_basis"] == null) c["gate_basis"] = row.gateBasis
        if (c["regime"] == null) c["regime"] = if (row.singleShot) "single_shot" else "median_of_repeats"
        c["threads"] = row.threads
        c["labels"] = rowLabels(row)
        for (k in listOf("thermal_status", "battery_pct")) if (!c.containsKey(k)) c[k] = null
        if (row.origin == "measured_shell" && !PyCompat.truthy(c["kit"])) c["kit"] = row.engineVersion
        return c
    }

    /**
     * Conditions of a leaf computed from several rows: the union of their labels, comparable false when any
     * row is not comparable, thermal status and battery when the rows agree, gate basis and kit joined.
     */
    private fun unionConditions(rows: List<Row>, extra: Map<String, Any?> = emptyMap()): Obj {
        val conds = rows.map { rowConditions(it) }
        val out = Obj()
        val labels = ArrayList<String>()
        for (c in conds) {
            @Suppress("UNCHECKED_CAST")
            for (lb in c["labels"] as List<String>) if (lb !in labels) labels.add(lb)
        }
        out["labels"] = labels
        if (conds.any { it["comparable"] == false }) out["comparable"] = false
        for (key in listOf("thermal_status", "battery_pct", "gate_basis", "kit")) {
            val vals = ArrayList<Any?>()
            for (c in conds) {
                val v = c[key]
                if (v != null && !PyCompat.contains(vals, v)) vals.add(v)
            }
            if (key == "gate_basis" || key == "kit") {
                if (vals.isNotEmpty()) out[key] = vals.joinToString("; ") { PyCompat.str(it) } else if (key == "gate_basis") out[key] = null
            } else {
                out[key] = if (vals.size == 1) vals[0] else null
            }
        }
        out.putAll(extra)
        return out
    }

    private fun rowLeaf(row: Row, value: Any?, unit: String? = null): Obj {
        var src = "row ${row.tag}"
        if (!row.kitTag.isNullOrEmpty()) src += " (kit row ${row.kitTag})"
        return leaf(value, row.origin, rowConditions(row), unit = unit, source = src)
    }

    // ------------------------------------------------------------------ sections

    private fun buildIdentity(cx: Ctx): Obj {
        val i = orEmpty(cx.static["identity"], "static.identity")
        val out = Obj()
        for (k in listOf("platform", "board", "device", "hardware", "soc_model", "sdk_int", "abi_list", "build_fingerprint")) {
            out[k] = cx.sleaf(i[k], "identity.$k", "identity")
        }
        return out
    }

    private fun buildEnvironment(cx: Ctx): Obj? {
        val e = cx.static["environment"]
        if (!PyCompat.truthy(e)) return null
        val env = Json.obj(e, "static.environment")
        val out = Obj()
        for (k in listOf("thermal_status", "thermal_headroom", "battery_pct", "charging", "display_refresh_hz", "display_px", "screen_on", "foreground_cpuset")) {
            out[k] = cx.sleaf(env[k], "environment.$k", "environment")
        }
        return out
    }

    private fun typeNameLeaf(cx: Ctx, part: Any?): Obj {
        if (part == null) return unk("no core part number was read for this CPU")
        val rec = cx.record
        var notes: List<Any?> = emptyList()
        if (rec != null && rec.isNotEmpty()) {
            val same = orEmptyList(rec["cores"]).map { Json.obj(it, "record.cores[]") }.filter { PyCompat.eq(it["part"], part) }
            val typed = same.filter { PyCompat.truthy(it["type"]) }.map { it["type"] }
            if (typed.isNotEmpty()) {
                return leaf(typed[0], "database", mapOf("regime" to "static"), source = "database entry ${PyCompat.str(rec["key"])}")
            }
            notes = same.filter { PyCompat.truthy(it["type_note"]) }.map { it["type_note"] }
        }
        var note = "named from the MIDR part per plan 3.7"
        if (notes.isNotEmpty()) note += "; database note: ${PyCompat.str(notes[0])}"
        return leaf("unknown-${PyCompat.str(part)}", "inferred", emptyMap(), source = "part number only; no database name", note = note)
    }

    private fun orEmptyList(x: Any?): List<Any?> = if (PyCompat.truthy(x)) Json.arr(x, "list") else emptyList()

    /**
     * Keys the rule R3 needs on a measured pin leaf that `pin_conditions` lacks. measured_app: `gate_basis`
     * (not null) and the key `thermal_status` (its value may be null); `battery_pct` and `labels` default.
     * measured_shell: `kit`. Any other measured origin: a non-empty `pin_conditions`.
     */
    private fun missingPinConditions(origin: String, cond: Map<String, Any?>): Boolean = when (origin) {
        "measured_app" -> cond["gate_basis"] == null || !cond.containsKey("thermal_status")
        "measured_shell" -> !PyCompat.truthy(cond["kit"])
        else -> cond.isEmpty() && origin in PartLib.MEASURED
    }

    private fun pinLeaves(cx: Ctx, cpu: Map<String, Any?>): Pair<Obj, Obj> {
        val p = cpu["pin"]
        if (!PyCompat.truthy(p)) {
            val why = cx.why("cpus.pin.${PyCompat.str(cpu["index"])}") ?: "no pin result for this CPU in the static input"
            return unk(why) to unk(why)
        }
        val pin = Json.obj(p, "static.cpus[].pin")
        val cond = Obj(orEmpty(cx.static["pin_conditions"], "static.pin_conditions"))
        val origin: String = if (cx.static.containsKey("pin_origin")) {
            cx.static["pin_origin"]?.let { Json.str(it, "static.pin_origin") } ?: "None"
        } else {
            "measured_app"
        }
        val src = cx.source("cpus")
        if (missingPinConditions(origin, cond)) {
            return unk(PIN_NO_CONDITIONS, source = src) to unk(PIN_NO_CONDITIONS, source = src)
        }
        cond["cpu_set"] = PyCompat.str(cpu["index"])
        cond["pin"] = pin["result"]
        if (!cond.containsKey("battery_pct")) cond["battery_pct"] = null
        if (!cond.containsKey("labels")) cond["labels"] = emptyList<Any?>()
        val att = pin["attempts"]
        return leaf(pin["result"], origin, cond, source = src) to
            (if (att != null) leaf(att, origin, cond, source = src) else unk("attempt count not recorded"))
    }

    private class Cores(val list: List<Obj>, val info: PartLib.ClusterInfo)

    private fun buildCores(cx: Ctx): Cores {
        val cpus = Json.arr(cx.static["cpus"], "static.cpus").mapIndexed { i, c -> Json.obj(c, "static.cpus[$i]") }
            .sortedBy { Json.long(it["index"], "static.cpus[].index") }
        val related = LinkedHashMap<Long, List<Long>?>()
        for (c in cpus) {
            val rel = c["related_cpus"]
            related[Json.long(c["index"], "static.cpus[].index")] =
                if (PyCompat.truthy(rel)) Json.arr(rel, "static.cpus[].related_cpus").map { Json.long(it, "static.cpus[].related_cpus[]") } else null
        }
        val info = PartLib.clusterInfo(related)
        val srcCpus = cx.source("cpus")
        val cores = ArrayList<Obj>()
        for (c in cpus) {
            val idx = Json.long(c["index"], "static.cpus[].index")
            val (pin, att) = pinLeaves(cx, c)
            val h = info.how[idx]!!
            val cl = when (h) {
                "listed" -> leaf(info.clusterOf[idx], "static_read", STATIC_COND, source = srcCpus)
                "joined" -> leaf(
                    info.clusterOf[idx], "inferred", emptyMap(), source = "related_cpus of this CPU was not read",
                    note = "joined to the cluster whose other members list this CPU",
                )
                else -> leaf(
                    info.clusterOf[idx], "inferred", emptyMap(), source = "related_cpus of this CPU was not read",
                    note = "singleton cluster: this CPU's own list was not read and no other CPU lists it",
                )
            }
            val entry = PartLib.obj(
                "index" to idx,
                "cluster" to cl,
                "part" to cx.sleaf(c["part"], "cpus.part", "cpus"),
                "midr" to cx.sleaf(c["midr"], "cpus.midr", "cpus"),
                "type_name" to typeNameLeaf(cx, c["part"]),
                "max_khz" to cx.sleaf(c["max_khz"], "cpus.max_khz", "cpus", unit = "kHz"),
                "capacity" to cx.sleaf(c["capacity"], "cpus.capacity", "cpus", source = c["capacity_source"]?.let { Json.str(it, "static.cpus[].capacity_source") }),
                "pin" to pin,
                "pin_attempts" to att,
            )
            if (c["implementer"] != null) entry["implementer"] = cx.sleaf(c["implementer"], "cpus.implementer", "cpus")
            cores.add(entry)
        }
        return Cores(cores, info)
    }

    private fun gbs(r: Row): Double = PyCompat.num(r.gbsMedian)

    private fun bestRow(rows: List<Row>): Row? = rows.maxByOrNull { gbs(it) }

    private val C2 = Regex("C2-read-k([0-9]+)")

    /** The cluster id named by a `C2-read-k<id>` tag (the cluster comes from the tag, not from the CPU list). */
    private fun c2Cluster(row: Row): Long? = C2.matchEntire(row.tag)?.let { it.groupValues[1].toLongOrNull() ?: Long.MAX_VALUE }

    private fun buildClusters(cx: Ctx, cores: List<Obj>, info: PartLib.ClusterInfo): List<Obj> {
        val byIdx = cores.associateBy { it["index"] as Long }
        val staticByIdx = Json.arr(cx.static["cpus"], "static.cpus").associateBy { Json.long(Json.obj(it, "c")["index"], "c.index") }
        val members = info.members
        val n = members.size
        val clusters = ArrayList<Obj>()
        val reads = HashMap<Int, Row?>().also { m -> for (k in 0 until n) m[k] = null }
        for (r in cx.rowsWith("C2-read-k")) {
            val k = c2Cluster(r)
            if (k == null || k >= n) {
                cx.notes.add("row ${r.tag} names a cluster that does not exist in this descriptor and was ignored")
            } else if (reads[k.toInt()] == null || gbs(r) > gbs(reads[k.toInt()]!!)) {
                reads[k.toInt()] = r
            }
        }
        val allMeasured = (0 until n).all { reads[it] != null }

        // rank by measured read: fastest first; ties by higher max kHz then lower id (recorded in note)
        val ranks = HashMap<Int, Int>()
        var tie = false
        if (allMeasured && n > 1) {
            fun mx(k: Int): Double = members[k].maxOf { c ->
                val v = Json.obj(staticByIdx[c], "static.cpus[]")["max_khz"]
                if (PyCompat.truthy(v)) PyCompat.num(v) else 0.0
            }
            val order = (0 until n).sortedWith(compareBy<Int>({ -gbs(reads[it]!!) }, { -mx(it) }, { it }))
            tie = order.map { gbs(reads[it]!!) }.toSet().size != order.size
            order.forEachIndexed { pos, k -> ranks[k] = pos + 1 }
        }

        for ((k, mem) in members.withIndex()) {
            val rd = reads[k]
            val clCores = mem.map { byIdx[it]!! }

            fun common(field: String, unit: String? = null): Obj {
                val vals = clCores.map { @Suppress("UNCHECKED_CAST") (it[field] as Obj) }
                if (vals.any { it["origin"] == "unk" }) return unk("one or more member CPUs have no $field")
                val distinct = ArrayList<Any?>()
                for (v in vals) if (!PyCompat.contains(distinct, v["value"])) distinct.add(v["value"])
                if (distinct.size == 1) return Obj(vals[0])
                val value: Any? = if (field != "type_name") {
                    vals.map { it["value"] }.reduce { a, b -> if (greater(b, a)) b else a }
                } else {
                    "mixed:" + vals.map { it["value"] as String }.toSortedSet().joinToString(",")
                }
                return leaf(value, "inferred", emptyMap(), unit = unit, note = "member CPUs differ; maximum or union shown")
            }

            val pins = clCores.map { @Suppress("UNCHECKED_CAST") (it["pin"] as Obj) }
            val pall: Obj = if (pins.all { it["origin"] != "unk" }) {
                @Suppress("UNCHECKED_CAST")
                val pc = Obj(pins[0]["conditions"] as Map<String, Any?>)
                pc["cpu_set"] = PartLib.cpuSetStr(mem)
                leaf(pins.all { it["value"] == "ok" }, pins[0]["origin"] as String, pc, source = pins[0]["source"] as String?)
            } else {
                unk("pin result missing for a member CPU")
            }
            val pinned = clCores.filter { (it["pin"] as Obj)["value"] == "ok" }.map { it["index"] as Long }
            val mcpu: Long? = if (rd != null && rd.cpuSet.isNotEmpty()) rd.cpuSet[0] else null
            val base: Long? = mcpu ?: pinned.firstOrNull()
            val others = pinned.filter { it != base }
            val sub = when {
                mem.size == 1 -> unk("single-CPU cluster: there is no other CPU to substitute")
                pinned.isEmpty() -> unk("no pin result shows a pinnable CPU in this cluster")
                others.isEmpty() -> unk("no second pinnable CPU in this cluster")
                else -> leaf(
                    others[0], "inferred", emptyMap(), source = "pin results of the member CPUs",
                    note = "first other pinnable member; used only if the measured CPU will not pin",
                )
            }
            val read: Obj
            val mc: Obj
            if (rd != null) {
                read = rowLeaf(rd, rd.gbsMedian, "GB/s")
                mc = if (mcpu != null) rowLeaf(rd, mcpu) else unk("the single-core row was unpinned and its CPU was not recorded")
            } else {
                val reason = cx.noRow("single-core read (C2-read) for this cluster")
                read = unk(reason, unit = "GB/s")
                mc = unk(reason)
            }
            val rank: Obj = if (n == 1) {
                leaf(1L, "inferred", emptyMap(), source = "only one cluster")
            } else if (ranks.containsKey(k)) {
                val origin = if (reads.values.any { it!!.origin == "measured_shell" }) "measured_shell" else "measured_app"
                var note = "ranked by measured single-core read of each cluster"
                if (tie) note += "; equal reads were ordered by max kHz, then lower id"
                leaf(
                    ranks[k]!!, origin,
                    unionConditions(
                        reads.keys.sorted().map { reads[it]!! },
                        mapOf("regime" to ((rowConditions(reads[k]!!)["regime"]?.takeIf { PyCompat.truthy(it) }) ?: "single_shot"), "note" to note),
                    ),
                    source = "rows " + reads.values.map { it!!.tag }.sorted().joinToString(", "),
                )
            } else {
                val have = reads.values.count { it != null }
                unk("rank needs a measured single-core read for every cluster; $have of $n clusters have one")
            }
            val membersLeaf: Obj = if (mem.all { info.how[it] == "listed" }) {
                leaf(mem, "static_read", STATIC_COND, source = cx.source("cpus"))
            } else {
                val joined = mem.filter { info.how[it] == "joined" }
                val alone = mem.filter { info.how[it] == "singleton" }
                val parts = ArrayList<String>()
                if (joined.isNotEmpty()) parts.add("own related_cpus list not read for CPU(s) ${PartLib.cpuSetStr(joined)}; placed by the lists of the others")
                if (alone.isNotEmpty()) {
                    parts.add("own related_cpus list not read for CPU(s) ${PartLib.cpuSetStr(alone)}, and no other CPU lists them: a singleton cluster, placed alone")
                }
                leaf(mem, "inferred", emptyMap(), source = "related_cpus lists of the member CPUs", note = parts.joinToString("; "))
            }
            clusters.add(
                PartLib.obj(
                    "id" to k,
                    "members" to membersLeaf,
                    "type_name" to common("type_name"),
                    "max_khz" to common("max_khz", "kHz"),
                    "capacity" to common("capacity"),
                    "pinnable_all" to pall,
                    "measured_cpu" to mc,
                    "read_1core_gbs" to read,
                    "rank" to rank,
                    "substitute_cpu" to sub,
                ),
            )
        }
        return clusters
    }

    private fun greater(a: Any?, b: Any?): Boolean =
        if (Json.isNumber(a) && Json.isNumber(b)) PyCompat.num(a) > PyCompat.num(b) else (a as String) > (b as String)

    private fun buildThreads(cx: Ctx, tol: Double): Obj {
        val r1 = bestRow(cx.rowsWith("C2-read-k").filter { c2Cluster(it) != null })
        val r2 = bestRow(cx.rowsWith("C3-read-2fast"))
        val ra = bestRow(cx.rowsWith("C4-read-all"))

        fun gb(r: Row?, what: String): Obj = if (r == null) unk(cx.noRow(what), unit = "GB/s") else rowLeaf(r, r.gbsMedian, "GB/s")
        fun pinned(r: Row?) = r != null && r.pin.result in listOf("ok", "substituted") && r.cpuSet.isNotEmpty()

        val fs: Obj = when {
            pinned(r2) -> rowLeaf(r2!!, r2.cpuSet)
            pinned(r1) -> rowLeaf(r1!!, r1.cpuSet)
            else -> unk(cx.noRow("pinned read (C2/C3)") + "; no pinnable set known")
        }
        val cand = listOfNotNull(r1, r2, ra).filter { it.threads >= 1 }
        val peak: Obj = if (cand.size >= 2) {
            val best = cand.maxOf { gbs(it) }
            val ok = cand.filter { gbs(it) >= best * (1.0 - tol) }.map { it.threads }.sorted()
            leaf(
                ok[0], "inferred", unionConditions(cand),
                source = "rows " + cand.joinToString(", ") { it.tag },
                note = "smallest of the measured thread counts within ${PyCompat.format1(tol * 100)}% of the best read; only these counts were measured",
            )
        } else {
            unk("peak needs at least two of the 1-, 2- and all-core rows")
        }
        return PartLib.obj(
            "fastest_pinnable_set" to fs,
            "read_1core_gbs" to gb(r1, "single-core read (C2-read)"),
            "read_2core_gbs" to gb(r2, "two-core read (C3-read-2fast)"),
            "read_allcore_gbs" to gb(ra, "all-core read (C4-read-all)"),
            "peak_threads" to peak,
        )
    }

    /** `capabilities.features`: the HWCAP and HWCAP2 words decoded with the arm64 hwcap.h name table. */
    private fun buildFeatures(cx: Ctx, f: Map<String, Any?>): Obj {
        val hw = f["hwcap"]
        val hw2 = f["hwcap2"]
        val abis = orEmpty(cx.static["identity"], "static.identity")["abi_list"]
        if (hw == null) return unk(cx.why("features.names") ?: cx.why("features.hwcap") ?: "the HWCAP word was not read")
        if (abis != null) {
            val has = when (abis) {
                is List<*> -> abis.any { it == "arm64-v8a" }
                is String -> abis.contains("arm64-v8a")
                else -> throw JsonException("static.identity.abi_list", "expected a list, got ${Json.describe(abis)}")
            }
            if (!has) return unk("this phone does not list arm64-v8a: the arm64 HWCAP name table does not apply")
        }
        val (names, unnamed) = PartLib.decodeHwcap(hw as BigInteger, (hw2 as BigInteger?) ?: BigInteger.ZERO)
        val what = cx.source("features")?.takeIf { it.isNotEmpty() } ?: "HWCAP words"
        val src = "decoded by generate.py (arm64 hwcap.h names) from: $what"
        val notes = ArrayList<String>()
        if (hw2 == null) notes.add("HWCAP2 was not read; only HWCAP is decoded")
        if (unnamed.isNotEmpty()) notes.add("set bits with no name in the table: ${unnamed.joinToString(", ")}")
        return leaf(names, "static_read", STATIC_COND, source = src, note = notes.joinToString("; ").ifEmpty { null })
    }

    private val HEX_WORD = Regex("0x[0-9a-fA-F]{1,16}")
    private val MAX_EXACT: BigInteger = BigInteger.ONE.shiftLeft(53)

    /**
     * A HWCAP/HWCAP2 word of the static input (`part1lib.parse_word`): null, a hex string `0x` + 1 to 16 hex
     * digits, or an integer from 0 to 2^53-1. Anything else is a [JsonException] naming [path].
     */
    internal fun word(x: Any?, path: String): BigInteger? = when (x) {
        null -> null
        is Long -> when {
            x < 0 -> throw JsonException(path, "a word cannot be negative")
            BigInteger.valueOf(x) >= MAX_EXACT -> throw JsonException(path, "an integer of 2^53 or more is not allowed; write the word as a hex string")
            else -> BigInteger.valueOf(x)
        }
        is String ->
            if (HEX_WORD.matches(x)) BigInteger(x.substring(2), 16) else throw JsonException(path, "a word must be a hex string 0x followed by 1 to 16 hex digits")
        is BigInteger -> throw JsonException(path, "an integer of 2^53 or more is not allowed; write the word as a hex string")
        else -> throw JsonException(path, "expected a hex string such as 0x119fff, got ${Json.describe(x)}")
    }

    /** The JSON value of a decoded word in the descriptor: an integer below 2^53, else a lower-case hex string (`part1lib.word_json`). */
    private fun wordJson(x: Any?): Any? = (x as BigInteger?)?.let { if (it.bitLength() <= 53) it.toLong() else "0x" + it.toString(16) }

    private fun buildCapabilities(cx: Ctx): Obj {
        val f = orEmpty(cx.static["features"], "static.features")
        val vk = cx.static["vulkan"]
        val out = Obj()
        out["hwcap"] = cx.sleaf(wordJson(f["hwcap"]), "features.hwcap", "features")
        out["hwcap2"] = cx.sleaf(wordJson(f["hwcap2"]), "features.hwcap2", "features")
        out["features"] = buildFeatures(cx, f)
        out["page_size"] = cx.sleaf(f["page_size"], "features.page_size", "features", unit = "B")
        out["ram_total_kb"] = cx.sleaf(orEmpty(cx.static["memory"], "static.memory")["memtotal_kb"], "memory.memtotal_kb", "memory", unit = "kB")
        out["opencl_loadable"] = cx.sleaf(cx.static["opencl_loadable"], "opencl_loadable", "opencl")
        out["nnapi_devices"] = cx.sleaf(cx.static["nnapi_devices"], "nnapi_devices", "nnapi")
        if (vk == null) {
            out["vulkan"] = PartLib.obj("present" to unk(cx.why("vulkan") ?: "Vulkan was not probed"))
        } else {
            val vkm = Json.obj(vk, "static.vulkan")
            val v = PartLib.obj("present" to cx.sleaf(PyCompat.truthy(vkm["present"]), "vulkan.present", "vulkan"))
            if (PyCompat.truthy(vkm["present"])) {
                for (k in listOf("device_name", "api_version", "compute_queue", "shader_float16", "int_dot")) {
                    v[k] = cx.sleaf(vkm[k], "vulkan.$k", "vulkan")
                }
            }
            out["vulkan"] = v
        }
        return out
    }

    private fun buildBandwidth(cx: Ctx): Obj {
        val out = Obj()
        for ((key, prefix) in listOf("write_1core_gbs" to "C5-write-k", "copy_1core_gbs" to "C6-copy-k", "sweep_largest_gbs" to "C1-sweep-k")) {
            val r = bestRow(cx.rowsWith(prefix))
            if (r != null) out[key] = rowLeaf(r, r.gbsMedian, "GB/s")
        }
        return out
    }

    private fun buildGpu(cx: Ctx): Obj? {
        val vk = orEmpty(cx.static["vulkan"], "static.vulkan")["present"]
        val ra = bestRow(cx.rowsWith("C7-gpu-alone"))
        val rc = bestRow(cx.rowsWith("C8-gpu-with-cpustream"))
        if (ra == null && rc == null && !PyCompat.truthy(vk)) return null

        fun one(r: Row?, what: String): Obj = when {
            r != null -> rowLeaf(r, r.gbsMedian, "GB/s")
            vk == false -> unk("no Vulkan compute device: GPU rows are unsupported", unit = "GB/s")
            else -> unk(cx.noRow(what), unit = "GB/s")
        }
        return PartLib.obj(
            "read_alone_gbs" to one(ra, "GPU read alone (C7)"),
            "read_with_cpu_stream_gbs" to one(rc, "GPU read with CPU stream (C8)"),
        )
    }

    private fun dbSource(dbrec: Map<String, Any?>): String {
        val ix = orEmpty(dbrec["index"], "dbrecord.index")
        var s = "database index record"
        if (PyCompat.truthy(ix["index_version"])) {
            s += " (${PyCompat.str(ix["index_version"])}"
            if (PyCompat.truthy(ix["snapshot_date"])) s += ", snapshot ${PyCompat.str(ix["snapshot_date"])}"
            s += ", index sha256 ${PyCompat.str(ix["index_sha256"])})"
        }
        return s
    }

    /** `database` block and `governor_hints` from the match result and the index record. Advisory only. */
    private fun buildDatabase(cx: Ctx): Pair<Obj?, Obj?> {
        val d = cx.dbrec
        if (!PyCompat.truthy(d)) return null to null
        d!!
        val m = Json.obj(d["match"], "dbrecord.match")
        val src = PyCompat.strip(d["match_source"] as String) + "; " + dbSource(d)
        val db = PartLib.obj(
            "match" to leaf(m["result"], "database", STATIC_COND, source = src, note = m["how"]?.let { PyCompat.str(it) }),
            "how_class" to leaf(m["how_class"], "database", STATIC_COND, source = src),
        )
        val rec = cx.record
        if (rec == null || rec.isEmpty()) {
            db["entry"] = unk("no database record matched this device (match none)")
            return db to null
        }
        val exact = m["result"] == "exact"
        val extra = if (exact) "" else "; from a SoC sibling entry, not from this device's own entry"
        val rsrc = "database entry ${PyCompat.str(rec["key"])}"
        db["entry"] = leaf(rec["key"], "database", STATIC_COND, source = rsrc)
        val cc = rec["core_control"]?.takeIf { PyCompat.truthy(it) }?.let { Json.obj(it, "record.core_control") }
        if (cc != null && PyCompat.truthy(cc["mechanism"])) {
            db["core_control"] = leaf(
                cc["mechanism"], "database", STATIC_COND, source = rsrc,
                note = "wake_then_pin_needed: ${PyCompat.str(cc["wake_then_pin_needed"]).lowercase()}$extra",
            )
        } else {
            db["core_control"] = unk("the database entry has no core_control record")
        }
        val sanity = Obj()
        val measured = orEmpty(rec["measured"], "record.measured")
        for ((k, name) in SANITY_MAP) {
            val mvRaw = measured[k]
            if (PyCompat.truthy(mvRaw)) {
                val mv = Json.obj(mvRaw, "record.measured.$k")
                if (mv["gbs"] != null) {
                    sanity[name] = leaf(
                        mv["gbs"], "database", STATIC_COND, unit = "GB/s", source = rsrc,
                        note = "database value from kit ${PyCompat.str(mv["kit"])}; a sanity range only, never replaces a measurement$extra",
                    )
                }
            }
        }
        if (sanity.isNotEmpty()) db["sanity"] = sanity
        val gh = Obj()
        val gk = rec["governor_kind"]
        if (PyCompat.truthy(gk)) {
            if (rec["governor_kind_basis"] == "predicted") {
                gh["mif_governor_kind"] = leaf(
                    gk, "inferred", STATIC_COND, source = rsrc,
                    note = "the database's prediction for this governor kind, not a reading$extra",
                )
            } else {
                gh["mif_governor_kind"] = leaf(gk, "database", STATIC_COND, source = rsrc, note = extra.substring(minOf(2, extra.length)).ifEmpty { null })
            }
        }
        return db to gh.ifEmpty { null }
    }

    private fun tierOf(rows: List<Row>): String {
        val tags = rows.map { it.tag }
        return when {
            tags.any { it.startsWith("S1-") || it.startsWith("S2-") } -> "T3"
            tags.any { t -> listOf("C1-", "C5-", "C6-", "C8-", "C9-", "PC-").any { t.startsWith(it) } } -> "T2"
            tags.isNotEmpty() -> "T1"
            else -> "T0"
        }
    }

    /**
     * Plan 3.1: lower-case hex sha256 of `<Build.FINGERPRINT>|<engine id>|<schema version>`, engine id =
     * `<engine name>/<version>`; unk if a part is unk or the rows name several engines.
     */
    private fun deviceKey(ident: Obj, eng: Obj, engines: List<String>): Obj {
        val fp = ident["build_fingerprint"] as Obj?
        val missing = ArrayList<String>()
        if (fp == null || fp["origin"] == "unk") {
            missing.add("Build.FINGERPRINT (${(fp?.get("reason") as String?)?.takeIf { it.isNotEmpty() } ?: "not read"})")
        }
        if (eng["origin"] == "unk") {
            missing.add("engine id (${PyCompat.str(eng["reason"])})")
        } else if (engines.size != 1) {
            missing.add("a single engine id (the rows name ${engines.size} engines: ${engines.joinToString(", ")})")
        }
        if (missing.isNotEmpty()) {
            return unk("the device key needs Build.FINGERPRINT, the engine id and the schema version; missing: " + missing.joinToString("; "))
        }
        val text = "${PyCompat.str(fp!!["value"])}|${engines[0]}|$SCHEMA_VERSION"
        val hex = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return leaf(hex, "inferred", emptyMap(), source = "sha256 of Build.FINGERPRINT|engine id|schema version (plan 3.1)")
    }

    private fun buildRun(cx: Ctx, pluginVersion: String?, rows: List<Row>, ident: Obj): Obj {
        val tier = tierOf(rows)
        var noteT = "derived from the tags present in the row log"
        if (rows.any { it.origin == "measured_shell" }) noteT += "; rows came from the shell kit, so this is a tier-equivalent, not an in-app tier"
        val have = rows.mapNotNull { it.durationS }
        val dur: Obj = if (have.isNotEmpty()) {
            val missing = rows.filter { it.durationS == null }.map { it.tag }
            var note = "row work time only; rests and cool-down waits are not included"
            if (missing.isNotEmpty()) {
                note += "; ${missing.size} of ${rows.size} rows have no duration and are not counted (${missing.joinToString(", ")})"
            }
            leaf(PyCompat.round1(PyCompat.sum(have)), "inferred", emptyMap(), unit = "s", source = "sum of row duration_s", note = note)
        } else if (cx.static["duration_s"] != null && rows.isEmpty()) {
            leaf(cx.static["duration_s"], "static_read", STATIC_COND, unit = "s", source = cx.source("run"))
        } else {
            unk(if (rows.isNotEmpty()) "no row has a duration" else "no timing was recorded for the static read", unit = "s")
        }
        val engines = rows.map { it.engineVersion }.toSortedSet().toList()
        val eng: Obj = if (engines.isNotEmpty()) {
            leaf(engines.joinToString(", "), "inferred", emptyMap(), source = "row log engine_version (engine id <engine name>/<version>)")
        } else {
            unk("no measurement engine ran (T0 only)")
        }
        val pv: String? = if (!pluginVersion.isNullOrEmpty()) pluginVersion else cx.static["plugin_version"]?.takeIf { PyCompat.truthy(it) }?.let { Json.str(it, "static.plugin_version") }
        val plug = if (pv != null) {
            leaf(pv, "static_read", STATIC_COND, source = "caller")
        } else {
            unk("this descriptor was not produced by the plugin (built from recorded input files)")
        }
        val match: Obj = if (PyCompat.truthy(cx.dbrec)) {
            val d = cx.dbrec!!
            leaf(
                Json.obj(d["match"], "dbrecord.match")["result"], "database", STATIC_COND,
                source = PyCompat.strip(d["match_source"] as String) + "; " + dbSource(d),
            )
        } else {
            unk("the database match was not run (no database input supplied)")
        }
        return PartLib.obj(
            "tier" to leaf(tier, "inferred", emptyMap(), source = noteT),
            "duration_s" to dur,
            "plugin_version" to plug,
            "engine" to eng,
            "database_match" to match,
            "device_key" to deviceKey(ident, eng, engines),
        )
    }

    private fun buildCaches(cx: Ctx): Obj? {
        val lb = orEmpty(cx.static["features"], "static.features")["dcache_line_bytes"] ?: return null
        return PartLib.obj("line_bytes" to cx.sleaf(lb, "features.dcache_line_bytes", "caches", unit = "B"))
    }
}
