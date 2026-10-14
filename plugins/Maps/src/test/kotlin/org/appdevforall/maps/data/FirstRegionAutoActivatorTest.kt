package org.appdevforall.maps.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit tests for [FirstRegionAutoActivator]. Exercises the policy + applies it
 * end-to-end against temp project / cache directories.
 *
 * The actual file-copy (`applyRegionToProject`) is injected as a fake that
 * just records what it was called with — same shape RegionManagerFragment
 * passes in production, but no real I/O. This keeps the test fast + isolated.
 */
class FirstRegionAutoActivatorTest {

    private lateinit var projectDir: File
    private lateinit var cacheRoot: File
    private val mapsSubpath = "app/src/main/assets/maps"

    @Before
    fun setUp() {
        projectDir = Files.createTempDirectory("maps-fra-project").toFile()
        cacheRoot = Files.createTempDirectory("maps-fra-cache").toFile()
    }

    @After
    fun tearDown() {
        projectDir.deleteRecursively()
        cacheRoot.deleteRecursively()
    }

    /** Drop a minimal valid region dir into [cacheRoot] so `RegionCache.listFromRoot` finds it. */
    private fun seedCachedRegion(regionId: String, displayName: String = regionId) {
        val dir = File(cacheRoot, regionId).apply { mkdirs() }
        File(dir, RegionCache.FILE_META_JSON).writeText(
            JSONObject().apply {
                put("regionId", regionId)
                put("displayName", displayName)
                put("bbox", JSONArray(listOf(0.0, 0.0, 1.0, 1.0)))
                put("zoomMin", 6)
                put("zoomMax", 14)
                put("source", JSONObject().apply { put("kind", "internet") })
                put("sizeBytes", 1024)
                put("downloadedAt", System.currentTimeMillis())
                put("lastUsedAt", System.currentTimeMillis())
            }.toString()
        )
        // The size sum reads from on-disk artifacts; touch a stub tiles file so
        // the listed RegionInfo isn't a phantom.
        File(dir, RegionCache.FILE_TILES_PMTILES).writeBytes(ByteArray(1024))
    }

    private fun writeActiveSentinel(text: String) {
        val mapsRoot = File(projectDir, mapsSubpath).apply { mkdirs() }
        File(mapsRoot, "active.txt").writeText(text)
    }

    /** Apply fake that records its arguments + returns true. */
    private val applyOk: (RegionInfo, File) -> Boolean = { _, _ -> true }

    /** Apply fake that simulates a failure (disk full / perms). */
    private val applyFail: (RegionInfo, File) -> Boolean = { _, _ -> false }

    private val writeActiveCalls = mutableListOf<Pair<File, String>>()
    private val writeActive: (File, String) -> Unit = { dir, id ->
        writeActiveCalls += dir to id
        // Mirror RegionManagerFragment's writeActiveRegionId behavior — write a
        // real file so subsequent assertions can verify it.
        val mapsRoot = File(dir, mapsSubpath).apply { mkdirs() }
        File(mapsRoot, "active.txt").writeText(id)
    }

    // ----- Empty project (the user's first download) -----

    @Test
    fun activates_when_no_active_sentinel_yet() = runBlocking {
        seedCachedRegion("lalibela", "Lalibela, Ethiopia")
        val applyCalls = mutableListOf<RegionInfo>()
        val result = FirstRegionAutoActivator.maybeAutoActivate(
            projectDir = projectDir,
            mapsSubpath = mapsSubpath,
            regionsCacheRoot = cacheRoot,
            downloadedRegionId = "lalibela",
            applyRegionToProject = { info, _ -> applyCalls += info; true },
            writeActiveRegion = writeActive,
        )
        assertTrue(
            "Expected Activated, got $result",
            result is FirstRegionAutoActivator.Result.Activated
        )
        assertEquals("lalibela", (result as FirstRegionAutoActivator.Result.Activated).regionId)
        assertEquals("Lalibela, Ethiopia", result.displayName)
        // File-copy was triggered exactly once.
        assertEquals(1, applyCalls.size)
        assertEquals("lalibela", applyCalls[0].regionId)
        // active.txt was written via the injected writer.
        val activeFile = File(projectDir, "$mapsSubpath/active.txt")
        assertTrue("active.txt should exist after auto-activation", activeFile.exists())
        assertEquals("lalibela", activeFile.readText().trim())
    }

