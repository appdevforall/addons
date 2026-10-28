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

    /** Where the completion left the project; [displayName] is the region's name for the snackbar. */
    sealed class Outcome {
        abstract val displayName: String

        data class NoProject(override val displayName: String) : Outcome()

        data class Finished(
            val projectDir: File,
            val result: FirstRegionAutoActivator.Result,
            override val displayName: String,
        ) : Outcome()
    }

    /** What the snackbar's "Apply" did. */
    sealed class ApplyResult {
        object Failed : ApplyResult()

        data class Switched(val name: String) : ApplyResult()

        /** The region was already active; the apply refreshed the project's copy. */
        data class AlreadyActive(val name: String) : ApplyResult()
    }

    /** Auto-activates [regionId] in the open project when that project has no active region yet. */
    suspend fun complete(regionId: String): Outcome =
        withContext(Dispatchers.IO) {
            val name = findRegion(regionId)?.displayName ?: regionId
            val projectDir = project.currentProjectRoot() ?: return@withContext Outcome.NoProject(name)
            val result =
                FirstRegionAutoActivator.maybeAutoActivate(
                    projectDir = projectDir,
                    mapsSubpath = ProjectRegionCoordinator.DEFAULT_PROJECT_MAPS_SUBPATH,
                    regionsCacheRoot = cacheRoot(),
                    downloadedRegionId = regionId,
                    applyRegionToProject = project::applyRegionToProject,
                    writeActiveRegion = project::writeActiveRegionId,
                )
            Outcome.Finished(projectDir, result, name)
        }

    /** Copies [regionId] into [projectDir] and makes it active; [ApplyResult.Failed] if it is gone. */
    suspend fun applyFromSnackbar(projectDir: File, regionId: String): ApplyResult =
        withContext(Dispatchers.IO) {
            val info = findRegion(regionId) ?: return@withContext ApplyResult.Failed
            val wasActive = project.readActiveRegionId(projectDir) == regionId
            when {
                !project.applyAndActivate(info, projectDir) -> ApplyResult.Failed
                wasActive -> ApplyResult.AlreadyActive(info.displayName)
                else -> ApplyResult.Switched(info.displayName)
            }
        }

    private suspend fun findRegion(regionId: String): RegionInfo? =
        runCatchingCancellable { listRegions() }.getOrNull()?.firstOrNull { it.regionId == regionId }
}
