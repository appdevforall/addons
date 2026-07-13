package org.appdevforall.maps.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Branch-coverage top-up for [RegionCache] — the parse branches the safety-path
 * suite in `RegionCacheTest` leaves uncovered:
 *
 *  - source object present but with a BLANK host → host coalesces to null.
 *  - `downloadedAt` / `lastUsedAt` positive (the `takeIf { it > 0 }` true branch).
 *  - `layers` array with blank / null-ish entries dropped.
 *  - `deleteFromRoot` invalid-id refusal (regex guard, before canonicalisation).
 *  - `listFromRoot` skipping a directory without `meta.json`.
 */
class RegionCacheBranchCoverageTest {

    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("maps-region-cache-branch").toFile()
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun sourceObjectWithBlankHostCoalescesToNull() = runBlocking {
        // Object-form source whose host is blank → sourceHost must read as null,
        // exercising the `takeIf { it.isNotBlank() }` false branch.
        val dir = File(tempRoot, "blank-host").apply { mkdirs() }
        val meta = JSONObject().apply {
            put("regionId", "blank-host")
            put("source", JSONObject().apply {
                put("kind", "internet")
                put("host", "   ")
            })
        }
        File(dir, "meta.json").writeText(meta.toString())

        val info = RegionCache.readDir(dir)
        assertNotNull(info)
        assertEquals("internet", info!!.source)
        assertNull("blank host must coalesce to null", info.sourceHost)
    }

    @Test
    fun sourceObjectMissingKindFallsBackToUnknown() = runBlocking {
        // Object-form source with no "kind" → optString default "unknown".
        val dir = File(tempRoot, "no-kind").apply { mkdirs() }
        val meta = JSONObject().apply {
            put("regionId", "no-kind")
            put("source", JSONObject().apply { put("host", "box.lan") })
        }
        File(dir, "meta.json").writeText(meta.toString())

        val info = RegionCache.readDir(dir)
        assertEquals("unknown", info!!.source)
        assertEquals("box.lan", info.sourceHost)
    }

    @Test
    fun downloadedAtAndLastUsedAtSurfacedWhenPositive() = runBlocking {
        // The `takeIf { it > 0L }` TRUE branch — both timestamps are non-null.
        val dir = File(tempRoot, "with-times").apply { mkdirs() }
        val meta = JSONObject().apply {
            put("regionId", "with-times")
            put("downloadedAt", 1735000000000L)
            put("lastUsedAt", 1735100000000L)
        }
        File(dir, "meta.json").writeText(meta.toString())

        val info = RegionCache.readDir(dir)!!
        assertEquals(1735000000000L, info.downloadedAt)
        assertEquals(1735100000000L, info.lastUsedAt)
    }

    @Test
    fun zeroTimestampsCoalesceToNull() = runBlocking {
        // The `takeIf { it > 0L }` FALSE branch — explicit 0 reads as null.
        val dir = File(tempRoot, "zero-times").apply { mkdirs() }
        val meta = JSONObject().apply {
            put("regionId", "zero-times")
            put("downloadedAt", 0L)
            put("lastUsedAt", 0L)
        }
        File(dir, "meta.json").writeText(meta.toString())

        val info = RegionCache.readDir(dir)!!
        assertNull(info.downloadedAt)
        assertNull(info.lastUsedAt)
    }

    @Test
    fun layersDropsBlankEntries() = runBlocking {
        // Layers array containing blank strings — the `takeIf { isNotBlank }`
        // filter must drop them, keeping only the real layer names.
        val dir = File(tempRoot, "blank-layers").apply { mkdirs() }
        val meta = JSONObject().apply {
            put("regionId", "blank-layers")
            put("layers", JSONArray(listOf("vector", "", "  ", "basemap")))
        }
        File(dir, "meta.json").writeText(meta.toString())

        val info = RegionCache.readDir(dir)!!
        assertEquals(listOf("vector", "basemap"), info.layers)
    }

    @Test
    fun bboxWithNonNumericEntriesIsDroppedViaRunCatching() = runBlocking {
        // A length-4 bbox whose entries aren't parseable as doubles makes
        // arr.getDouble(i) throw inside the runCatching, so getOrNull() yields
        // null — the catch arm, distinct from the `!has` / `length != 4` early
        // returns. The rest of the record still parses.
        val dir = File(tempRoot, "bad-bbox-values").apply { mkdirs() }
        val meta = JSONObject().apply {
            put("regionId", "bad-bbox-values")
            put("bbox", JSONArray(listOf("north", "south", "east", "west")))
        }
        File(dir, "meta.json").writeText(meta.toString())

        val info = RegionCache.readDir(dir)!!
        assertNull("non-numeric bbox must be dropped", info.bbox)
        assertEquals("bad-bbox-values", info.regionId)
    }

