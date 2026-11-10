package com.itsaky.androidide.plugins.aiagentlocal.device.match

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.PyCompat

/** The result of the device match rule: `result` is exact, soc_sibling or none. */
data class MatchResult(val result: String, val key: String?, val how: String, val howClass: String) {
    internal fun toTree(): Map<String, Any?> =
        linkedMapOf("result" to result, "key" to key, "how" to how, "how_class" to howClass)
}

/**
 * Port of `part1-dbindex/match.py` (which is itself a port of detect.py steps 1-5). Inputs are not
 * trimmed, the key lookup is case-sensitive, entry fields and the device are compared lower-case,
 * identity values count as absent when null, `unk` or empty, a bare-string `soc` is not a sibling seed.
 * Inputs containing glob or path characters are outside the rule's scope (see match.py).
 */
object DeviceIndexMatcher {

    /** [platform] = ro.board.platform, [board] = ro.product.board, [device] = ro.product.device, [hardware] = ro.hardware. */
    fun match(platform: String, board: String, device: String, hardware: String, index: DeviceIndex): MatchResult {
        val soc = platform
        val eff = if (board.isNotEmpty()) board else hardware // step 1
        val socl = PyCompat.lower(soc)
        val effl = PyCompat.lower(eff)
        val devl = PyCompat.lower(device)
        val whyGeneric = ArrayList<String>()
        if (board.isEmpty()) whyGeneric.add("empty ro.product.board")
        if (eff.isNotEmpty() && effl == socl) whyGeneric.add("equals the SoC/platform name")
        val pm = index.genericBoardPatterns.filter { eff.isNotEmpty() && PyCompat.fnmatch(effl, it) }
        if (pm.isNotEmpty()) whyGeneric.add("reference-board pattern " + pm[0])
        val sb = PyCompat.lower(PyCompat.strip(eff))
        if (sb.isNotEmpty() && sb != "unk" && sb != "unknown" && sb in index.sharedBoards) {
            whyGeneric.add("shared by more than one device in devices.csv")
        }
        val generic = whyGeneric.isNotEmpty()
        val gtxt = if (generic) whyGeneric.joinToString("; ") else "not generic"

        var why = ""
        var hit: Map<String, Any?>? = null
        if (soc.isNotEmpty()) {
            val cand = index.byKey["$soc-$eff"] // step 2 (case-sensitive, like the file name)
            if (cand == null) {
                why = "$soc-$eff not in index"
            } else {
                val ident = Json.obj(cand["identity"], "index record ${cand["key"]}.identity")
                val es = first(ident["soc"])
                val eb = first(ident["board"])
                val ids: List<Any?> = if (ident.containsKey("device_ids")) {
                    Json.arr(ident["device_ids"], "index record ${cand["key"]}.identity.device_ids")
                } else {
                    emptyList()
                }
                if (es != null && es != socl) {
                    why = "identity.soc ${PyCompat.repr(es)} != ${PyCompat.repr(soc)}" // step 3
                } else if (eb != null && eb != effl) {
                    why = "identity.board ${PyCompat.repr(eb)} != ${PyCompat.repr(eff)}"
                } else if (ids.isNotEmpty() && !PyCompat.contains(ids, devl)) {
                    why = "ro.product.device ${PyCompat.repr(device)} not in the entry device identity ${PyCompat.str(ids)}"
                } else if (generic && ids.isEmpty()) {
                    why = "generic board ${PyCompat.repr(eff)} ($gtxt) and the entry has no device identity" // step 4
                } else {
                    hit = cand
                }
            }
        }
        if (hit != null) {
            return MatchResult(
                result = "exact",
                key = hit["key"] as String,
                how = if (generic) "exact soc-board-device" else "exact soc-board",
                howClass = if (generic) "exact_soc_board_device" else "exact_soc_board",
            )
        }
        val how = "no exact soc-board${if (generic) "-device" else ""} entry ($why)"
        val sibs = ArrayList<Map<String, Any?>>()
        if (soc.isNotEmpty()) { // step 5
            for (r in index.records) {
                val ident = Json.obj(r["identity"], "index record ${r["key"]}.identity")
                if ((r["key"] as String).startsWith("$soc-") ||
                    (first(ident["soc"]) == socl && !PyCompat.truthy(ident["soc_bare_string"]))
                ) {
                    sibs.add(r)
                }
            }
        }
        if (sibs.isNotEmpty()) {
            val sorted = sibs.sortedWith(
                compareBy<Map<String, Any?>>({ -Json.long(it["n_measured_leaves"], "index record n_measured_leaves") }, { (it["key"] as String) + ".json" }),
            )
            val b = sorted[0]
            val n = Json.long(b["n_measured_leaves"], "index record n_measured_leaves")
            return MatchResult(
                result = "soc_sibling",
                key = b["key"] as String,
                how = "$how; soc-sibling seed ${b["key"]} ($n measured leaves; not savings-eligible)",
                howClass = "soc_sibling",
            )
        }
        return MatchResult("none", null, how, "none")
    }

    /** `_first`: the first whitespace-separated token, lower-cased; null for a non-string, blank or `unk` value. */
    private fun first(x: Any?): String? =
        if (x is String && PyCompat.strip(x).isNotEmpty() && x != "unk") PyCompat.lower(PyCompat.split(x)[0]) else null
}
