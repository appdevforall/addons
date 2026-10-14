package org.appdevforall.maps.data

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.IdeProjectService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * The project/active-region facade, extracted from `RegionManagerFragment` —
 * resolving the open project, reading/writing the per-project `active.txt`
 * sentinel, and copying a cached region's data into the project are all file
 * I/O + host-service work that a Fragment should only *route*, never own.
 *
 * Thin, deliberate delegation layer over [ActiveRegionStore] (the sentinel),
 * [RegionInstaller] (the copy), and [IdeProjectService] (the open project):
 * its one piece of policy is [applyAndActivate]'s pairing rule — never write
 * `active.txt` unless the copy succeeded.
 *
 * @param pluginContextProvider resolves the live [PluginContext] on every call
 *   (the plugin's static is volatile — a plugin reload must not leave the
 *   coordinator holding a stale reference). Returns null when the plugin has
 *   been disposed, which degrades every method to a harmless no-op/null.
 * @param mapsSubpath subpath under the project where this plugin lands map
 *   data — both the region-data copies and the `active.txt` sentinel.
 *   Injectable for tests; production uses [DEFAULT_PROJECT_MAPS_SUBPATH].
 */
internal class ProjectRegionCoordinator(
    private val pluginContextProvider: () -> PluginContext?,
    private val mapsSubpath: String = DEFAULT_PROJECT_MAPS_SUBPATH,
) {

    companion object {
        /**
         * Default subpath under the open project where this plugin lands map
         * data. Used both for "use in this project" copies and the per-project
         * active.txt sentinel.
         */
        const val DEFAULT_PROJECT_MAPS_SUBPATH = "app/src/main/assets/maps"
    }

    /**
     * Resolve the current project root via [IdeProjectService]. Returns null
     * when no project is open or the service isn't available (running in
     * standalone test mode).
     */
    suspend fun currentProjectRoot(): File? = withContext(Dispatchers.IO) {
        val ctx = pluginContextProvider() ?: return@withContext null
        val project = ctx.services.get(IdeProjectService::class.java)?.getCurrentProject()
        project?.rootDir
    }

    /**
     * Read the active regionId for the currently-open project, or null when no
     * project is open. Thin delegate to [ActiveRegionStore.read].
     */
    suspend fun readActiveRegionId(): String? {
        val projectDir = currentProjectRoot() ?: return null
        return readActiveRegionId(projectDir)
    }

    suspend fun readActiveRegionId(projectDir: File): String? =
        ActiveRegionStore.read(projectDir, mapsSubpath)

    suspend fun writeActiveRegionId(projectDir: File, regionId: String) {
        ActiveRegionStore.write(projectDir, mapsSubpath, regionId)
    }

    suspend fun clearActiveRegionId(projectDir: File) {
        ActiveRegionStore.clear(projectDir, mapsSubpath)
    }

    /**
     * Copy [info] into the project AND mark it active — the "apply + activate" pair that the
     * download-complete, snackbar-apply, and toggle-on flows all need. Consolidated here so the
     * pairing (never write active.txt unless the copy succeeded) lives in one place.
     */
    suspend fun applyAndActivate(info: RegionInfo, projectDir: File): Boolean {
        val applied = applyRegionToProject(info, projectDir)
        if (applied) writeActiveRegionId(projectDir, info.regionId)
        return applied
    }

    /**
     * Copy the cached region's data files into [projectDir]'s fixed flat maps
     * assets, overwriting any previous region. Pure data copy — the project ships
     * its own MapLibre wiring (see [org.appdevforall.maps.templates.MapTemplateBuilder]).
     * Fails if the project isn't a Maps project (no `MapRegionActivity`).
     *
     * The per-project active state (active.txt) is written separately via
     * [writeActiveRegionId] so the toggle flow can decouple "copy data" from
     * "mark active".
     */
    suspend fun applyRegionToProject(
        info: RegionInfo,
        projectDir: File,
    ): Boolean {
        // Capture the calling coroutine's context so the (potentially 100+ MB)
        // tiles.pmtiles copy checks for cancellation between chunks — closing the
        // bottom sheet / switching projects mid-copy aborts promptly.
        val ctx = coroutineContext
        return RegionInstaller.apply(
            info = info,
            projectDir = projectDir,
            logError = { msg, t -> pluginContextProvider()?.logger?.error(msg, t) },
            onChunk = { ctx.ensureActive() },
        )
    }
}
