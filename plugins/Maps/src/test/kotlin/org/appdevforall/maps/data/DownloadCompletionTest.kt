package org.appdevforall.maps.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [DownloadCompletion] against a temp project and a temp region cache. */
class DownloadCompletionTest {

    private lateinit var projectDir: File
    private lateinit var cacheRoot: File
    private lateinit var project: FakeProjectRegions
    private var listFails = false

    private val completion by lazy {
        DownloadCompletion(
            project = project,
            listRegions = { if (listFails) error("cache unreadable") else RegionCache.listFromRoot(cacheRoot) },
            cacheRoot = { cacheRoot },
        )
    }

    @Before
    fun setUp() {
        projectDir = Files.createTempDirectory("maps-dc-project").toFile()
        cacheRoot = Files.createTempDirectory("maps-dc-cache").toFile()
        project = FakeProjectRegions(projectDir)
    }

    @After
    fun tearDown() {
        projectDir.deleteRecursively()
        cacheRoot.deleteRecursively()
    }

    private fun seed(regionId: String, displayName: String = regionId) {
        val dir = File(cacheRoot, regionId).apply { mkdirs() }
        File(dir, RegionCache.FILE_META_JSON)
            .writeText(
                JSONObject()
                    .apply {
                        put("regionId", regionId)
                        put("displayName", displayName)
                        put("bbox", JSONArray(listOf(0.0, 0.0, 1.0, 1.0)))
                        put("source", JSONObject().apply { put("kind", "internet") })
                    }
                    .toString()
            )
        File(dir, RegionCache.FILE_TILES_PMTILES).writeBytes(ByteArray(16))
    }

    private fun active(): String? = runBlocking { project.readActiveRegionId(projectDir) }

    @Test
    fun `no open project is reported by region name and nothing is copied`() = runBlocking {
        seed("alpha", "Alpha Town")
        project.projectDir = null
        assertEquals(DownloadCompletion.Outcome.NoProject("Alpha Town"), completion.complete("alpha"))
        assertTrue(project.applied.isEmpty())
    }

    @Test
    fun `the first region in a project is activated in that project`() = runBlocking {
        seed("alpha", "Alpha Town")
        val outcome = completion.complete("alpha")
        assertEquals(
            DownloadCompletion.Outcome.Finished(
                projectDir,
                FirstRegionAutoActivator.Result.Activated("alpha", "Alpha Town"),
                "Alpha Town",
            ),
            outcome,
        )
        assertEquals("alpha", active())
    }

    @Test
    fun `a project with a live active region keeps it`() = runBlocking {
        seed("alpha", "Alpha Town")
        seed("beta")
        project.writeActiveRegionId(projectDir, "beta")
        val outcome = completion.complete("alpha") as DownloadCompletion.Outcome.Finished
        assertEquals(FirstRegionAutoActivator.Result.NoOpAlreadyActive, outcome.result)
        assertEquals("Alpha Town", outcome.displayName)
        assertEquals("beta", active())
    }

    @Test
    fun `a region missing from an unreadable cache is named by its id`() = runBlocking {
        listFails = true
        project.projectDir = null
        assertEquals(DownloadCompletion.Outcome.NoProject("alpha"), completion.complete("alpha"))
    }

    @Test
    fun `snackbar apply copies the region in, activates it and names it`() = runBlocking {
        seed("alpha", "Alpha Town")
        project.writeActiveRegionId(projectDir, "beta")
        assertEquals(
            DownloadCompletion.ApplyResult.Switched("Alpha Town"),
            completion.applyFromSnackbar(projectDir, "alpha"),
        )
        assertEquals(listOf("alpha"), project.applied)
        assertEquals("alpha", active())
    }

    @Test
    fun `snackbar apply of the already-active region says so and refreshes its copy`() = runBlocking {
        seed("alpha", "Alpha Town")
        project.writeActiveRegionId(projectDir, "alpha")
        assertEquals(
            DownloadCompletion.ApplyResult.AlreadyActive("Alpha Town"),
            completion.applyFromSnackbar(projectDir, "alpha"),
        )
        assertEquals(listOf("alpha"), project.applied)
    }

    @Test
    fun `snackbar apply of a region gone from the cache does nothing`() = runBlocking {
        seed("beta")
        assertEquals(DownloadCompletion.ApplyResult.Failed, completion.applyFromSnackbar(projectDir, "alpha"))
        assertTrue(project.applied.isEmpty())
    }

    @Test
    fun `snackbar apply with an unreadable cache does nothing`() = runBlocking {
        seed("alpha")
        listFails = true
        assertEquals(DownloadCompletion.ApplyResult.Failed, completion.applyFromSnackbar(projectDir, "alpha"))
        assertTrue(project.applied.isEmpty())
    }

    @Test
    fun `a failed snackbar copy reports failure and leaves active_txt alone`() = runBlocking {
        seed("alpha")
        project.writeActiveRegionId(projectDir, "beta")
        project.applyResult = false
        assertEquals(DownloadCompletion.ApplyResult.Failed, completion.applyFromSnackbar(projectDir, "alpha"))
        assertEquals("beta", active())
    }
}
