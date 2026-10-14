package org.appdevforall.maps.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Branch-coverage top-up for [FirstRegionAutoActivator] — the
 * `readActiveSentinel` branches the policy suite in
 * `FirstRegionAutoActivatorTest` leaves uncovered:
 *
 *  - the sentinel path exists but is a DIRECTORY (`isFile` false) → treated as
 *    no active region, so the download auto-activates.
 *  - no `active.txt` at all (the `!file.exists()` true branch) with a region
 *    present → activates.
 */
class FirstRegionAutoActivatorBranchCoverageTest {

    private lateinit var projectDir: File
    private lateinit var cacheRoot: File
    private val mapsSubpath = "app/src/main/assets/maps"

    @Before
    fun setUp() {
        projectDir = Files.createTempDirectory("maps-fra-branch-project").toFile()
        cacheRoot = Files.createTempDirectory("maps-fra-branch-cache").toFile()
    }

    @After
    fun tearDown() {
        projectDir.deleteRecursively()
        cacheRoot.deleteRecursively()
    }

    private fun seedCachedRegion(regionId: String, displayName: String = regionId) {
        val dir = File(cacheRoot, regionId).apply { mkdirs() }
        File(dir, RegionCache.FILE_META_JSON).writeText(
            JSONObject().apply {
                put("regionId", regionId)
                put("displayName", displayName)
                put("bbox", JSONArray(listOf(0.0, 0.0, 1.0, 1.0)))
                put("source", JSONObject().apply { put("kind", "internet") })
            }.toString()
        )
        File(dir, RegionCache.FILE_TILES_PMTILES).writeBytes(ByteArray(8))
    }

    private val applyOk: suspend (RegionInfo, File) -> Boolean = { _, _ -> true }

    private val writeCalls = mutableListOf<Pair<File, String>>()
    private val writeActive: (File, String) -> Unit = { dir, id ->
        writeCalls += dir to id
        File(dir, mapsSubpath).apply { mkdirs() }
        val sentinel = File(File(dir, mapsSubpath), "active.txt")
        // The dir-as-sentinel test seeds active.txt as a directory; clear it
        // before writing the real marker (mirrors the activate-proceeds path).
        if (sentinel.isDirectory) sentinel.deleteRecursively()
        sentinel.writeText(id)
    }

    @Test
    fun activatesWhenSentinelPathIsADirectory() {
        runBlocking {
            // active.txt exists but is a directory → readActiveSentinel's
            // `!file.isFile` guard returns null → activator proceeds.
            seedCachedRegion("freetown", "Freetown")
            File(projectDir, "$mapsSubpath/active.txt").mkdirs()

            val result = FirstRegionAutoActivator.maybeAutoActivate(
                projectDir = projectDir,
                mapsSubpath = mapsSubpath,
                regionsCacheRoot = cacheRoot,
                downloadedRegionId = "freetown",
                applyRegionToProject = applyOk,
                writeActiveRegion = writeActive,
            )

            assertTrue(
                "Directory sentinel should be ignored; got $result",
                result is FirstRegionAutoActivator.Result.Activated,
            )
            assertEquals(1, writeCalls.size)
            assertEquals("freetown", writeCalls.first().second)
        }
    }

    @Test
    fun applyFailedCarriesRegionIdAndReason() {
        runBlocking {
            // Drive the ApplyFailed arm and read its generated accessors so the
            // data class's component/equals/toString branches are covered, and
            // assert the contract: the failing region's id + a reason string,
            // and writeActive is NOT called (no half-applied active.txt).
            seedCachedRegion("monrovia", "Monrovia")
            writeCalls.clear()

            val applyFails: suspend (RegionInfo, File) -> Boolean = { _, _ -> false }
            val result = FirstRegionAutoActivator.maybeAutoActivate(
                projectDir = projectDir,
                mapsSubpath = mapsSubpath,
                regionsCacheRoot = cacheRoot,
                downloadedRegionId = "monrovia",
                applyRegionToProject = applyFails,
                writeActiveRegion = writeActive,
            )

            assertTrue(result is FirstRegionAutoActivator.Result.ApplyFailed)
            result as FirstRegionAutoActivator.Result.ApplyFailed
            assertEquals("monrovia", result.regionId)
            assertTrue("reason should be non-blank", result.reason.isNotBlank())
            assertTrue("apply failure must not write active.txt", writeCalls.isEmpty())
            // toString is part of the generated surface — exercise it.
            assertTrue(result.toString().contains("monrovia"))
        }
    }

    @Test
    fun activatedResultExposesRegionIdAndDisplayName() {
        runBlocking {
            // Read both generated accessors of the Activated data class.
            seedCachedRegion("bamako", "Bamako, Mali")
            writeCalls.clear()

            val result = FirstRegionAutoActivator.maybeAutoActivate(
                projectDir = projectDir,
                mapsSubpath = mapsSubpath,
                regionsCacheRoot = cacheRoot,
                downloadedRegionId = "bamako",
                applyRegionToProject = applyOk,
                writeActiveRegion = writeActive,
            )

            result as FirstRegionAutoActivator.Result.Activated
            assertEquals("bamako", result.regionId)
            assertEquals("Bamako, Mali", result.displayName)
        }
    }

    @Test
    fun noOpAlreadyActiveWhenSentinelNamesAStillPresentRegion() {
        runBlocking {
            // existingActive non-blank AND the named region still exists in the
            // cache → the `activeStillExists` true branch returns NoOpAlreadyActive
            // without touching apply/write. (The policy suite covers this too, but
            // it's the decision arm paired with the stale-sentinel fall-through, so
            // assert it explicitly here against the directory-sentinel doubles.)
            seedCachedRegion("dakar", "Dakar")
            seedCachedRegion("conakry", "Conakry")
            File(projectDir, "$mapsSubpath").mkdirs()
            File(projectDir, "$mapsSubpath/active.txt").writeText("dakar")
            writeCalls.clear()

            val result = FirstRegionAutoActivator.maybeAutoActivate(
                projectDir = projectDir,
                mapsSubpath = mapsSubpath,
                regionsCacheRoot = cacheRoot,
                downloadedRegionId = "conakry",
                applyRegionToProject = applyOk,
                writeActiveRegion = writeActive,
            )

            assertTrue(
                "active region present → NoOp; got $result",
                result is FirstRegionAutoActivator.Result.NoOpAlreadyActive,
            )
            assertTrue("must not write when already active", writeCalls.isEmpty())
        }
    }

    @Test
    fun activatesWhenNoSentinelFileExistsAtAll() {
        runBlocking {
            // No active.txt on disk — the `!file.exists()` true branch in
            // readActiveSentinel. A seeded region auto-activates.
            seedCachedRegion("juba", "Juba")

            val result = FirstRegionAutoActivator.maybeAutoActivate(
                projectDir = projectDir,
                mapsSubpath = mapsSubpath,
                regionsCacheRoot = cacheRoot,
                downloadedRegionId = "juba",
                applyRegionToProject = applyOk,
                writeActiveRegion = writeActive,
            )

            assertTrue(
                "Missing sentinel should activate; got $result",
                result is FirstRegionAutoActivator.Result.Activated,
            )
            assertEquals("juba", (result as FirstRegionAutoActivator.Result.Activated).regionId)
        }
    }
}
