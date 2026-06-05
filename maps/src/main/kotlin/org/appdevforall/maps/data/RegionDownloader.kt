package org.appdevforall.maps.data

import android.content.Context
import org.appdevforall.maps.domain.Bbox
import org.appdevforall.maps.domain.SourceKind
import org.appdevforall.maps.MapsPlugin
import org.appdevforall.maps.util.AtomicFiles
import org.appdevforall.maps.slicer.PmtilesHeader
import org.appdevforall.maps.slicer.PmtilesRegionSlicer
import org.appdevforall.maps.slicer.PmtilesV3
import org.appdevforall.maps.slicer.RangeFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Region download coordinator. Writes the canonical cache layout in
 * `/sdcard/CodeOnTheGo/maps/<regionId>/`.
 *
 * Instead of pulling the whole multi-GB global PMTiles per region, the vector
 * tiles are *sliced* — only the bbox's tile bytes are
 * transferred and written to a fresh PMTiles v3 archive with a matching header.
 * The basemap (Natural Earth, ~7 MB worldwide) is the same global file every
 * region uses — and it's already bundled in the plugin (byte-for-byte identical
 * to the IIAB copy), so it's copied from the bundle rather than re-downloaded.
 *
 * Single source of truth for "which tiles cover the region" is
 * [PmtilesRegionSlicer.tilesInRegion]; both the size estimate and the download
 * read from it.
 *
 * URL contract:
 *  - **Internet** — `https://iiab.switnet.org/maps/2/`:
 *    - `openstreetmap-openmaptiles.<date>.z00-z14.pmtiles` (vector tiles)
 *  - **LAN** — `http://<host>/maps/` with the same filenames.
 *  The Natural Earth basemap is copied from the plugin bundle, not fetched.
 */
internal object RegionDownloader {

    /** Schema version. Bump when adding required fields. */
    internal const val META_SCHEMA_VERSION = 1

    /** Hard-coded fallback date for the IIAB archive filename. */
    private const val FALLBACK_VECTOR_DATE = "2026-04-01"

    /**
     * The Natural Earth basemap bundled in the plugin assets. It's a global,
     * static file — identical for every region and byte-for-byte the same as the
     * IIAB-hosted copy — so we copy it from here instead of downloading ~7 MB per
     * region. (Same asset the bbox picker renders for its world view.)
     */
    private const val BUNDLED_BASEMAP_ASSET = "maps/natural-earth-z0-z4.pmtiles"

    private const val INTERNET_BASE = "https://iiab.switnet.org/maps/2/"
    private const val LAN_PATH_SUFFIX = "/maps/"

    // OkHttp client tuned for the IIAB use case:
    //  - Many in-flight range requests against the same host while slicing a
    //    region archive. Default Dispatcher caps at 5 concurrent requests per
    //    host, which throttles our parallel slicer (PmtilesRegionSlicer
    //    fan-outs). Raise to 16 so the slicer's PARALLEL_FETCHES=6 is the
    //    real cap, not OkHttp.
    //  - Keep connections alive across requests so we don't pay TCP+TLS
    //    handshake per range. Pool 12 idle for 5 min covers a long slice.
    /**
     * Shared OkHttpClient — exposed `internal` so the bbox-picker's slicer
     * estimate reuses the same connection pool + dispatcher tuning as the
     * download path. Warm connections across estimate→download pay the TLS
     * handshake once instead of twice.
     */
    internal val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
        .connectionPool(ConnectionPool(12, 5, TimeUnit.MINUTES))
        .build()

    /**
     * Discrete phases the wizard surfaces in the progress UI.
     */
    enum class Phase { BASEMAP, TILES }

    /**
     * Per-phase progress callback. [bytesRead] = downloaded so far,
     * [totalBytes] = Content-Length (or -1 if unknown).
     */
    fun interface ProgressListener {
        fun onProgress(phase: Phase, bytesRead: Long, totalBytes: Long)
    }

