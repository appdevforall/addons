package com.itsaky.androidide.plugins.aiagentgemini.backend

import android.net.TrafficStats

/**
 * Thread stats tags for this plugin's sockets: ASCII bytes, so a raw `dumpsys netstats` dump stays
 * readable. Keep-alive pooling makes the split approximate — a pooled socket keeps the tag it was
 * born with, and inference and ListModels share a host.
 */
internal object NetworkTags {

    /** Generation, streaming and not — `"GEIN"`. */
    const val INFERENCE = 0x4745494E

    /** The ListModels catalog — `"GECT"`. */
    const val CATALOG = 0x47454354
}

/**
 * Run [block] with [tag] on this thread's sockets, restoring the previous tag in a `finally` so the
 * tag never leaks onto these shared `Dispatchers.IO` threads and an enclosing tag survives. Not
 * `inline` on purpose: the tag is thread-local, so [block] must not suspend.
 *
 * @param tag one of [NetworkTags]
 * @return whatever [block] returned
 */
internal fun <T> withTrafficTag(tag: Int, block: () -> T): T {
    val previous = TrafficStats.getThreadStatsTag()
    TrafficStats.setThreadStatsTag(tag)
    return try {
        block()
    } finally {
        TrafficStats.setThreadStatsTag(previous)
    }
}
