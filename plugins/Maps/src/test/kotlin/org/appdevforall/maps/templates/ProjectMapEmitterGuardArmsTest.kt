package org.appdevforall.maps.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Two [ProjectMapEmitter.apply] arms the main emitter suites leave open:
 *
 *  - a JAVA-language Maps project (`MapRegionActivity.java`, no `.kt`) must be
 *    recognized by `hasMapRegionActivity` — both template languages are
 *    first-class (see the team's both-language-paths convention);
 *  - the maps assets dir resolving OUTSIDE the project (symlinked
 *    `assets/maps` — e.g. a user "optimizing" storage by linking assets to an
 *    SD card path) must be refused by the canonical-containment guard, not
 *    written through.
 */
class ProjectMapEmitterGuardArmsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Minimal region dir (with tiles.pmtiles) inside a cache root. */
    private fun seedRegion(cacheRoot: File): File {
        val region = File(cacheRoot, "testregion").apply { mkdirs() }
        File(region, "tiles.pmtiles").writeBytes(ByteArray(32) { it.toByte() })
        return region
    }

    @Test
    fun applies_into_a_java_language_maps_project() {
        val project = tmp.newFolder("javaproj")
        val javaDir = File(project, "app/src/main/java/com/example").apply { mkdirs() }
        // A neighbouring non-activity file first in walk order: the detector
        // must skip past it (neither name matches) and still find the .java.
        File(javaDir, "AndroidManifest.xml").writeText("<manifest/>")
        File(javaDir, "MapRegionActivity.java").writeText("public class MapRegionActivity {}")
        val cacheRoot = tmp.newFolder("cache")
        val region = seedRegion(cacheRoot)

        val result = ProjectMapEmitter.apply(project, region, cacheRoot)

        assertTrue("Java-only Maps project must be accepted: ${result.reason}", result.success)
        val copied = File(project, "app/src/main/assets/maps/tiles.pmtiles")
        assertTrue("tiles must be copied into the Java project", copied.exists())
        assertEquals(32L, copied.length())
    }

    @Test
    fun refuses_when_maps_assets_dir_symlinks_out_of_the_project() {
        val project = tmp.newFolder("linkproj")
        val ktDir = File(project, "app/src/main/kotlin/com/example").apply { mkdirs() }
        File(ktDir, "MapRegionActivity.kt").writeText("class MapRegionActivity")
        // assets/ exists, but assets/maps is a symlink escaping the project.
        val assets = File(project, "app/src/main/assets").apply { mkdirs() }
        val outside = tmp.newFolder("outside-target")
        val link = File(assets, "maps")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: Exception) {
            assumeTrue("filesystem doesn't support symlinks here", false)
        }
        val cacheRoot = tmp.newFolder("cache")
        val region = seedRegion(cacheRoot)

        val result = ProjectMapEmitter.apply(project, region, cacheRoot)

        assertFalse("symlinked maps dir must be refused", result.success)
        assertTrue(
            "reason should name the containment failure, got: ${result.reason}",
            result.reason!!.contains("maps dir escapes"),
        )
        assertFalse(
            "nothing may be written through the escaping link",
            File(outside, "tiles.pmtiles").exists(),
        )
    }
}
