package org.appdevforall.maps.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for the data-only [ProjectMapEmitter]. Pure-disk tests — no Android,
 * no coroutines. Each test builds a fake project + a fake cached region in temp
 * dirs and asserts the flat-overwrite copy + the require-a-Maps-project gate.
 */
class ProjectMapEmitterTest {

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
    }

    /** Drop a MapRegionActivity source so the project counts as a Maps project. */
    private fun addMapRegionActivity(kotlin: Boolean = false) {
        val pkgDir = File(appDir, "src/main/java/com/example/foo").apply { mkdirs() }
        val name = if (kotlin) "MapRegionActivity.kt" else "MapRegionActivity.java"
        File(pkgDir, name).writeText("// stub")
    }

    /** Create a cached region dir under the cache root with the given file names. */
    private fun makeRegion(id: String, files: List<String>): File {
        val dir = File(cacheRoot, id).apply { mkdirs() }
        files.forEach { File(dir, it).writeText("$it-bytes") }
        return dir
    }

    private val fullRegionFiles =
        listOf("tiles.pmtiles", "basemap.pmtiles", "meta.json")

    private fun mapsDest(name: String) =
        File(appDir, "src/main/assets/maps/$name")

    // ----- require-a-Maps-project gate -----

    @Test
    fun `apply fails when project has no MapRegionActivity`() {
        val region = makeRegion("spain", fullRegionFiles)
        val result = ProjectMapEmitter.apply(projectDir, region, cacheRoot)
        assertFalse(result.success)
        assertNotNull(result.reason)
        assertTrue(
            "reason should tell the user to create a Maps project: ${result.reason}",
            result.reason!!.contains("Maps project"),
        )
    }

    @Test
    fun `apply fails cleanly when no app dir`() {
        val empty = tmp.newFolder("empty-project")
        val region = makeRegion("spain", fullRegionFiles)
        val result = ProjectMapEmitter.apply(empty, region, cacheRoot)
        assertFalse(result.success)
        assertEquals("no app/ dir in $empty", result.reason)
    }

    @Test
    fun `hasMapRegionActivity detects kotlin and java variants`() {
        assertFalse(ProjectMapEmitter.hasMapRegionActivity(appDir))
        addMapRegionActivity(kotlin = true)
        assertTrue(ProjectMapEmitter.hasMapRegionActivity(appDir))
    }

    // ----- flat data copy -----

    @Test
    fun `apply copies the four data files flat into assets maps`() {
        addMapRegionActivity()
        val region = makeRegion("spain", fullRegionFiles)
        val result = ProjectMapEmitter.apply(projectDir, region, cacheRoot)
        assertTrue("apply should succeed: ${result.reason}", result.success)
        for (name in fullRegionFiles) {
            assertTrue("$name should be copied flat", mapsDest(name).exists())
        }
        // No per-region subdir, no markers.
        assertFalse(File(appDir, "src/main/assets/maps/spain").exists())
        assertFalse(mapsDest("active.txt").exists())
        assertFalse(mapsDest("region-id.txt").exists())
    }

    @Test
    fun `apply invokes onChunk during the copy as a cancellation seam`() {
        addMapRegionActivity()
        val region = makeRegion("spain", fullRegionFiles)
        var calls = 0
        val result = ProjectMapEmitter.apply(projectDir, region, cacheRoot) { calls++ }
        assertTrue("apply should succeed: ${result.reason}", result.success)
        assertTrue("onChunk should be threaded through to the copy", calls >= 1)
    }

    @Test
    fun `apply reports failure and cleans up when onChunk throws mid-copy`() {
        addMapRegionActivity()
        val region = makeRegion("spain", fullRegionFiles)
        val result = ProjectMapEmitter.apply(projectDir, region, cacheRoot) {
            throw IllegalStateException("cancelled")
        }
        assertFalse("apply must report failure when the copy is cancelled", result.success)
        // Any files written before the throw are rolled back.
        for (name in fullRegionFiles) {
            assertFalse("$name should be cleaned up on cancel", mapsDest(name).exists())
        }
    }

    @Test
    fun `apply overwrites a previously-applied region`() {
        addMapRegionActivity()
        // First region.
        val first = makeRegion("spain", fullRegionFiles)
        File(first, "tiles.pmtiles").writeText("SPAIN")
        assertTrue(ProjectMapEmitter.apply(projectDir, first, cacheRoot).success)
        assertEquals("SPAIN", mapsDest("tiles.pmtiles").readText())

        // Second region overwrites flat.
        val second = makeRegion("dakar", fullRegionFiles)
        File(second, "tiles.pmtiles").writeText("DAKAR")
        assertTrue(ProjectMapEmitter.apply(projectDir, second, cacheRoot).success)
        assertEquals("DAKAR", mapsDest("tiles.pmtiles").readText())
        // Still flat — no spain/ or dakar/ subdir.
        assertFalse(File(appDir, "src/main/assets/maps/spain").exists())
        assertFalse(File(appDir, "src/main/assets/maps/dakar").exists())
    }

    @Test
    fun `apply fails when region has no tiles pmtiles`() {
        addMapRegionActivity()
        val region = makeRegion("spain", listOf("basemap.pmtiles", "meta.json"))
        val result = ProjectMapEmitter.apply(projectDir, region, cacheRoot)
        assertFalse(result.success)
        assertNotNull(result.reason)
    }

    // ----- containment guard -----

    @Test
    fun `apply refuses a source outside the cache root`() {
        addMapRegionActivity()
        // Region dir lives OUTSIDE the declared cache root.
        val rogue = tmp.newFolder("rogue", "spain")
        File(rogue, "tiles.pmtiles").writeText("x")
        val result = ProjectMapEmitter.apply(projectDir, rogue, cacheRoot)
        assertFalse(result.success)
        assertNotNull(result.reason)
    }

    @Test
    fun `apply does not write code or patch gradle or manifest`() {
        addMapRegionActivity()
        val region = makeRegion("spain", fullRegionFiles)
        ProjectMapEmitter.apply(projectDir, region, cacheRoot)
        // No emitted source, no style.json, no manifest, no gradle written by apply.
        assertFalse(
            "apply must not emit a Java activity",
            File(appDir, "src/main/java/com/example/foo/PmtilesHttpServer.java").exists(),
        )
        assertFalse(
            "apply must not write style.json",
            mapsDest("style.json").exists(),
        )
        assertFalse(
            "apply must not create a manifest",
            File(appDir, "src/main/AndroidManifest.xml").exists(),
        )
        assertFalse(
            "apply must not create build.gradle.kts",
            File(appDir, "build.gradle.kts").exists(),
        )
    }
}