    /**
     * Run the full download into `<cacheRoot>/<regionId>.partial/`; rename
     * atomically to `<regionId>/` on success.
     *
     * @return the final region directory (NOT the .partial path).
     * @throws IOException on network / disk failures.
     * @throws IllegalArgumentException if [regionId] is malformed.
     */
    suspend fun download(
        context: Context,
        regionId: String,
        displayName: String,
        bbox: Bbox,
        zoomMin: Int = 6,
        zoomMax: Int = 14,
        sourceKind: SourceKind = SourceKind.UNKNOWN,
        sourceHost: String? = null,
        sourceVersion: String? = null,
        onProgress: ProgressListener = ProgressListener { _, _, _ -> },
    ): File {
        android.util.Log.i(
            "RegionDownloader",
            "download: regionId=$regionId bbox=$bbox z=$zoomMin..$zoomMax",
        )
        // The estimate loop populates HttpRangeByteCache. A download needs fresh
        // bytes — IIAB rotates the same URL weekly, and a mid-session swap would
        // glue a header from version N to leaves from N+1.
        org.appdevforall.maps.slicer.HttpRangeByteCache.clear()
        val tilesUrl = buildTilesUrl(sourceKind, sourceHost)
        return downloadInto(
            cacheRoot = RegionCache.rootDir(),
            regionId = regionId,
            displayName = displayName,
            bbox = bbox,
            zoomMin = zoomMin,
            zoomMax = zoomMax,
            sourceKind = sourceKind,
            sourceHost = sourceHost,
            sourceVersion = sourceVersion,
            nowMillis = System.currentTimeMillis(),
            onProgress = onProgress,
            // The bundled Natural Earth basemap is the same global file for every
            // region (byte-identical to the IIAB copy) — copy from the plugin asset
            // instead of re-downloading ~7 MB. A failure here is a packaging error.
            copyBasemap = { dest, op -> copyBundledBasemap(dest, op) },
            // Vector tiles: sliced down to the bbox over the network.
            sliceTiles = { target, op ->
                sliceDownload(tilesUrl, bbox, zoomMin, zoomMax, target, op)
            },
        )
    }

    /**
     * The pure download orchestration, with the three IO seams ([cacheRoot],
     * [copyBasemap], [sliceTiles]) and the clock ([nowMillis]) injected so it's
     * unit-testable on the JVM without `Environment`, the plugin asset bundle, or a
     * live PMTiles host. [download] supplies the real implementations; tests pass a
     * temp dir + fakes.
     *
     * Writes into `<cacheRoot>/<regionId>.partial/` and renames atomically to
     * `<regionId>/` only after `meta.json` is written (its presence implies
     * completion). Any failure — including cancellation — deletes the partial dir.
     */
    internal suspend fun downloadInto(
        cacheRoot: File,
        regionId: String,
        displayName: String,
        bbox: Bbox,
        zoomMin: Int,
        zoomMax: Int,
        sourceKind: SourceKind,
        sourceHost: String?,
        sourceVersion: String?,
        nowMillis: Long,
        onProgress: ProgressListener,
        copyBasemap: (dest: File, onProgress: (Long, Long) -> Unit) -> Unit,
        sliceTiles: suspend (target: File, onProgress: (Long, Long) -> Unit) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        require(RegionCache.isValidRegionId(regionId)) {
            "regionId '$regionId' is not a valid region identifier " +
                "(lowercase letters/digits/marks of any script, '-', no path separators)"
        }
        val finalDir = File(cacheRoot, regionId)
        val partialDir = File(cacheRoot, "$regionId.partial").apply {
            // Clean any leftover partial from a prior cancelled run.
            if (exists()) deleteRecursively()
            mkdirs()
        }

        // Defense-in-depth: canonicalise to keep the partial dir under the root.
        val canonicalRoot = cacheRoot.canonicalFile.toPath()
        val canonicalPartial = partialDir.canonicalFile.toPath()
        require(canonicalPartial.startsWith(canonicalRoot)) {
            "partialDir escapes cache root: $canonicalPartial not under $canonicalRoot"
        }

        try {
            // ---- BASEMAP ----
            copyBasemap(File(partialDir, RegionCache.FILE_BASEMAP_PMTILES)) { bytes, total ->
                onProgress.onProgress(Phase.BASEMAP, bytes, total)
            }
            coroutineContext.ensureActive()

            // ---- TILES (vector): sliced ----
            sliceTiles(File(partialDir, RegionCache.FILE_TILES_PMTILES)) { bytes, total ->
                onProgress.onProgress(Phase.TILES, bytes, total)
            }
            coroutineContext.ensureActive()

            // Build + write meta.json (LAST — its presence implies completion).
            val sizeBytes = listOf(
                RegionCache.FILE_BASEMAP_PMTILES,
                RegionCache.FILE_TILES_PMTILES,
            ).sumOf { File(partialDir, it).length() }
            val layers = buildList {
                add("vector")
                add("basemap")
            }
            val sourceObj = JSONObject().apply {
                put("kind", sourceKind.wireValue)
                if (sourceHost != null) put("host", sourceHost)
                if (sourceVersion != null) put("version", sourceVersion)
                put("downloadedAt", nowMillis)
            }
            val metaJson = JSONObject().apply {
                put("version", META_SCHEMA_VERSION)
                put("regionId", regionId)
                put("displayName", displayName)
                put("bbox", JSONArray(bbox.toBoundsArray().toList()))
                put("zoomMin", zoomMin)
                put("zoomMax", zoomMax)
                put("source", sourceObj)
                put("sizeBytes", sizeBytes)
                put("downloadedAt", nowMillis)
                put("lastUsedAt", nowMillis)
                put("layers", JSONArray(layers))
            }
            AtomicFiles.writeText(File(partialDir, RegionCache.FILE_META_JSON), metaJson.toString(2))

            // Atomic rename .partial → final.
            if (finalDir.exists()) finalDir.deleteRecursively()
            partialDir.renameTo(finalDir) || error(
                "Couldn't rename ${partialDir.absolutePath} → ${finalDir.absolutePath}"
            )
            finalDir
        } catch (t: Throwable) {
            // Clean partial on ANY failure — including cancellation.
            runCatching { partialDir.deleteRecursively() }
            throw t
        }
    }

