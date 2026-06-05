package org.appdevforall.maps.data

import android.os.Environment
import android.util.Log
import org.appdevforall.maps.domain.RegionId
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-disk view of `/sdcard/CodeOnTheGo/maps/<region-id>/`.
 *
 * **Layout:**
 *  - `tiles.pmtiles`     — vector tiles (OpenMapTiles schema, OSM-derived).
 *                          Single-file PMTiles archive, loaded via PMTiles'
 *                          HTTP range-request reader.
 *  - `basemap.pmtiles`   — Natural Earth raster basemap (public domain). Always
 *                          present; the renderer falls back to it at low zooms.
 *  - `meta.json`         — `{regionId, displayName, bbox, zoomMin, zoomMax,
 *                            source: {kind, host?, version?, downloadedAt},
 *                            sizeBytes, layers: ["vector", "basemap"]}`
 *
 * The cache lives outside the plugin so it survives plugin re-installs and is
 * shareable between projects. Public external storage is used because:
 *   (a) Internet-in-a-Box and similar channels can sideload tile packs into a
 *       known path without going through app-specific storage;
 *   (b) the cache can be 100s of MB and shouldn't count against one app's quota.
 *
 * Permission caveat: scoped storage on API 30+ disallows arbitrary writes to
 * `/sdcard/...`. The IDE holds `MANAGE_EXTERNAL_STORAGE` and this plugin runs
 * inside that process, so writes work.
 *
 * **regionId precondition.** The id must satisfy [RegionId.isValid]. [isValidRegionId]
 * is the authoritative validator, called from every entry point that resolves a
 * path under the cache root.
 *
 * **Testability.** The `*FromRoot` overloads take an explicit root File so JVM
 * tests can run without `Environment.getExternalStorageDirectory`; the no-arg
 * variants delegate to [rootDir].
 */
internal object RegionCache {

    private const val TAG = "MapsPlugin.RegionCache"
    private const val DEFAULT_ROOT_NAME = "CodeOnTheGo/maps"

    /**
     * File names the wizard writes inside `<region-id>/`. Region Manager
     * sums their sizes for display, and `applyRegionToProject` knows which
     * ones to copy into a project.
     */
    internal const val FILE_TILES_PMTILES = "tiles.pmtiles"
    internal const val FILE_BASEMAP_PMTILES = "basemap.pmtiles"
    internal const val FILE_META_JSON = "meta.json"

    /**
     * Path-safety allowlist check for a regionId, delegating to the canonical,
     * unit-tested [RegionId.isValid] (its doc carries the Unicode + path-safety
     * rationale). Kept here so the data layer has one validator entry point —
     * callers don't reach into `domain/` — and it's backed by the
     * canonical-containment check in [deleteFromRoot] / the downloader (defense in
     * depth).
     */
    fun isValidRegionId(regionId: String): Boolean = RegionId.isValid(regionId)

    /** Returns the root directory of the cache. Creates it lazily; never null. */
    fun rootDir(): File {
        val storage = Environment.getExternalStorageDirectory()
            ?: error("External storage unavailable")
        val root = File(storage, DEFAULT_ROOT_NAME)
        if (!root.exists()) root.mkdirs()
        return root
    }

    /**
     * List regions currently present in the cache. Returns an empty list if
     * the cache is empty or unreadable. Never throws — the bottom-sheet tab
     * needs to render even if storage is in a bad state.
     *
     * Skips any directory without a wizard-written `meta.json` — that means
     * sideloaded directories (or partial / aborted downloads) don't show up
     * as ghost rows. Plain dirs land on a quiet log line, not in the UI.
     */
    fun list(): List<RegionInfo> {
        val root = runCatching { rootDir() }.getOrNull() ?: return emptyList()
        return listFromRoot(root)
    }

    /** Testable variant of [list] taking an explicit root directory. */
    fun listFromRoot(root: File): List<RegionInfo> {
        val children = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return children.mapNotNull { dir ->
            val meta = File(dir, FILE_META_JSON)
            if (!meta.exists()) {
                // The wizard writes meta.json LAST, so a missing meta means a
                // sideloaded / partial / aborted download — skip the ghost row.
                warn("Skipping region without meta.json: ${dir.name}")
                return@mapNotNull null
            }
            runCatching { read(dir) }
                .onFailure { warn("Skipping malformed region at ${dir.name}: ${it.message}") }
                .getOrNull()
        }.sortedBy { it.displayName.lowercase() }
    }

    /**
     * Delete a cached region directory recursively. Returns true if the
     * directory either was removed or did not exist (idempotent). Defensive
     * checks ensure the path is a child of the cache root before deletion —
     * catches `regionId` values that contain `..` or absolute paths.
     *
     * Validates [regionId] via [isValidRegionId] first, then
     * canonicalises and asserts containment. The two checks are
     * complementary: the regex prevents most attacks at the API boundary,
     * canonicalisation catches anything the regex missed (defense in depth).
     */
    fun delete(regionId: String): Boolean {
        val root = runCatching { rootDir() }.getOrNull() ?: return false
        return deleteFromRoot(root, regionId)
    }

    /** Testable variant of [delete] taking an explicit root directory. */
    fun deleteFromRoot(root: File, regionId: String): Boolean {
        if (!isValidRegionId(regionId)) {
            warn("Refusing to delete invalid regionId: $regionId")
            return false
        }
        val target = File(root, regionId).canonicalFile
        if (!target.toPath().startsWith(root.canonicalFile.toPath())) {
            warn("Refusing to delete out-of-bounds path: $target")
            return false
        }
        if (!target.exists()) return true
        return target.deleteRecursively()
    }

