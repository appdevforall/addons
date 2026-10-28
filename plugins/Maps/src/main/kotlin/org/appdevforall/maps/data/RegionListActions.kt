package org.appdevforall.maps.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.appdevforall.maps.domain.RegionWizardStateMachine.Event
import org.appdevforall.maps.util.runCatchingCancellable

/**
 * The region list's row actions (toggle active, delete, re-download) as plain suspend functions,
 * so the fragment only renders the result.
 *
 * @param listRegions the cached regions; the default reads the shared cache
 * @param deleteRegion deletes one cached region, returning false on failure
 */
internal class RegionListActions(
    private val project: ProjectRegions,
    private val listRegions: suspend () -> List<RegionInfo> = { RegionCache.list() },
    private val deleteRegion: suspend (RegionInfo) -> Boolean = { RegionCache.delete(it) },
) {

    /** What a toggle did, for the fragment to turn into a snackbar. */
    sealed class ToggleResult {
        object NoProject : ToggleResult()

        object ApplyFailed : ToggleResult()

        data class Activated(val name: String) : ToggleResult()

        data class Switched(val fromName: String, val toName: String) : ToggleResult()

        data class Deactivated(val name: String) : ToggleResult()
    }

    /**
     * Makes [info] the open project's active region, or clears the active region when [newActive]
     * is false. Activating copies the region in first and leaves `active.txt` alone if that fails.
     */
    suspend fun toggleActive(info: RegionInfo, newActive: Boolean): ToggleResult =
        withContext(Dispatchers.IO) {
            val projectDir = project.currentProjectRoot() ?: return@withContext ToggleResult.NoProject
            val priorActive = project.readActiveRegionId(projectDir)
            val priorName =
                priorActive?.let { activeId ->
                    runCatchingCancellable { listRegions() }
                        .getOrNull()
                        ?.firstOrNull { it.regionId == activeId }
                        ?.displayName
                }
            val ok =
                if (newActive) {
                    project.applyAndActivate(info, projectDir)
                } else {
                    project.clearActiveRegionId(projectDir)
                    true
                }
            when {
                !ok -> ToggleResult.ApplyFailed
                newActive && priorActive != null && priorActive != info.regionId ->
                    ToggleResult.Switched(priorName ?: priorActive, info.displayName)
                newActive -> ToggleResult.Activated(info.displayName)
                else -> ToggleResult.Deactivated(info.displayName)
            }
        }

    /**
     * Deletes [info] from the cache and, if it was the open project's active region, clears
     * `active.txt` so the next download can auto-activate. Returns whether the delete succeeded.
     */
    suspend fun delete(info: RegionInfo): Boolean =
        withContext(Dispatchers.IO) {
            val ok = deleteRegion(info)
            if (ok) {
                val projectDir = project.currentProjectRoot()
                if (projectDir != null && project.readActiveRegionId(projectDir) == info.regionId) {
                    project.clearActiveRegionId(projectDir)
                }
            }
            ok
        }

    /** The wizard event that re-opens the picker on [info]'s saved name and bounds. */
    fun redownloadRequest(info: RegionInfo): Event =
        Event.RedownloadRequested(
            regionId = info.regionId,
            displayName = info.displayName,
            bbox = info.bbox,
        )
}
