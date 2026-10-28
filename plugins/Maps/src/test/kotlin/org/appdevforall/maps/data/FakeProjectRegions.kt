package org.appdevforall.maps.data

import java.io.File

/**
 * A [ProjectRegions] over a temp project dir: the active id is the real `active.txt` through
 * [ActiveRegionStore], and the region copy only records its call and returns [applyResult].
 */
internal class FakeProjectRegions(
    var projectDir: File?,
    var applyResult: Boolean = true,
) : ProjectRegions {

    val applied = mutableListOf<String>()

    override suspend fun currentProjectRoot(): File? = projectDir

    override suspend fun readActiveRegionId(projectDir: File): String? =
        ActiveRegionStore.read(projectDir, ProjectRegionCoordinator.DEFAULT_PROJECT_MAPS_SUBPATH)

    override suspend fun writeActiveRegionId(projectDir: File, regionId: String) {
        ActiveRegionStore.write(projectDir, ProjectRegionCoordinator.DEFAULT_PROJECT_MAPS_SUBPATH, regionId)
    }

    override suspend fun clearActiveRegionId(projectDir: File) {
        ActiveRegionStore.clear(projectDir, ProjectRegionCoordinator.DEFAULT_PROJECT_MAPS_SUBPATH)
    }

    override suspend fun applyAndActivate(info: RegionInfo, projectDir: File): Boolean {
        val ok = applyRegionToProject(info, projectDir)
        if (ok) writeActiveRegionId(projectDir, info.regionId)
        return ok
    }

    override suspend fun applyRegionToProject(info: RegionInfo, projectDir: File): Boolean {
        applied += info.regionId
        return applyResult
    }
}

/** A [RegionInfo] whose directory need not exist; enough for code that only reads its fields. */
internal fun regionInfo(id: String, name: String = "Name of $id", bbox: DoubleArray? = null) =
    RegionInfo(
        regionId = id,
        displayName = name,
        sizeBytes = 0L,
        downloadedAt = null,
        lastUsedAt = null,
        source = "internet",
        directory = File("/nonexistent/$id"),
        bbox = bbox,
    )
