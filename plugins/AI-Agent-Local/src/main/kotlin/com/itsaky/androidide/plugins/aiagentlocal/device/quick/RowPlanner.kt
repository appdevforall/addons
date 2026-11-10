package com.itsaky.androidide.plugins.aiagentlocal.device.quick

import com.itsaky.androidide.plugins.aiagentlocal.device.descriptor.PartLib

/** One cluster of the static input: the CPUs that share a `related_cpus` group, and the ones of them that pinned. */
class ClusterPlan(val id: Int, val cpus: List<Int>, val pinnable: List<Int>)

/** The CPU a cluster's single-core row runs on: the first CPU, a [substitutedFor] stand-in from the same cluster, or [unpinned]. */
class C2Choice(val cpu: Int, val substitutedFor: Int?, val unpinned: Boolean)

/**
 * Plans the T1 rows from the static input (the T0 object with the pin probe folded in). Pure functions, no I/O.
 *
 * - **Clusters** are the groups of `related_cpus` ([PartLib.clusterInfo]); ids are 0..N-1 in the order of each cluster's lowest CPU, the
 *   id the `C2-read-k<id>` tag carries.
 * - **Pinnable** means the pin probe gave `pin.result` `ok` for the CPU. A CPU the probe did not list is not pinnable.
 * - **Ranking** is by the measured single-core value, never by name, capacity or frequency; equal values keep the lower cluster id first;
 *   a cluster with no measured value comes after every measured one.
 */
object RowPlanner {

    fun clusters(static: Map<String, Any?>): List<ClusterPlan> {
        val cpus = (static["cpus"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val related = LinkedHashMap<Long, List<Long>?>()
        val pinned = HashSet<Long>()
        for (c in cpus) {
            val index = (c["index"] as? Number)?.toLong() ?: continue
            related[index] = (c["related_cpus"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() }
            if (((c["pin"] as? Map<*, *>)?.get("result")) == "ok") pinned.add(index)
        }
        if (related.isEmpty()) return emptyList()
        val info = PartLib.clusterInfo(related)
        return info.members.mapIndexed { id, members ->
            ClusterPlan(id, members.map { it.toInt() }, members.filter { it in pinned }.map { it.toInt() })
        }
    }

    /** First CPU if it pins; else the first pinnable CPU of the cluster (a substitute); else the first CPU, to be run unpinned. */
    fun c2Choice(cluster: ClusterPlan): C2Choice {
        val first = cluster.cpus.first()
        if (first in cluster.pinnable) return C2Choice(first, null, false)
        val sub = cluster.pinnable.firstOrNull() ?: return C2Choice(first, null, true)
        return C2Choice(sub, first, false)
    }

    /** Cluster ids, best measured value first; [measured] maps a cluster id to its single-core read (GB/s). */
    fun ranking(clusters: List<ClusterPlan>, measured: Map<Int, Double>): List<Int> {
        val have = clusters.filter { measured[it.id] != null }
        val sortedHave = have.sortedWith(compareBy<ClusterPlan>({ -measured.getValue(it.id) }, { it.id }))
        val rest = clusters.filter { measured[it.id] == null }.sortedBy { it.id }
        return (sortedHave + rest).map { it.id }
    }

    /** The two fastest pinnable CPUs: from the best cluster, then the next; null when fewer than two CPUs pinned. */
    fun twoFastest(clusters: List<ClusterPlan>, ranking: List<Int>): List<Int>? {
        val out = ArrayList<Int>()
        for (id in ranking) {
            val c = clusters.firstOrNull { it.id == id } ?: continue
            for (cpu in c.pinnable) {
                if (out.size < 2) out.add(cpu)
            }
        }
        return out.takeIf { it.size == 2 }
    }

    /** Every pinnable CPU, ascending. */
    fun allPinnable(clusters: List<ClusterPlan>): List<Int> = clusters.flatMap { it.pinnable }.sorted()

    /** True when the GPU probe found a Vulkan compute device (`vulkan.present` and `vulkan.compute_queue` are true). */
    fun hasVulkanCompute(static: Map<String, Any?>): Boolean {
        val vk = static["vulkan"] as? Map<*, *> ?: return false
        return vk["present"] == true && vk["compute_queue"] == true
    }

    /** How many rows the pass has: the pin probe, one per cluster, the two-fast row, the all-core row, and the GPU row. */
    fun totalRows(clusters: List<ClusterPlan>, vulkanCompute: Boolean, assumeAllPinnable: Boolean = false): Int {
        // before the pin probe nothing is known: the estimate assumes every CPU pins
        val pinnable = if (assumeAllPinnable) clusters.flatMap { it.cpus } else allPinnable(clusters)
        return 1 + clusters.size + (if (pinnable.size >= 2) 1 else 0) + (if (pinnable.isNotEmpty()) 1 else 0) + (if (vulkanCompute) 1 else 0)
    }
}
