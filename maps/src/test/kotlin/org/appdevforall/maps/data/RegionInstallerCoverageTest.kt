package org.appdevforall.maps.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Coverage for [RegionInstaller].
 *
 * `apply` takes an injectable `cacheRoot` (null → resolved via
 * `RegionCache.rootDir()` *inside* the try). Passing a temp dir lets the whole
 * orchestration run on a plain JVM:
 *  - the success path (valid project + region under the cache root → copied → true),
 *  - the `!result.success` branch (ProjectMapEmitter rejects → logged → false),
 *  - and the cache-root-resolution failure (default null → `rootDir()` throws on the
 *    JVM → caught + routed to `logError` → false), in its injected- and default-
 *    logger shapes.
 *
 * The free-space precheck's *insufficient-space* arm needs a constrained volume
 * (a temp dir reports the host disk's usable space), so it's left to the android-qa
 * device walk; the precheck's compute + proceed path is exercised here.
 */
class RegionInstallerCoverageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun regionInfo(dir: File) = RegionInfo(
        regionId = "test-region",
        displayName = "Test Region",
        sizeBytes = 0L,
        downloadedAt = null,
        lastUsedAt = null,
        source = "internet",
        directory = dir,
    )

    /** A project dir that satisfies ProjectMapEmitter's "is a Maps project" gate. */
    private fun mapsProject(name: String): File {
        val project = tmp.newFolder(name)
        // app/ dir + a MapRegionActivity source anywhere under app/src/main.
        File(project, "app/src/main/java/com/example/MapRegionActivity.kt").apply {
            parentFile.mkdirs()
            writeText("class MapRegionActivity")
        }
        return project
    }

    @Test
    fun applyCopiesRegionDataIntoTheProjectAndReturnsTrue() {
        val cacheRoot = tmp.newFolder("cache-ok")
        val regionDir = File(cacheRoot, "test-region").apply { mkdirs() }
        File(regionDir, RegionCache.FILE_TILES_PMTILES).writeBytes(ByteArray(16))
        File(regionDir, RegionCache.FILE_BASEMAP_PMTILES).writeBytes(ByteArray(8))
        File(regionDir, RegionCache.FILE_META_JSON).writeText("{}")
        val project = mapsProject("project-ok")

        val result = RegionInstaller.apply(
            info = regionInfo(regionDir),
            projectDir = project,
            cacheRoot = cacheRoot,
        )

        assertTrue("a valid region + project must apply successfully", result)
        // The data landed in the project's flat maps assets with the right bytes.
        val dest = File(project, "app/src/main/assets/maps")
        assertEquals(16L, File(dest, RegionCache.FILE_TILES_PMTILES).length())
        assertEquals(8L, File(dest, RegionCache.FILE_BASEMAP_PMTILES).length())
        assertTrue(File(dest, RegionCache.FILE_META_JSON).exists())
    }

    @Test
    fun applyReturnsFalseWhenEmitterRejectsTheRegion() {
        // Region is under the cache root but has NO tiles.pmtiles, so
        // ProjectMapEmitter returns success=false — exercising RegionInstaller's
        // post-rootDir orchestration: the free-space `needed` compute + proceed,
        // the ProjectMapEmitter.apply call, and the `!result.success` log + false.
        val cacheRoot = tmp.newFolder("cache-notiles")
        val regionDir = File(cacheRoot, "test-region").apply { mkdirs() }
        File(regionDir, RegionCache.FILE_META_JSON).writeText("{}") // no tiles.pmtiles
        val project = mapsProject("project-notiles")

        val result = RegionInstaller.apply(
            info = regionInfo(regionDir),
            projectDir = project,
            cacheRoot = cacheRoot,
        )

        assertFalse("a region without tiles must be rejected", result)
        // Nothing copied into the project.
        assertFalse(
            File(project, "app/src/main/assets/maps/${RegionCache.FILE_TILES_PMTILES}").exists(),
        )
    }

    @Test
    fun applyRoutesFailureToInjectedLogger() {
        val project = tmp.newFolder("project")
        val regionDir = tmp.newFolder("cache", "test-region")
        var reportedMessage: String? = null
        var reportedThrowable: Throwable? = null

        val result = RegionInstaller.apply(
            info = regionInfo(regionDir),
            projectDir = project,
            logError = { msg, t -> reportedMessage = msg; reportedThrowable = t },
        )

        assertFalse("must return false on cache-root failure", result)
        assertNotNull("logError message must be supplied", reportedMessage)
        assertTrue(
            "message should name the failing region",
            reportedMessage!!.contains("test-region"),
        )
        assertNotNull("the caught throwable must be routed to logError", reportedThrowable)
    }

    @Test
    fun applyUsesDefaultNoOpLoggerWithoutThrowing() {
        // Calling apply without a logError lambda exercises the default no-op
        // parameter; the catch block must still swallow the exception and
        // return false rather than propagating.
        val project = tmp.newFolder("project2")
        val regionDir = tmp.newFolder("cache2", "test-region")

        val result = RegionInstaller.apply(
            info = regionInfo(regionDir),
            projectDir = project,
        )

        assertFalse("default-logger apply must still return false, not throw", result)
    }

    @Test
    fun applyRoutesTheExternalStorageGuardSpecifically() {
        // Pin the failure MODE, not just "some throwable". On the JVM the very
        // first statement in `apply` — `RegionCache.rootDir()` — throws an
        // IllegalStateException("External storage unavailable") because
        // Environment.getExternalStorageDirectory() returns null
        // (isReturnDefaultValues). If a future refactor reordered apply so a
        // DIFFERENT exception surfaced first (e.g. an NPE on info.directory), this
        // would catch it. The free-space precheck + ProjectMapEmitter copy +
        // !result.success branches all sit AFTER this throw and are unreachable on
        // a plain JVM (they need a resolvable cache root — Robolectric/Mockk or a
        // device); they're covered by ProjectMapEmitterTest + the android-qa walk.
        val project = tmp.newFolder("project3")
        val regionDir = tmp.newFolder("cache3", "test-region")
        var reportedThrowable: Throwable? = null

        val result = RegionInstaller.apply(
            info = regionInfo(regionDir),
            projectDir = project,
            logError = { _, t -> reportedThrowable = t },
        )

        assertFalse(result)
        assertTrue(
            "the routed failure must be an IllegalStateException",
            reportedThrowable is IllegalStateException,
        )
        assertEquals(
            "External storage unavailable",
            reportedThrowable?.message,
        )
    }
}
