package com.itsaky.androidide.plugins.aiagentlocal.device.descriptor

import java.math.BigInteger

internal typealias Obj = LinkedHashMap<String, Any?>

/** Port of `descriptor-v02/part1lib.py`: leaves, clusters, HWCAP names. */
internal object PartLib {

    const val STATIC_FORMAT = "part1-static-v1"
    const val ROWS_FORMAT = "part1-rows-v1"
    const val DBRECORD_FORMAT = "part1-dbrecord-v1"
    const val PINPROBE_FORMAT = "part1-pinprobe-v1"

    val ORIGINS = listOf("measured_app", "measured_shell", "static_read", "database", "inferred", "unk")
    val MEASURED = listOf("measured_app", "measured_shell")

    /** Keys a conditions object may carry (descriptor schema and row schema share them). */
    val CONDITION_KEYS = listOf(
        "thermal_status", "thermal_headroom", "battery_pct", "charging",
        "display_refresh_hz", "display_px", "screen_on", "foreground_cpuset",
        "gate_basis", "buffer_mib", "cpu_set", "pin", "start_temp_c",
        "memavail_mib", "memtotal_mib", "regime", "kit", "note",
        "threads", "labels", "comparable",
    )

    fun obj(vararg pairs: Pair<String, Any?>): Obj = LinkedHashMap<String, Any?>().also { m -> pairs.forEach { m[it.first] = it.second } }

    /** Builds a v0.2 leaf. A leaf with origin `unk` must carry value null and a reason. */
    fun leaf(
        value: Any?,
        origin: String,
        conditions: Map<String, Any?>? = null,
        unit: String? = null,
        source: String? = null,
        ref: String? = null,
        reason: String? = null,
        note: String? = null,
    ): Obj {
        require(origin in ORIGINS) { "bad origin '$origin'" }
        val out = obj("value" to value, "origin" to origin, "conditions" to Obj(conditions ?: emptyMap()))
        if (unit != null) out["unit"] = unit
        if (source != null) out["source"] = source
        if (ref != null) out["ref"] = ref
        if (reason != null) out["reason"] = reason
        if (note != null) out["note"] = note
        return out
    }

    fun unk(reason: String, unit: String? = null, source: String? = null): Obj {
        require(reason.isNotEmpty()) { "an unk leaf needs a reason" }
        return leaf(null, "unk", reason = reason, unit = unit, source = source)
    }

    /**
     * The one ranking of clusters by their measured single-core read, used by the descriptor generator and by the T1 row planner so
     * that both agree: value descending, then higher max kHz, then lower id.
     */
    fun rankOrder(ids: List<Int>, value: (Int) -> Double, maxKhz: (Int) -> Double): List<Int> =
        ids.sortedWith(compareBy<Int>({ -value(it) }, { -maxKhz(it) }, { it }))

    fun cpuSetStr(cpus: List<Long>): String = cpus.joinToString("+")

    class ClusterInfo(val clusterOf: Map<Long, Int>, val members: List<List<Long>>, val how: Map<Long, String>)

    /**
     * Clusters are the connected groups over every CPU's cpufreq related list; a CPU whose own list is
     * unreadable (null or empty) joins the cluster whose other members list it, else it is a singleton.
     * Ids are 0..N-1 in order of each cluster's lowest CPU index. [relatedByCpu] keys are the CPUs.
     */
    fun clusterInfo(relatedByCpu: Map<Long, List<Long>?>): ClusterInfo {
        val cpus = relatedByCpu.keys.sorted()
        val parent = HashMap<Long, Long>().also { p -> cpus.forEach { p[it] = it } }
        fun find(x0: Long): Long {
            var x = x0
            while (parent[x] != x) {
                parent[x] = parent[parent[x]!!]!!
                x = parent[x]!!
            }
            return x
        }
        for ((cpu, rel) in relatedByCpu) {
            for (r in rel ?: emptyList()) {
                if (parent.containsKey(r)) {
                    val ra = find(cpu)
                    val rb = find(r)
                    if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
                }
            }
        }
        val groups = LinkedHashMap<Long, MutableList<Long>>()
        for (c in cpus) groups.getOrPut(find(c)) { ArrayList() }.add(c)
        val members = groups.values.map { it.sorted() }.sortedBy { it[0] }
        val cid = HashMap<Long, Int>()
        members.forEachIndexed { i, m -> m.forEach { cid[it] = i } }
        val how = HashMap<Long, String>()
        for (c in cpus) {
            how[c] = if (!relatedByCpu[c].isNullOrEmpty()) "listed" else if (members[cid[c]!!].size > 1) "joined" else "singleton"
        }
        return ClusterInfo(cid, members, how)
    }

