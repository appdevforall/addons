package org.appdevforall.maps.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
  fun `no open project is reported and nothing is copied`() = runBlocking {
    seed("alpha")
    project.projectDir = null
    assertEquals(DownloadCompletion.Outcome.NoProject, completion.complete("alpha"))
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
      ),
      outcome,
    )
    assertEquals("alpha", active())
  }

  @Test
  fun `a project with a live active region keeps it`() = runBlocking {
    seed("alpha")
    seed("beta")
    project.writeActiveRegionId(projectDir, "beta")
    val outcome = completion.complete("alpha") as DownloadCompletion.Outcome.Finished
    assertEquals(FirstRegionAutoActivator.Result.NoOpAlreadyActive, outcome.result)
    assertEquals("beta", active())
  }

  @Test
  fun `snackbar apply copies the region in and activates it`() = runBlocking {
    seed("alpha")
    project.writeActiveRegionId(projectDir, "beta")
    assertTrue(completion.applyFromSnackbar(projectDir, "alpha"))
    assertEquals(listOf("alpha"), project.applied)
    assertEquals("alpha", active())
  }

  @Test
  fun `snackbar apply of a region gone from the cache does nothing`() = runBlocking {
    seed("beta")
    assertFalse(completion.applyFromSnackbar(projectDir, "alpha"))
    assertTrue(project.applied.isEmpty())
  }

  @Test
  fun `snackbar apply with an unreadable cache does nothing`() = runBlocking {
    seed("alpha")
    listFails = true
    assertFalse(completion.applyFromSnackbar(projectDir, "alpha"))
    assertTrue(project.applied.isEmpty())
  }

  @Test
  fun `a failed snackbar copy reports false and leaves active_txt alone`() = runBlocking {
    seed("alpha")
    project.writeActiveRegionId(projectDir, "beta")
    project.applyResult = false
    assertFalse(completion.applyFromSnackbar(projectDir, "alpha"))
    assertEquals("beta", active())
  }
}
