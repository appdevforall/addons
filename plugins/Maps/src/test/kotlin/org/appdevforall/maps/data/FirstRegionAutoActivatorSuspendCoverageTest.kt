package org.appdevforall.maps.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Coverage top-up for [FirstRegionAutoActivator] beyond the policy suites:
 *
 *  - the injected `applyRegionToProject` / `writeActiveRegion` callbacks
 *    GENUINELY suspend (production passes real I/O suspend lambdas; the other
 *    suites' fakes return synchronously, so the coroutine resume arms of
 *    `maybeAutoActivate` never run there);
 *  - an EXISTING but UNREADABLE `active.txt` — `readActiveSentinel`'s
 *    `runCatching { readText() }` failure arm → treated as "no active region",
 *    so the new download still auto-activates instead of wedging the project.
 */
class FirstRegionAutoActivatorSuspendCoverageTest {

    private lateinit var projectDir: File
    private lateinit var cacheRoot: File
    private val mapsSubpath = "app/src/main/assets/maps"

    @Before
    fun setUp() {
        projectDir = Files.createTempDirectory("maps-fra-susp-project").toFile()
        cacheRoot = Files.createTempDirectory("maps-fra-susp-cache").toFile()
    }

    @After
    fun tearDown() {
        // Restore readability so deleteRecursively can't be tripped up by the
        // unreadable-sentinel test's chmod.
        projectDir.walkBottomUp().forEach { it.setReadable(true) }
        projectDir.deleteRecursively()
        cacheRoot.deleteRecursively()
    }

    private fun seedCachedRegion(regionId: String, displayName: String = regionId) {
        val dir = File(cacheRoot, regionId).apply { mkdirs() }
        File(dir, RegionCache.FILE_META_JSON).writeText(
            JSONObject().apply {
                put("regionId", regionId)
                put("displayName", displayName)
            }.toString()
        )
        File(dir, RegionCache.FILE_TILES_PMTILES).writeBytes(ByteArray(16))
    }

    @Test
    fun activates_when_apply_and_write_callbacks_genuinely_suspend() = runBlocking {
        // Production wires real suspend I/O into both callbacks; the activator
        // must come back through its resume path with the same Activated result
        // and the same ordering guarantee (write only after apply succeeded).
        seedCachedRegion("timbuktu", "Timbuktu, Mali")
        val order = mutableListOf<String>()

        val result = FirstRegionAutoActivator.maybeAutoActivate(
            projectDir = projectDir,
            mapsSubpath = mapsSubpath,
            regionsCacheRoot = cacheRoot,
            downloadedRegionId = "timbuktu",
            applyRegionToProject = { info, _ ->
                delay(5)               // force a real suspension point
                order += "apply:${info.regionId}"
                true
            },
            writeActiveRegion = { dir, id ->
                delay(5)               // force a real suspension point
                order += "write:$id"
                val mapsRoot = File(dir, mapsSubpath).apply { mkdirs() }
                File(mapsRoot, "active.txt").writeText(id)
            },
        )

        assertTrue("Expected Activated, got $result", result is FirstRegionAutoActivator.Result.Activated)
        assertEquals("Timbuktu, Mali", (result as FirstRegionAutoActivator.Result.Activated).displayName)
        assertEquals(
            "apply must complete before the active sentinel is written",
            listOf("apply:timbuktu", "write:timbuktu"),
            order,
        )
        assertEquals(
            "timbuktu",
            File(projectDir, "$mapsSubpath/active.txt").readText().trim(),
        )
    }

    @Test
    fun apply_failure_still_propagates_through_a_suspending_callback() = runBlocking {
        // The ApplyFailed arm reached via a genuinely-suspending apply lambda —
        // the resume path must carry the `false` back into the !applied branch.
        seedCachedRegion("mopti")
        var writeCalled = false

        val result = FirstRegionAutoActivator.maybeAutoActivate(
            projectDir = projectDir,
            mapsSubpath = mapsSubpath,
            regionsCacheRoot = cacheRoot,
            downloadedRegionId = "mopti",
            applyRegionToProject = { _, _ ->
                delay(5)
                false
            },
            writeActiveRegion = { _, _ -> writeCalled = true },
        )

        assertTrue("Expected ApplyFailed, got $result", result is FirstRegionAutoActivator.Result.ApplyFailed)
        assertEquals("mopti", (result as FirstRegionAutoActivator.Result.ApplyFailed).regionId)
        assertTrue("active sentinel must not be written on apply failure", !writeCalled)
    }

    @Test
    fun activates_when_existing_sentinel_is_unreadable() = runBlocking {
        // active.txt exists, is a small regular file, but can't be read →
        // readActiveSentinel's runCatching yields null → no active region →
        // the new download auto-activates (rather than NoOpAlreadyActive
        // on a sentinel whose content we couldn't even see).
        seedCachedRegion("zinder", "Zinder")
        val mapsRoot = File(projectDir, mapsSubpath).apply { mkdirs() }
        val sentinel = File(mapsRoot, "active.txt").apply { writeText("zinder") }
        // If the platform/user can't actually revoke read (e.g. running as
        // root), the scenario can't be produced — skip rather than fake-pass.
        assumeTrue(sentinel.setReadable(false, false) && !sentinel.canRead())

        val applied = mutableListOf<String>()
        val result = FirstRegionAutoActivator.maybeAutoActivate(
            projectDir = projectDir,
            mapsSubpath = mapsSubpath,
            regionsCacheRoot = cacheRoot,
            downloadedRegionId = "zinder",
            applyRegionToProject = { info, _ -> applied += info.regionId; true },
            writeActiveRegion = { dir, id ->
                val root = File(dir, mapsSubpath).apply { mkdirs() }
                val f = File(root, "active.txt")
                f.setReadable(true)
                f.writeText(id)
            },
        )

        assertTrue("Unreadable sentinel must not block activation; got $result",
            result is FirstRegionAutoActivator.Result.Activated)
        assertEquals(listOf("zinder"), applied)
    }
}
