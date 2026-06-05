package org.appdevforall.maps.slicer

import android.net.TrafficStats
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Abstraction over "read a byte range from a PMTiles archive". Implementations:
 *  - [HttpRangeFetcher] — for `http://` / `https://` URLs (LAN or internet)
 *  - [FileRangeFetcher] — for `file://` URLs (debug / unit tests against the
 *    bundled Natural Earth pmtiles)
 *
 * Both honor cancellation via the caller's coroutine context — the actual
 * cooperation point is between fetched chunks at the call-site.
 */
internal interface RangeFetcher : Closeable {
    /** Read [length] bytes starting at [offset] of the underlying source. */
    @Throws(IOException::class)
    fun readRange(offset: Long, length: Int): ByteArray

    override fun close() = Unit

    companion object {
        /**
         * Build the right fetcher for [url]. Supports `http://`, `https://`,
         * and `file://` (latter is convenient for tests + the bundled NE
         * archive).
         */
        fun forUrl(url: String, client: OkHttpClient = defaultClient()): RangeFetcher = when {
            url.startsWith("http://") || url.startsWith("https://") ->
                HttpRangeFetcher(url, client)
            url.startsWith("file://") ->
                FileRangeFetcher(File(url.removePrefix("file://")))
            else ->
                throw IllegalArgumentException("Unsupported URL scheme: $url")
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder().build()
    }
}

/**
 * Process-wide LRU cache for HTTP range reads, keyed by `(url, offset, length)`.
 *
 * Why: the slicer's `tilesInRegion` walk re-fetches the same header + root
 * directory + most of the same leaves every time the user adjusts the bbox.
 * Without this cache, each adjustment re-runs the full directory walk from
 * scratch over the network. With it, only the *new* leaves a pan/zoom uncovers
 * actually hit the network — the rest are instant in-memory copies.
 *
 * Bounded at 8 MiB total: even a global PMTiles archive's header + root + all
 * leaves combined is well under that, and the cache is shared across slicer
 * runs in the same process so the second-and-later runs typically hit
 * everything they need without a single network read.
 *
 * Tile-byte reads should NOT go through this fetcher path — they happen via
 * the download pipeline, which has its own coalescing logic. If they ever did
 * land here, LRU eviction caps the damage at 8 MiB.
 */
internal object HttpRangeByteCache {
    private const val MAX_BYTES = 8L * 1024 * 1024
    private var totalBytes = 0L

    // accessOrder=true → get() promotes to most-recently-used so LRU eviction
    // throws out the truly-cold entries.
    private val entries = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {}

    @Synchronized
    fun get(url: String, offset: Long, length: Int): ByteArray? {
        return entries["$url|$offset|$length"]
    }

    @Synchronized
    fun put(url: String, offset: Long, length: Int, bytes: ByteArray) {
        val key = "$url|$offset|$length"
        if (entries.containsKey(key)) return
        entries[key] = bytes
        totalBytes += bytes.size.toLong()
        while (totalBytes > MAX_BYTES && entries.isNotEmpty()) {
            val it = entries.entries.iterator()
            val first = it.next()
            it.remove()
            totalBytes -= first.value.size.toLong()
        }
    }

    /**
     * Drop every entry. Call before a download starts — the cache is intended
     * for the bbox-picker's rapid-pan estimate loop, where we WANT stale-OK
     * reads. A download is a different contract: the produced .pmtiles must
     * match the upstream archive's *current* bytes, so we re-read everything
     * fresh. Without this, an upstream mid-session swap of the archive at the
     * same URL (the weekly OSM updates IIAB tracks) would produce a header from
     * version N glued to leaf bytes from version N+1 — silently broken.
     */
    @Synchronized
    fun clear() {
        entries.clear()
        totalBytes = 0L
    }
}

internal class HttpRangeFetcher(
    private val url: String,
    private val client: OkHttpClient,
) : RangeFetcher {

    private companion object {
        /**
         * Cap on a 200-OK (Range-ignored) response body. A compliant server
         * answers with 206 + only the requested range; a 200 means it sent the
         * whole archive, which for OSM PMTiles can be multiple GB —
         * buffering that to slice locally OOMs the phone. Larger-than-this or
         * unknown-length bodies are refused rather than buffered.
         */
        const val MAX_200_FALLBACK_BYTES = 32L * 1024L * 1024L

        /**
         * Socket traffic-stats tag for range fetches. Tagging the thread before
         * the okhttp call keeps StrictMode's UntaggedSocketViolation quiet and
         * lets the platform attribute this traffic to the slicer.
         */
        private const val THREAD_STATS_TAG = 0x4D41_5053 // "MAPS"
    }

    override fun readRange(offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "offset $offset must be nonneg" }
        require(length > 0) { "length $length must be positive" }
        HttpRangeByteCache.get(url, offset, length)?.let { return it }
        val end = offset + length - 1
        val req = Request.Builder()
            .url(url)
            .header("Range", "bytes=$offset-$end")
            .build()
        TrafficStats.setThreadStatsTag(THREAD_STATS_TAG)
        try {
            client.newCall(req).execute().use { resp ->
                // 206 is the spec-compliant range response; 200 means the server
                // ignored Range and sent the whole file (which we still slice
                // locally rather than blow up the call).
                when (resp.code) {
                    206 -> {
                        val body = resp.body ?: throw IOException("No body for range $offset-$end of $url")
                        val bytes = body.bytes()
                        require(bytes.size == length || bytes.size == (end - offset + 1).toInt()) {
                            "Range response returned ${bytes.size} bytes, expected $length"
                        }
                        HttpRangeByteCache.put(url, offset, length, bytes)
                        return bytes
                    }
                    200 -> {
                        val body = resp.body ?: throw IOException("No body for $url")
                        // The server ignored Range and will send the whole file. For a
                        // multi-GB PMTiles archive, buffering it into memory to slice
                        // locally is an instant OOM on a 2-4 GB phone. Refuse loudly when
                        // the body is unknown-length or larger than the cap; the slicer
                        // treats a fetch failure as a download error, so failing here is safe.
                        val declared = body.contentLength()
                        if (declared < 0 || declared > MAX_200_FALLBACK_BYTES) {
                            throw IOException(
                                "Server ignored Range and returned $declared bytes for $url " +
                                    "(cap $MAX_200_FALLBACK_BYTES) — refusing to buffer whole archive",
                            )
                        }
                        val whole = body.bytes()
                        require(offset.toInt().toLong() == offset && (offset.toInt() + length) <= whole.size) {
                            "200-OK fallback can't satisfy range $offset-$end of size ${whole.size}"
                        }
                        val slice = whole.copyOfRange(offset.toInt(), offset.toInt() + length)
                        HttpRangeByteCache.put(url, offset, length, slice)
                        return slice
                    }
                    else -> throw IOException("HTTP ${resp.code} for $url range $offset-$end")
                }
            }
        } finally {
            TrafficStats.clearThreadStatsTag()
        }
    }
}

internal class FileRangeFetcher(file: File) : RangeFetcher {
    private val raf = RandomAccessFile(file, "r")

    override fun readRange(offset: Long, length: Int): ByteArray {
        val buf = ByteArray(length)
        raf.seek(offset)
        var read = 0
        while (read < length) {
            val n = raf.read(buf, read, length - read)
            if (n < 0) throw IOException("EOF after $read of $length bytes at offset $offset")
            read += n
        }
        return buf
    }

    override fun close() {
        raf.close()
    }
}
