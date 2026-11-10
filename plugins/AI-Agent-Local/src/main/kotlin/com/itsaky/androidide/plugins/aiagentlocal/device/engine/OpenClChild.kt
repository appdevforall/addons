package com.itsaky.androidide.plugins.aiagentlocal.device.engine

/** What a candidate OpenCL library did when a child process tried to load it. */
enum class OpenClLoad { LOADED, NOT_LOADED, UNKNOWN }

class OpenClCandidate(val name: String, val load: OpenClLoad, val outcome: String)

/**
 * Reads the `opencl_child` list of the GPU probe (`part1-gpuprobe-v1`). It is a fact about a child process, not about the app
 * process (ENGINE.md: "must not be used as OpenCL available"). A candidate the probe never tried (`not_tried`, the overall
 * limit was used up first) is [OpenClLoad.UNKNOWN], not "not loaded".
 */
object OpenClChild {
    fun parse(gpuProbe: Map<String, Any?>): List<OpenClCandidate> {
        val list = gpuProbe["opencl_child"] as? List<*> ?: return emptyList()
        return list.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val name = m["name"] as? String ?: return@mapNotNull null
            val outcome = m["outcome"] as? String ?: "unknown"
            val load = when {
                outcome == "not_tried" -> OpenClLoad.UNKNOWN
                outcome == "loaded" && m["loaded"] == true -> OpenClLoad.LOADED
                else -> OpenClLoad.NOT_LOADED
            }
            OpenClCandidate(name, load, outcome)
        }
    }

    /** Plain tree of [parse] for the T0 facts: `{name, load, outcome}` per candidate. */
    fun toFacts(candidates: List<OpenClCandidate>): List<Map<String, Any?>> =
        candidates.map { linkedMapOf("name" to it.name, "load" to it.load.name.lowercase(), "outcome" to it.outcome) }
}
