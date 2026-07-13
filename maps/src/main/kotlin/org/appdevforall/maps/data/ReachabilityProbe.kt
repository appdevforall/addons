package org.appdevforall.maps.data

import android.net.TrafficStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.appdevforall.maps.domain.SourceKind
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tells the source picker whether a tile source is reachable, via a lightweight HTTP HEAD.
 *
 * Extracted from `SourcePickerFragment` so the **network I/O + URL construction** don't live in a
 * Fragment (separation of concerns — the Fragment keeps only the poll cadence + the on-screen
 * status text). [isReachable] is self-enforcing on `Dispatchers.IO`; [probeUrlFor] / [buildLanProbeUrl]
 * are pure and JVM-testable.
 */
internal class ReachabilityProbe(
    private val internetProbeUrl: String = INTERNET_PROBE_URL,
) {

    /** HEAD-probe [source] (+ LAN [host]); true iff reachable. Runs on `Dispatchers.IO`. */
    suspend fun isReachable(source: SourceKind, host: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = probeUrlFor(source, host) ?: return@withContext false
            probeHead(url)
        }

    /**
     * The probe URL for [source], or null when a LAN host is required but blank (the caller shows
     * "enter a hostname" instead of probing). Pure.
     */
    fun probeUrlFor(source: SourceKind, host: String): String? = when (source) {
        SourceKind.IIAB_LAN -> if (host.isBlank()) null else buildLanProbeUrl(host)
        else -> internetProbeUrl
    }

    /**
     * LAN probe URL = `http://<host>/maps/extracts.json` — the canonical IIAB inventory; a HEAD on
     * it is the lightest check that the maps role is up. A host with an explicit scheme is honoured;
     * otherwise default to plain http (IIAB boxes don't typically run TLS on the LAN). Pure.
     */
    fun buildLanProbeUrl(host: String): String {
        val base = when {
            host.startsWith("http://") || host.startsWith("https://") -> host
            else -> "http://$host"
        }
        return base.trimEnd('/') + "/maps/extracts.json"
    }

    private suspend fun probeHead(url: String): Boolean = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
        // Tag the socket so the HEAD probe doesn't trip StrictMode's UntaggedSocketViolation
        // (same fix as RangeFetcher's range fetch).
        TrafficStats.setThreadStatsTag(PROBE_STATS_TAG)
        try {
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "HEAD"
                    conn.connectTimeout = PROBE_TIMEOUT_MS.toInt()
                    conn.readTimeout = PROBE_TIMEOUT_MS.toInt()
                    conn.instanceFollowRedirects = true
                    conn.responseCode in 200..399
                } finally {
                    conn.disconnect()
                }
            }.getOrDefault(false)
        } finally {
            TrafficStats.clearThreadStatsTag()
        }
    } ?: false

    companion object {
        const val INTERNET_PROBE_URL = "https://iiab.switnet.org/maps/2/"
        private const val PROBE_TIMEOUT_MS = 6_000L
        private const val PROBE_STATS_TAG = 0x4D41_5053 // "MAPS"
    }
}
