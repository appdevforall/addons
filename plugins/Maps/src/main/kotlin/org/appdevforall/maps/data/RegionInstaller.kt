package org.appdevforall.maps.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.appdevforall.maps.templates.ProjectMapEmitter
import java.io.File

/**
 * Applies a cached region to an open Maps project as a pure **data copy**: copies
 * the region's data files (`tiles.pmtiles`, `basemap.pmtiles`, `meta.json`) into
 * the project's fixed, flat asset location
 * `app/src/main/assets/maps/`, overwriting any previous region.
 *
 * The actual verify + copy (require-a-Maps-project gate, canonical-containment
 * guards, atomic copy, cleanup-on-failure) lives in [ProjectMapEmitter]; this
 * object adds a free-space precheck and routes failures to the plugin logger.
 *
 * No code injection, no manifest/Gradle patching, no active/region-id markers, no
 * per-region subdir, no pruning — the emitted project is single-region and ships
 * all of its own wiring (see [org.appdevforall.maps.templates.MapTemplateBuilder]).
 */
internal object RegionInstaller {

    private const val TAG = "MapsPlugin.RegionInstaller"

    /**
     * Copy [info]'s data files into [projectDir]'s fixed flat maps assets,
     * overwriting any previous region.
     *
     * @param cacheRoot the regions cache root, passed through to
     *   [ProjectMapEmitter.apply]. Null (the default) resolves it via
     *   [RegionCache.rootDir] *inside* the try block (so a resolution failure is
     *   still caught + routed); JVM tests pass a temp dir to exercise the whole copy
     *   without `Environment`'s external storage, which is null on a plain JVM.
     * @param logError routes a failure (with the caught exception) to the plugin
     *   logger in addition to logcat; defaults to a no-op for tests.
     * @param onChunk cooperative-cancellation seam invoked between copy chunks —
     *   the caller (always inside `withContext(Dispatchers.IO)`) passes
     *   `{ coroutineContext.ensureActive() }` so closing the bottom sheet mid-copy
     *   of a large `tiles.pmtiles` aborts promptly. Defaults to a no-op for tests.
     * @return true if the region data was copied successfully.
     */
    suspend fun apply(
        info: RegionInfo,
        projectDir: File,
        cacheRoot: File? = null,
        logError: (String, Throwable) -> Unit = { _, _ -> },
        onChunk: () -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val root = cacheRoot ?: RegionCache.rootDir()
            val canonicalSrc = info.directory.canonicalFile

            // Free-space precheck: bail before writing if the volume can't fit the
            // copy plus a 1 MB safety margin. `usableSpace` returns 0 both for a
            // genuinely-full volume and when it can't be read; treat 0 as
            // "can't verify" and proceed — ProjectMapEmitter's atomic copy cleans
            // up a partial write if a mid-copy ENOSPC hits anyway.
            val needed = listOf(
                RegionCache.FILE_TILES_PMTILES,
                RegionCache.FILE_BASEMAP_PMTILES,
                RegionCache.FILE_META_JSON,
            ).map { File(canonicalSrc, it) }
                .filter { it.exists() }
                .sumOf { it.length() }
            val safetyMargin = 1L * 1024L * 1024L
            val usable = File(projectDir, "app").usableSpace
            if (usable in 1 until needed + safetyMargin) {
                Log.w(
                    TAG,
                    "Insufficient space to apply region ${info.regionId}: " +
                        "need ${needed + safetyMargin}, have $usable",
                )
                return@withContext false
            }

            val result = ProjectMapEmitter.apply(projectDir, canonicalSrc, root, onChunk)
            if (!result.success) {
                Log.w(TAG, "apply region ${info.regionId} failed: ${result.reason}")
            }
            result.success
        } catch (e: Exception) {
            // Log.e to logcat too (the plugin logger only routes to CoGo's own
            // log file, invisible via `adb logcat`).
            Log.e(TAG, "applyRegionToProject failed for ${info.regionId}", e)
            logError("applyRegionToProject failed for ${info.regionId}", e)
            false
        }
    }
}
