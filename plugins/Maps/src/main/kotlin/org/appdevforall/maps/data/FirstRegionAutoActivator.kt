package org.appdevforall.maps.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decides — and executes — auto-activation of the **first** region in a
 * project's cache. When the user downloads their very first region for a
 * project, the wizard should not also force them to tap "Use in this project"
 * before they can build; this object handles that handoff.
 *
 * Decision table:
 *
 * | Project state                                  | Behavior              |
 * | ---------------------------------------------- | --------------------- |
 * | No `active.txt` (or blank)                     | Apply + write active  |
 * | `active.txt` already names a region            | No-op (user's choice) |
 * | Region missing from cache (race / corruption)  | No-op (nothing to do) |
 *
 * The file-copy + ProjectMapEmitter wiring and the active-sentinel write are
 * injected as lambdas so the policy stays decoupled from the Android machinery.
 */
internal object FirstRegionAutoActivator {

    sealed class Result {
        /** Project already had an active region — left alone. */
        object NoOpAlreadyActive : Result()

        /** [downloadedRegionId] wasn't found in the cache root. */
        object NoOpRegionNotFound : Result()

        /** First region auto-activated successfully. */
        data class Activated(val regionId: String, val displayName: String) : Result()

        /** Apply step (file copy + manifest patch) failed. */
        data class ApplyFailed(val regionId: String, val reason: String) : Result()
    }

    /**
     * Apply [downloadedRegionId] as the project's active region if (and only if)
     * the project doesn't already have one. Pure I/O against [projectDir] and
     * [regionsCacheRoot]; no UI, no coroutine launching.
     *
     * @param projectDir            project root (where `app/src/main/assets/maps/` lives)
     * @param mapsSubpath           subpath under the project where active.txt is kept
     *                              (default: `app/src/main/assets/maps`)
     * @param regionsCacheRoot      `/sdcard/CodeOnTheGo/maps/` root in production
     * @param downloadedRegionId    regionId that just finished downloading
     * @param applyRegionToProject  injectable file-copy + emitter callback;
     *                              returns true on success
     * @param writeActiveRegion     injectable writer for the active.txt sentinel
     */
    suspend fun maybeAutoActivate(
        projectDir: File,
        mapsSubpath: String,
        regionsCacheRoot: File,
        downloadedRegionId: String,
        applyRegionToProject: suspend (info: RegionInfo, projectDir: File) -> Boolean,
        writeActiveRegion: suspend (projectDir: File, regionId: String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val mapsRoot = File(projectDir, mapsSubpath)
        val activeFile = File(mapsRoot, "active.txt")
        val existingActive = readActiveSentinel(activeFile)
        // Look up the cache list once — used for both the stale-sentinel
        // check below AND the downloaded-region lookup further down.
        val cacheEntries = runCatching { RegionCache.listFromRoot(regionsCacheRoot) }
            .getOrNull() ?: emptyList()
        if (!existingActive.isNullOrBlank()) {
            // Verify the named region still exists. If the user deleted the
            // previously-active region, the sentinel is stale — treat the project
            // as having no active region so the new download auto-activates.
            val activeStillExists = cacheEntries.any { it.regionId == existingActive }
            if (activeStillExists) return@withContext Result.NoOpAlreadyActive
            // else fall through — sentinel is stale.
        }
        val info = cacheEntries.firstOrNull { it.regionId == downloadedRegionId }
            ?: return@withContext Result.NoOpRegionNotFound

        val applied = applyRegionToProject(info, projectDir)
        if (!applied) {
            // Don't write active.txt if files weren't actually copied — would
            // produce a project that references a region but doesn't have its
            // tiles, failing the build with a misleading "missing tiles" error.
            return@withContext Result.ApplyFailed(info.regionId, "applyRegionToProject returned false")
        }
        writeActiveRegion(projectDir, info.regionId)
        Result.Activated(info.regionId, info.displayName)
    }

    private fun readActiveSentinel(file: File): String? {
        if (!file.exists() || !file.isFile) return null
        // Bounded read so a corrupt sentinel can't OOM the caller.
        if (file.length() > 256L) return null
        return runCatching { file.readText().trim() }.getOrNull()
    }
}