    // ----- Project already has an active region — must not overwrite -----

    @Test
    fun noOp_when_active_sentinel_already_names_a_region() = runBlocking {
        // lalibela MUST also be in the cache — otherwise the sentinel is
        // stale (the region was deleted out from under us) and the activator
        // falls through to apply the new region. Tested separately below.
        seedCachedRegion("lalibela", "Lalibela")
        seedCachedRegion("kathmandu", "Kathmandu")
        writeActiveSentinel("lalibela")  // user's prior choice
        val applyCalls = mutableListOf<RegionInfo>()
        val result = FirstRegionAutoActivator.maybeAutoActivate(
            projectDir = projectDir,
            mapsSubpath = mapsSubpath,
            regionsCacheRoot = cacheRoot,
            downloadedRegionId = "kathmandu",
            applyRegionToProject = { info, _ -> applyCalls += info; true },
            writeActiveRegion = writeActive,
        )
        assertTrue(
            "Expected NoOpAlreadyActive, got $result",
            result is FirstRegionAutoActivator.Result.NoOpAlreadyActive
        )
        assertTrue("apply must not be invoked when an active region exists", applyCalls.isEmpty())
        assertTrue("writeActive must not be invoked", writeActiveCalls.isEmpty())
        // Existing active.txt unchanged.
        assertEquals("lalibela", File(projectDir, "$mapsSubpath/active.txt").readText().trim())
    }

    /**
     * Bryan, 2026-05-26 — observed bug: deleted all regions, downloaded
     * Spain, got "active region is unchanged" because the previously-active
     * region's name was still in active.txt even though its cache files
     * were gone. Fix: treat such a sentinel as stale and proceed.
     */
    @Test
    fun activates_when_active_sentinel_names_a_deleted_region() = runBlocking {
        // spain is in the cache; lalibela was deleted (only its stale
        // sentinel survives).
        seedCachedRegion("spain", "Spain")
        writeActiveSentinel("lalibela")  // stale — region cache doesn't have it
        val result = FirstRegionAutoActivator.maybeAutoActivate(
            projectDir = projectDir,
            mapsSubpath = mapsSubpath,
            regionsCacheRoot = cacheRoot,
            downloadedRegionId = "spain",
            applyRegionToProject = applyOk,
            writeActiveRegion = writeActive,
        )
        assertTrue(
            "Stale sentinel should fall through to apply; got $result",
            result is FirstRegionAutoActivator.Result.Activated,
        )
        assertEquals(1, writeActiveCalls.size)
        assertEquals("spain", writeActiveCalls.first().second)
        // active.txt now updated to spain.
        assertEquals("spain", File(projectDir, "$mapsSubpath/active.txt").readText().trim())
    }

    // ----- Blank active sentinel = treat as no active region -----

    @Test
    fun activates_when_active_sentinel_is_blank() = runBlocking {
        seedCachedRegion("kandahar")
        writeActiveSentinel("   \n")  // whitespace-only — should not block
        val result = FirstRegionAutoActivator.maybeAutoActivate(
            projectDir = projectDir,
            mapsSubpath = mapsSubpath,
            regionsCacheRoot = cacheRoot,
            downloadedRegionId = "kandahar",
            applyRegionToProject = applyOk,
            writeActiveRegion = writeActive,
        )
        assertTrue(result is FirstRegionAutoActivator.Result.Activated)
    }

    // ----- Race: regionId not in cache (e.g., deleted mid-flight) -----