    /**
     * Slice [globalUrl]'s PMTiles archive down to [bbox]·[zoomMin..zoomMax]
     * tiles, writing the result to [target] (a valid v3 PMTiles file).
     *
     * Uses [PmtilesRegionSlicer.tilesInRegion] to discover which tiles to fetch
     * (same function the size estimator calls — single source of truth), then
     * [PmtilesRegionSlicer.downloadAndSlice] to range-fetch each tile blob and
     * reassemble.
     */
    private suspend fun sliceDownload(
        globalUrl: String,
        bbox: Bbox,
        zoomMin: Int,
        zoomMax: Int,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        // Stage 1: parse header + identify tiles. One fetcher session.
        val (sourceHeader, tiles) = RangeFetcher.forUrl(globalUrl, httpClient).use { fetcher ->
            val headerBytes = fetcher.readRange(0L, PmtilesV3.HEADER_BYTES)
            val header = PmtilesHeader.parse(
                ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            )
            val regionTiles = PmtilesRegionSlicer.tilesInRegionImpl(fetcher, bbox, zoomMin, zoomMax)
            if (regionTiles.isEmpty()) {
                throw IOException(
                    "No tiles intersect bbox $bbox at zoom $zoomMin..$zoomMax in $globalUrl"
                )
            }
            header to regionTiles
        }

        // Stage 2: fetch + reassemble.
        PmtilesRegionSlicer.downloadAndSlice(
            tiles = tiles,
            globalPmtilesUrl = globalUrl,
            sourceHeader = sourceHeader,
            bbox = bbox,
            zoomMin = zoomMin,
            zoomMax = zoomMax,
            targetFile = target,
            onProgress = { downloaded, total -> onProgress(downloaded, total) },
            client = httpClient,
        ).getOrThrow()
    }

    // ----- URL builders -----

    private fun base(sourceKind: SourceKind, sourceHost: String?): String = when (sourceKind) {
        SourceKind.IIAB_LAN -> {
            val host = sourceHost?.trim().orEmpty()
            require(host.isNotBlank()) {
                "IIAB_LAN sourceKind requires a non-blank host"
            }
            val withScheme = when {
                host.startsWith("http://") || host.startsWith("https://") -> host
                else -> "http://$host"
            }
            withScheme.trimEnd('/') + LAN_PATH_SUFFIX
        }
        else -> INTERNET_BASE
    }

    /**
     * package-internal: the vector tiles (OpenMapTiles) URL for [sourceKind].
     *
     * Exposed so [BboxPickerFragment] can drive a live size estimate
     * via [PmtilesRegionSlicer.tilesInRegion] against the same URL the
     * download will use — keeps "estimate ≈ actual download size" honest.
     */
    internal fun buildTilesUrl(sourceKind: SourceKind, sourceHost: String?): String =
        base(sourceKind, sourceHost) +
            "openstreetmap-openmaptiles.$FALLBACK_VECTOR_DATE.z00-z14.pmtiles"

    /**
     * Copy the bundled Natural Earth basemap ([BUNDLED_BASEMAP_ASSET]) into [dest].
     * It's the same global basemap every region uses and is byte-for-byte identical
     * to the IIAB copy, so this replaces a redundant ~7 MB download per region. The
     * asset always ships in the plugin; if it can't be read that's a packaging
     * error, so we throw rather than silently falling back to the network.
     */
    private fun copyBundledBasemap(dest: File, onProgress: (Long, Long) -> Unit) {
        val input = MapsPlugin.pluginContext?.resources?.openPluginAsset(BUNDLED_BASEMAP_ASSET)
            ?: throw IOException("bundled basemap asset '$BUNDLED_BASEMAP_ASSET' unavailable")
        val total = input.available().toLong().coerceAtLeast(0L)
        input.use { src ->
            dest.outputStream().use { dst ->
                val buf = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    dst.write(buf, 0, n)
                    copied += n
                    onProgress(copied, total)
                }
            }
        }
        android.util.Log.i(
            "RegionDownloader",
            "basemap copied from bundle ($BUNDLED_BASEMAP_ASSET, $total bytes)",
        )
    }
}
