package org.appdevforall.maps.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Edge-arm top-up for [RegionCache] beyond `RegionCacheTest` /
 * `RegionCacheBranchCoverageTest`:
 *
 *  - `listFromRoot` where `root.listFiles` returns NULL (root missing, or a
 *    plain file) — distinct from the empty-array case already covered;
 *  - `meta.json` present but ZERO bytes — the `metaFile.length() > 0` false arm
 *    with `exists()` true: parses as an empty JSON object, all defaults from
 *    the directory;
 *  - the suspend entry points invoked from an ALREADY-IO-dispatched coroutine
 *    (production calls arrive both ways: cold from the main scope, and nested
 *    inside other `Dispatchers.IO` work like FirstRegionAutoActivator).
 */
class RegionCacheEdgeCoverageTest {

    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("maps-region-cache-edge").toFile()
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun listFromRootIsEmptyWhenRootDoesNotExist() = runBlocking {
        val missing = File(tempRoot, "never-created")
        assertFalse("precondition", missing.exists())
        assertTrue(RegionCache.listFromRoot(missing).isEmpty())
    }

    @Test
    fun listFromRootIsEmptyWhenRootIsAPlainFile() = runBlocking {
        // listFiles on a non-directory returns null — must degrade to an empty
        // list, never throw (the Region Manager tab still has to render).
        val fileRoot = File(tempRoot, "not-a-dir.txt").apply { writeText("x") }
        assertTrue(RegionCache.listFromRoot(fileRoot).isEmpty())
    }

    @Test
    fun zeroByteMetaJsonFallsBackToDirectoryDefaults() = runBlocking {
        // exists() true but length() == 0 → parsed as an empty meta object:
        // regionId/displayName fall back to the dir name and the size column
        // reflects the on-disk artifact sum.
        val dir = File(tempRoot, "empty-meta").apply { mkdirs() }
        File(dir, RegionCache.FILE_META_JSON).writeText("")
        File(dir, RegionCache.FILE_TILES_PMTILES).writeBytes(ByteArray(64))

        val listed = RegionCache.listFromRoot(tempRoot)
        assertEquals(1, listed.size)
        assertEquals("empty-meta", listed[0].regionId)
        assertEquals("empty-meta", listed[0].displayName)
        assertEquals(64L, listed[0].sizeBytes)
    }

    @Test
    fun suspendEntryPointsWorkWhenAlreadyOnIoDispatcher() = runBlocking {
        // Nested-in-IO invocation (withContext(IO) inside IO returns
        // undispatched) must behave identically to the cold-dispatch path.
        val dir = File(tempRoot, "nestedregion").apply { mkdirs() }
        File(dir, RegionCache.FILE_META_JSON).writeText(
            JSONObject().apply {
                put("regionId", "nestedregion")
                put("displayName", "Nested Region")
            }.toString()
        )

        withContext(Dispatchers.IO) {
            val listed = RegionCache.listFromRoot(tempRoot)
            assertEquals(listOf("nestedregion"), listed.map { it.regionId })

            val info = RegionCache.readDir(dir)
            assertNotNull(info)
            assertEquals("Nested Region", info!!.displayName)

            assertTrue(RegionCache.deleteFromRoot(tempRoot, "nestedregion"))
            assertFalse(dir.exists())
        }
    }
}
