package org.appdevforall.maps.templates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Branch-coverage supplement for [ProjectMapEmitter]. Covers the partial-region
 * branch (`tiles.pmtiles` present but the optional `basemap`/`meta` missing) where
 * the existence filter prunes the copy list but the required-tiles guard still
 * passes — the path the happy-path test (`ProjectMapEmitterTest`) skips by always
 * providing the full three-file set.
 */
class ProjectMapEmitterCoverageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var projectDir: File
    private lateinit var appDir: File
    private lateinit var cacheRoot: File

    @org.junit.Before
    fun setUp() {
        projectDir = tmp.newFolder("project")
        appDir = File(projectDir, "app").apply { mkdirs() }
        cacheRoot = tmp.newFolder("cache")
        // Make this a Maps project.
        File(appDir, "src/main/java/com/example/foo").apply { mkdirs() }
            .also { File(it, "MapRegionActivity.kt").writeText("// stub") }
    }

    private fun mapsDest(name: String) =
        File(appDir, "src/main/assets/maps/$name")

    @Test
    fun `apply succeeds copying only tiles when basemap and meta are absent`() {
        // Region has ONLY tiles.pmtiles — the existence filter drops basemap/meta
        // from the copy list, but the required-tiles guard still passes.
        val region = File(cacheRoot, "spain").apply { mkdirs() }
        File(region, "tiles.pmtiles").writeText("TILES")

        val result = ProjectMapEmitter.apply(projectDir, region, cacheRoot)
        assertTrue("apply should succeed with just tiles.pmtiles: ${result.reason}", result.success)
        assertTrue("tiles.pmtiles should be copied", mapsDest("tiles.pmtiles").exists())
        // The absent optional files are simply not written.
        assertFalse("basemap.pmtiles must not appear", mapsDest("basemap.pmtiles").exists())
        assertFalse("meta.json must not appear", mapsDest("meta.json").exists())
    }
}