    @Test
    fun noOp_when_region_missing_from_cache() = runBlocking {
        // Don't seed any region.
        val result = FirstRegionAutoActivator.maybeAutoActivate(
            projectDir = projectDir,
            mapsSubpath = mapsSubpath,
            regionsCacheRoot = cacheRoot,
            downloadedRegionId = "phantom",
            applyRegionToProject = applyOk,
            writeActiveRegion = writeActive,
        )
        assertTrue(
            "Expected NoOpRegionNotFound, got $result",
            result is FirstRegionAutoActivator.Result.NoOpRegionNotFound
        )
        assertTrue(writeActiveCalls.isEmpty())
    }

    // ----- Apply failure (disk full, perms): MUST NOT write active.txt -----

    @Test
    fun does_not_write_active_when_apply_fails() = runBlocking {
        seedCachedRegion("goma")
        val result = FirstRegionAutoActivator.maybeAutoActivate(
            projectDir = projectDir,
            mapsSubpath = mapsSubpath,
            regionsCacheRoot = cacheRoot,
            downloadedRegionId = "goma",
            applyRegionToProject = applyFail,
            writeActiveRegion = writeActive,
        )
        assertTrue(
            "Expected ApplyFailed, got $result",
            result is FirstRegionAutoActivator.Result.ApplyFailed
        )
        assertTrue("writeActive must not be invoked when apply fails", writeActiveCalls.isEmpty())
        // active.txt must NOT exist (or exists empty); otherwise the project
        // would name a region whose files weren't copied → build fails with
        // a misleading "missing tiles" error.
        val activeFile = File(projectDir, "$mapsSubpath/active.txt")
        assertFalse(
            "active.txt must NOT exist after apply failure",
            activeFile.exists() && activeFile.readText().trim().isNotEmpty()
        )
    }

    // ----- Sentinel-format robustness -----

    @Test
    fun ignores_oversized_sentinel_treats_as_no_active() = runBlocking {
        // A 1 KB file in active.txt is suspicious — could be a corrupted dump.
        // The bounded-read in maybeAutoActivate caps at 256 bytes, so anything
        // larger reads as "no active" and lets the auto-activate proceed.
        seedCachedRegion("maiduguri")
        val mapsRoot = File(projectDir, mapsSubpath).apply { mkdirs() }
        File(mapsRoot, "active.txt").writeBytes(ByteArray(1024) { 'x'.code.toByte() })
        val result = FirstRegionAutoActivator.maybeAutoActivate(
            projectDir = projectDir,
            mapsSubpath = mapsSubpath,
            regionsCacheRoot = cacheRoot,
            downloadedRegionId = "maiduguri",
            applyRegionToProject = applyOk,
            writeActiveRegion = writeActive,
        )
        assertTrue(
            "Oversized sentinel should be ignored; expected Activated, got $result",
            result is FirstRegionAutoActivator.Result.Activated
        )
    }

    // ----- Nested mapsSubpath is created when missing -----

    @Test
    fun creates_maps_subpath_if_absent_during_write() = runBlocking {
        seedCachedRegion("port-au-prince", "Port-au-Prince")
        val mapsRoot = File(projectDir, mapsSubpath)
        assertFalse("precondition: maps subpath should not exist yet", mapsRoot.exists())
        val result = FirstRegionAutoActivator.maybeAutoActivate(
            projectDir = projectDir,
            mapsSubpath = mapsSubpath,
            regionsCacheRoot = cacheRoot,
            downloadedRegionId = "port-au-prince",
            applyRegionToProject = applyOk,
            writeActiveRegion = writeActive,
        )
        assertTrue(result is FirstRegionAutoActivator.Result.Activated)
        assertTrue("maps subpath must exist after activation", mapsRoot.exists())
        assertEquals(
            "port-au-prince",
            File(mapsRoot, "active.txt").readText().trim(),
        )
    }
}
