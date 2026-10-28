package org.appdevforall.maps.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.appdevforall.maps.util.runCatchingCancellable

/**
 * The bookkeeping after a download finishes: auto-activate the first region in the open project,
 * and the snackbar's "Apply" when the project already had one.
 *
 * @param listRegions the cached regions; the default reads the shared cache
 * @param cacheRoot the cache root [FirstRegionAutoActivator] reads; the default is the shared one
 */
internal class DownloadCompletion(
    private val project: ProjectRegions,
    private val listRegions: suspend () -> List<RegionInfo> = { RegionCache.list() },
    private val cacheRoot: suspend () -> File = { RegionCache.rootDir() },
) {

    /** Where the completion left the project. */
    sealed class Outcome {
        object NoProject : Outcome()

        data class Finished(val projectDir: File, val result: FirstRegionAutoActivator.Result) :
            Outcome()
    }

    /** Auto-activates [regionId] in the open project when that project has no active region yet. */
    suspend fun complete(regionId: String): Outcome =
        withContext(Dispatchers.IO) {
            val projectDir = project.currentProjectRoot() ?: return@withContext Outcome.NoProject
            val result =
                FirstRegionAutoActivator.maybeAutoActivate(
                    projectDir = projectDir,
                    mapsSubpath = ProjectRegionCoordinator.DEFAULT_PROJECT_MAPS_SUBPATH,
                    regionsCacheRoot = cacheRoot(),
                    downloadedRegionId = regionId,
                    applyRegionToProject = project::applyRegionToProject,
                    writeActiveRegion = project::writeActiveRegionId,
                )
            Outcome.Finished(projectDir, result)
        }

    /** Copies [regionId] into [projectDir] and makes it active; false if it is gone from the cache. */
    suspend fun applyFromSnackbar(projectDir: File, regionId: String): Boolean =
        withContext(Dispatchers.IO) {
            val info =
                runCatchingCancellable { listRegions() }.getOrNull()?.firstOrNull { it.regionId == regionId }
                    ?: return@withContext false
            project.applyAndActivate(info, projectDir)
        }
}
