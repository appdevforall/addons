package org.appdevforall.maps.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.appdevforall.maps.util.AtomicFiles
import java.io.File

/**
 * Read/write the per-project "active region" pointer.
 *
 * Each project records which cached region is bundled into its builds in
 * `<projectDir>/<mapsSubpath>/active.txt` — the canonical sentinel. An older
 * `region-id.txt` marker is still honoured on read so projects created before
 * `active.txt` existed keep working; [clear] removes both so a deactivate toggle
 * can't be silently undone by a surviving legacy marker.
 */
internal object ActiveRegionStore {

    /** Canonical per-project sentinel naming the active region. */
    const val ACTIVE_REGION_FILE = "active.txt"

    /** Legacy top-level marker, honoured on read for backward compatibility. */
    const val REGION_MARKER_FILE = "region-id.txt"

    /** Cap on a marker file's read size — defensive bound so a corrupt sentinel
     *  can't OOM the panel (CodeRabbit resource-bounds theme). */
    private const val MARKER_MAX_BYTES = 1024L

    private const val TAG = "MapsPlugin.ActiveRegion"

    /**
     * Read the currently active regionId for the project, or null when none is
     * set / the sentinel is missing, oversized, blank, or invalid.
     *
     * Prefers [ACTIVE_REGION_FILE]; falls back to the legacy [REGION_MARKER_FILE].
     */
    suspend fun read(projectDir: File, mapsSubpath: String): String? = withContext(Dispatchers.IO) {
        readMarker(File(projectDir, "$mapsSubpath/$ACTIVE_REGION_FILE"))
            ?: readMarker(File(projectDir, "$mapsSubpath/$REGION_MARKER_FILE"))
    }

    /**
     * Write [regionId] into the project's [ACTIVE_REGION_FILE]. No-op (with a
     * warning) when [regionId] fails [RegionCache.isValidRegionId], so a bad id
     * can never land in the sentinel.
     */
    suspend fun write(projectDir: File, mapsSubpath: String, regionId: String) =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "write: enter regionId=$regionId projectDir=$projectDir")
            if (!RegionCache.isValidRegionId(regionId)) {
                Log.w(TAG, "Refusing to write invalid regionId to active.txt: $regionId")
                return@withContext
            }
            val mapsRoot = File(projectDir, mapsSubpath).apply { mkdirs() }
            val dest = File(mapsRoot, ACTIVE_REGION_FILE)
            Log.i(TAG, "write: about to write $dest")
            runCatching {
                AtomicFiles.writeText(dest, regionId)
            }.onSuccess {
                Log.i(TAG, "write: wrote ok, dest.exists=${dest.exists()} size=${dest.length()}")
            }.onFailure {
                Log.e(TAG, "write FAILED for dest=$dest", it)
            }
        }

    /**
     * Clear the active pointer. Deletes BOTH [ACTIVE_REGION_FILE] and the legacy
     * [REGION_MARKER_FILE]: if the legacy marker survives, [read] falls back to
     * it and the project still reports a region as active, so the deactivate
     * toggle would look ignored.
     */
    suspend fun clear(projectDir: File, mapsSubpath: String) = withContext(Dispatchers.IO) {
        listOf(ACTIVE_REGION_FILE, REGION_MARKER_FILE).forEach { name ->
            val f = File(projectDir, "$mapsSubpath/$name")
            if (f.exists()) {
                runCatching { f.delete() }
                    .onFailure { Log.w(TAG, "clear: couldn't delete $name: ${it.message}") }
            }
        }
    }

    /**
     * Read one marker file: must exist, be a regular file, be within
     * [MARKER_MAX_BYTES], and hold a non-blank, valid regionId. Returns null
     * otherwise.
     */
    private fun readMarker(marker: File): String? {
        if (marker.exists() && marker.isFile && marker.length() <= MARKER_MAX_BYTES) {
            return runCatching { marker.readText().trim() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() && RegionCache.isValidRegionId(it) }
        }
        return null
    }
}