    // arm64 HWCAP / HWCAP2 bit names: macro suffixes of Linux arch/arm64/include/uapi/asm/hwcap.h.
    val HWCAP_NAMES = listOf(
        "fp", "asimd", "evtstrm", "aes", "pmull", "sha1", "sha2", "crc32", "atomics", "fphp", "asimdhp",
        "cpuid", "asimdrdm", "jscvt", "fcma", "lrcpc", "dcpop", "sha3", "sm3", "sm4", "asimddp",
        "sha512", "sve", "asimdfhm", "dit", "uscat", "ilrcpc", "flagm", "ssbs", "sb", "paca", "pacg",
        "gcs", "cmpbr", "fprcvt", "f8mm8", "f8mm4", "sve_f16mm", "sve_eltperm", "sve_aes2",
        "sve_bfscale", "sve2p2", "sme2p2", "sme_sbitperm", "sme_aes", "sme_sfexpa", "sme_stmop",
        "sme_smop4",
    )
    val HWCAP2_NAMES = listOf(
        "dcpodp", "sve2", "sveaes", "svepmull", "svebitperm", "svesha3", "svesm4", "flagm2", "frint",
        "svei8mm", "svef32mm", "svef64mm", "svebf16", "i8mm", "bf16", "dgh", "rng", "bti", "mte", "ecv",
        "afp", "rpres", "mte3", "sme", "sme_i16i64", "sme_f64f64", "sme_i8i32", "sme_f16f32",
        "sme_b16f32", "sme_f32f32", "sme_fa64", "wfxt", "ebf16", "sve_ebf16", "cssc", "rprfm", "sve2p1",
        "sme2", "sme2p1", "sme_i16i32", "sme_bi32i32", "sme_b16b16", "sme_f16f16", "mops", "hbc",
        "sve_b16b16", "lrcpc3", "lse128", "fpmr", "lut", "faminmax", "f8cvt", "f8fma", "f8dp4", "f8dp2",
        "f8e4m3", "f8e5m2", "sme_lutv2", "sme_f8f16", "sme_f8f32", "sme_sf8fma", "sme_sf8dp4",
        "sme_sf8dp2", "poe",
    )

    /**
     * A JSON-Schema `pattern` of the form `^...$` as Python's `re.search` applies it: `$` also matches before one
     * final newline, so `"kit/v1\n"` satisfies `^[a-z0-9-]+/[A-Za-z0-9._-]+$`. The same quirk is ported so the
     * Kotlin parsers accept exactly what the Python schema check accepts.
     */
    fun schemaPattern(re: Regex, s: String): Boolean = re.matches(s) || (s.endsWith("\n") && re.matches(s.dropLast(1)))

    /** Names of the set bits (HWCAP names then HWCAP2 names, in bit order) and `hwcap bit N` for unnamed set bits. */
    fun decodeHwcap(hwcap: BigInteger, hwcap2: BigInteger): Pair<List<String>, List<String>> {
        val names = ArrayList<String>()
        val unnamed = ArrayList<String>()
        for ((word, table, label) in listOf(Triple(hwcap, HWCAP_NAMES, "hwcap"), Triple(hwcap2, HWCAP2_NAMES, "hwcap2"))) {
            for (bit in 0 until word.abs().bitLength()) {
                if (word.testBit(bit)) {
                    if (bit < table.size) names.add(table[bit]) else unnamed.add("$label bit $bit")
                }
            }
        }
        return names to unnamed
    }
}