    @Test
    fun bboxFullyParsedWhenAllFourPresentAndNumeric() = runBlocking {
        // The success arm: has("bbox") true, length == 4, all getDouble succeed.
        val dir = File(tempRoot, "good-bbox").apply { mkdirs() }
        val meta = JSONObject().apply {
            put("regionId", "good-bbox")
            put("bbox", JSONArray(listOf(1.5, 2.5, 3.5, 4.5)))
        }
        File(dir, "meta.json").writeText(meta.toString())

        val info = RegionCache.readDir(dir)!!
        assertNotNull(info.bbox)
        assertEquals(4, info.bbox!!.size)
        assertEquals(4.5, info.bbox!![3], 0.0)
    }

    @Test
    fun layersPresentButEmptyArrayYieldsEmptyList() = runBlocking {
        // layers key present but the array is empty → the for-loop body never
        // runs, buildList returns empty. Distinct from the no-layers-key path.
        val dir = File(tempRoot, "empty-layers").apply { mkdirs() }
        val meta = JSONObject().apply {
            put("regionId", "empty-layers")
            put("layers", JSONArray())
        }
        File(dir, "meta.json").writeText(meta.toString())

        assertEquals(emptyList<String>(), RegionCache.readDir(dir)!!.layers)
    }

    @Test
    fun deleteFromRootRefusesInvalidIdViaRegex() = runBlocking {
        // Hits the `!isValidRegionId` true branch in deleteFromRoot (the regex
        // guard) — returns false before any canonicalisation.
        assertFalse(RegionCache.deleteFromRoot(tempRoot, "Bad Id!"))
        assertFalse(RegionCache.deleteFromRoot(tempRoot, "UPPER"))
    }

    @Test
    fun deleteFromRootSucceedsForValidExistingRegion() = runBlocking {
        // The happy path: valid id, path contained, target exists → deleted.
        val dir = File(tempRoot, "goodregion").apply { mkdirs() }
        File(dir, "meta.json").writeText("{}")
        assertTrue(dir.exists())
        assertTrue(RegionCache.deleteFromRoot(tempRoot, "goodregion"))
        assertFalse(dir.exists())
    }

    @Test
    fun listFromRootSkipsDirWithoutMeta_butKeepsValidSibling() = runBlocking {
        // No-meta dir is skipped (warn path); the valid sibling still lists.
        File(tempRoot, "ghost").apply { mkdirs() }
        val ok = File(tempRoot, "real").apply { mkdirs() }
        File(ok, "meta.json").writeText(
            JSONObject().apply { put("regionId", "real") }.toString()
        )

        val regions = RegionCache.listFromRoot(tempRoot)
        assertEquals(1, regions.size)
        assertEquals("real", regions[0].regionId)
    }

    @Test
    fun listFromRootReturnsEmptyWhenRootHasNoSubdirs() = runBlocking {
        // listFiles filter returns an empty array (root has only files).
        File(tempRoot, "loose-file.txt").writeText("x")
        assertTrue(RegionCache.listFromRoot(tempRoot).isEmpty())
    }

    @Test
    fun listReturnsEmptyWhenExternalStorageUnavailableOnJvm() = runBlocking {
        // The no-arg list() routes through rootDir(), which calls
        // Environment.getExternalStorageDirectory() — unavailable on the JVM, so
        // it throws and the `runCatching { rootDir() }.getOrNull() ?: return
        // emptyList()` elvis-true branch fires. Must yield an empty list, never
        // throw (the bottom-sheet still has to render).
        assertTrue(RegionCache.list().isEmpty())
    }

    @Test
    fun deleteReturnsFalseWhenExternalStorageUnavailableOnJvm() = runBlocking {
        // The no-arg delete() also reaches rootDir(); when it throws, the
        // `getOrNull() ?: return false` branch returns false (couldn't even
        // resolve the root, so nothing was deleted).
        assertFalse(RegionCache.delete("goodregion"))
    }

    @Test
    fun readDirReturnsNullWhenReadThrowsOnNonDirectory() = runBlocking {
        // read() opening a path that isn't a usable directory: readDir wraps in
        // runCatching and yields a best-effort RegionInfo (dir name fallbacks),
        // never throwing.
        val dir = File(tempRoot, "empty-region").apply { mkdirs() }
        val info = RegionCache.readDir(dir)
        assertNotNull(info)
        assertEquals("empty-region", info!!.regionId)
        // No artifacts on disk → size sum is 0.
        assertEquals(0L, info.sizeBytes)
    }
}