    /**
     * Public read of a single region directory. Returns null on read failure.
     * Used by tests; production code goes through [list] / [listFromRoot].
     */
    fun readDir(dir: File): RegionInfo? = runCatching { read(dir) }.getOrNull()

    /** Best-effort read of `meta.json`. Falls back to disk-derived defaults. */
    private fun read(dir: File): RegionInfo {
        val metaFile = File(dir, FILE_META_JSON)

        // Sum all the on-disk artifacts so the "size" column reflects what
        // the user actually paid for in flash space.
        val candidates = sequenceOf(
            FILE_TILES_PMTILES,
            FILE_BASEMAP_PMTILES,
            FILE_META_JSON,
        )
        val sizeOnDisk = candidates
            .map { File(dir, it) }
            .filter { it.exists() }
            .sumOf { it.length() }

        val meta: JSONObject = if (metaFile.exists() && metaFile.length() > 0) {
            runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: JSONObject()
        } else JSONObject()

        // Optional bbox (south, west, north, east). Only surfaced when all four
        // values are present — partial / malformed bbox arrays are dropped.
        val bbox: DoubleArray? = runCatching {
            if (!meta.has("bbox")) return@runCatching null
            val arr: JSONArray = meta.getJSONArray("bbox")
            if (arr.length() != 4) return@runCatching null
            DoubleArray(4) { i -> arr.getDouble(i) }
        }.getOrNull()

        // Source is either the object form { "kind": ..., "host": ..., "version":
        // ..., "downloadedAt": <ms> } (what downloads write) or a plain string on
        // older entries. Tolerate both.
        val (sourceKind, sourceHost) = run {
            val sourceObj = meta.optJSONObject("source")
            if (sourceObj != null) {
                sourceObj.optString("kind", "unknown") to
                    sourceObj.optString("host").takeIf { it.isNotBlank() }
            } else {
                meta.optString("source", "unknown") to null
            }
        }

        val layers: List<String> = runCatching {
            val arr = meta.optJSONArray("layers") ?: return@runCatching emptyList<String>()
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    arr.optString(i)?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())

        return RegionInfo(
            regionId = meta.optString("regionId", dir.name),
            displayName = meta.optString("displayName", dir.name),
            sizeBytes = if (meta.has("sizeBytes")) meta.optLong("sizeBytes", sizeOnDisk) else sizeOnDisk,
            downloadedAt = meta.optLong("downloadedAt", 0L).takeIf { it > 0L },
            lastUsedAt = meta.optLong("lastUsedAt", 0L).takeIf { it > 0L },
            source = sourceKind,
            sourceHost = sourceHost,
            layers = layers,
            directory = dir,
            bbox = bbox,
        )
    }

    /**
     * Defensive logger wrapper. Production calls go to `android.util.Log`;
     * unit tests run on the JVM where `Log` isn't classloaded, so we
     * runCatch around it and fall back to stderr.
     */
    private fun warn(message: String) {
        runCatching { Log.w(TAG, message) }
            .onFailure { System.err.println("$TAG: $message") }
    }
}

/**
 * What the bottom-sheet tab renders per row. Public because the
 * `RegionManagerFragment.Listener` overrides reference it; still plugin-internal
 * in meaning.
 *
 * - `bbox`: the south, west, north, east tuple from `meta.json` — used by Refresh
 *   to re-pick the same area without making the user re-draw.
 * - `source`: the source kind (`"iiab-lan"`, `"internet"`, or `"unknown"`) from
 *   `meta.source.kind`; older string-only entries surface as `"unknown"` or the
 *   legacy string value.
 * - `sourceHost`: the LAN host when known, shown as a sub-label so the user can
 *   see where each region came from.
 * - `layers`: bundled layers from `meta.layers` (e.g. `["vector", "basemap"]`).
 */
data class RegionInfo(
    val regionId: String,
    val displayName: String,
    val sizeBytes: Long,
    val downloadedAt: Long?,
    val lastUsedAt: Long?,
    val source: String,
    val sourceHost: String? = null,
    val layers: List<String> = emptyList(),
    val directory: File,
    val bbox: DoubleArray? = null,
) {
    // bbox is a DoubleArray so equality needs structural comparison; the
    // generated equals/hashCode for data classes uses array reference equality.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RegionInfo) return false
        if (regionId != other.regionId) return false
        if (displayName != other.displayName) return false
        if (sizeBytes != other.sizeBytes) return false
        if (downloadedAt != other.downloadedAt) return false
        if (lastUsedAt != other.lastUsedAt) return false
        if (source != other.source) return false
        if (sourceHost != other.sourceHost) return false
        if (layers != other.layers) return false
        if (directory != other.directory) return false
        if (bbox == null) {
            if (other.bbox != null) return false
        } else {
            if (other.bbox == null) return false
            if (!bbox.contentEquals(other.bbox)) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = regionId.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + (downloadedAt?.hashCode() ?: 0)
        result = 31 * result + (lastUsedAt?.hashCode() ?: 0)
        result = 31 * result + source.hashCode()
        result = 31 * result + (sourceHost?.hashCode() ?: 0)
        result = 31 * result + layers.hashCode()
        result = 31 * result + directory.hashCode()
        result = 31 * result + (bbox?.contentHashCode() ?: 0)
        return result
    }
}
