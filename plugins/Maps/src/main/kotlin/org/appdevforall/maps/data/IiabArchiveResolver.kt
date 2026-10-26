package org.appdevforall.maps.data

import android.net.TrafficStats
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.appdevforall.maps.util.MAPS_TRAFFIC_STATS_TAG

/**
 * Resolves the internet source's vector archive name from [IiabCatalog], once per process, so
 * the size estimate and the download read the same file. Falls back to
 * [IiabCatalog.FALLBACK_VECTOR_ARCHIVE] when the catalog is unreachable, and retries next time.
 */
internal class IiabArchiveResolver(private val fetchCatalog: suspend () -> String?) {

    @Volatile private var resolved: String? = null

    /** The resolved archive if [resolve] has succeeded, else the fallback. Does no I/O. */
    fun current(): String = resolved ?: IiabCatalog.FALLBACK_VECTOR_ARCHIVE

    /** Fetches the catalog unless an earlier call already resolved it. */
    suspend fun resolve(): String {
        resolved?.let {
            return it
        }
        val json =
            try {
                fetchCatalog()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        val name = json?.let(IiabCatalog::vectorArchive) ?: return IiabCatalog.FALLBACK_VECTOR_ARCHIVE
        resolved = name
        return name
    }

    companion object {
        /** The catalog is a few KB; anything far larger is not the catalog. */
        internal const val MAX_CATALOG_BYTES = 256L * 1024L

        // The picker waits on this fetch before loading its map, so bound the whole call: the
        // connect and read timeouts alone do not cover DNS or a server that trickles bytes.
        internal val catalogClient =
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build()

        /** The process-wide resolver, reading [IiabCatalog.CATALOG_URL]. */
        val shared = IiabArchiveResolver { fetchText(catalogClient, IiabCatalog.CATALOG_URL) }

        /** GETs [url] on `Dispatchers.IO`; null unless it answers 200 with a body under [maxBytes]. */
        internal suspend fun fetchText(
            client: OkHttpClient,
            url: String,
            maxBytes: Long = MAX_CATALOG_BYTES,
        ): String? =
            withContext(Dispatchers.IO) {
                TrafficStats.setThreadStatsTag(MAPS_TRAFFIC_STATS_TAG)
                try {
                    client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                        val body = resp.body ?: return@use null
                        if (resp.code != 200) return@use null
                        val declared = body.contentLength()
                        if (declared > maxBytes) return@use null
                        // Content-Length can be absent (chunked), so cap what is actually read too.
                        val source = body.source()
                        if (source.request(maxBytes + 1)) return@use null
                        source.buffer.readUtf8()
                    }
                } finally {
                    TrafficStats.clearThreadStatsTag()
                }
            }
    }
}
