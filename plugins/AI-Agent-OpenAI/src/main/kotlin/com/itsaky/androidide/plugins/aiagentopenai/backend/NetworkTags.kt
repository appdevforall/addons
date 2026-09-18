package com.itsaky.androidide.plugins.aiagentopenai.backend

import android.net.TrafficStats

/**
 * Thread stats tags for this plugin's sockets: ASCII bytes, so a raw `dumpsys netstats` dump stays
 * readable. Keep-alive pooling makes the split approximate — a pooled socket keeps the tag it was
 * born with, and inference and the catalog share a base URL.
 */
internal object NetworkTags {

    /** Chat completions, streaming and not — `"OAIN"`. */
    const val INFERENCE = 0x4F41494E

    /** The model catalog — `"OACT"`. */
    const val CATALOG = 0x4F414354
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
