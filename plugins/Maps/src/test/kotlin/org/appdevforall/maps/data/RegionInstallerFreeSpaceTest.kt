package org.appdevforall.maps.data

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers [RegionInstaller.apply]'s free-space precheck refusing a region larger than the
 * project volume can hold, using a sparse tiles file whose length exceeds the free space.
 */
class RegionInstallerFreeSpaceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a region larger than the free space is refused before any copy starts`() = runBlocking {
        val project = tmp.newFolder("project")
        File(project, "app/src/main/java/com/example/MapRegionActivity.kt").apply {
            parentFile.mkdirs()
            writeText("class MapRegionActivity")
        }
        val cacheRoot = tmp.newFolder("cache")
        val regionDir = File(cacheRoot, "big-region").apply { mkdirs() }
        val usable = File(project, "app").usableSpace
        assertTrue("the test volume must report its free space", usable > 0)
        // Sparse: reports this length without allocating it.
        RandomAccessFile(File(regionDir, RegionCache.FILE_TILES_PMTILES), "rw").use {
            it.setLength(usable + (1L shl 30))
        }
        var chunks = 0
        val info = RegionInfo(
            regionId = "big-region", displayName = "Big", sizeBytes = 0L, downloadedAt = null,
            lastUsedAt = null, source = "internet", directory = regionDir,
        )

        val ok = RegionInstaller.apply(
            info, project, cacheRoot = cacheRoot,
            // Aborts the copy at once should the precheck ever let this region through.
            onChunk = { chunks++; throw IllegalStateException("copy must not start") },
        )

        assertFalse(ok)
        assertTrue("no chunk may be copied", chunks == 0)
        assertFalse(File(project, "app/src/main/assets/maps/${RegionCache.FILE_TILES_PMTILES}").exists())
    }
}
