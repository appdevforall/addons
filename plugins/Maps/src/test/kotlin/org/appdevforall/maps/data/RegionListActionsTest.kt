package org.appdevforall.maps.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.appdevforall.maps.data.RegionListActions.ToggleResult
import org.appdevforall.maps.domain.RegionWizardStateMachine.Event
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [RegionListActions] against a real `active.txt` in a temp project and an in-memory cache. */
class RegionListActionsTest {

  private lateinit var projectDir: File
  private lateinit var project: FakeProjectRegions
  private val alpha = regionInfo("alpha", "Alpha Town")
  private val beta = regionInfo("beta", "Beta City")
  private var cached = listOf(alpha, beta)
  private var listFails = false
  private var deleteResult = true
  private val deleted = mutableListOf<String>()

  private val actions by lazy {
    RegionListActions(
      project = project,
      listRegions = { if (listFails) error("cache unreadable") else cached },
      deleteRegion = {
        deleted += it.regionId
        deleteResult
      },
    )
  }

  @Before
  fun setUp() {
    projectDir = Files.createTempDirectory("maps-rla").toFile()
    project = FakeProjectRegions(projectDir)
  }

  @After
  fun tearDown() {
    projectDir.deleteRecursively()
  }

  private fun active(): String? = runBlocking { project.readActiveRegionId(projectDir) }

  private fun setActive(id: String) = runBlocking { project.writeActiveRegionId(projectDir, id) }

  @Test
  fun `toggle with no open project reports it and touches nothing`() = runBlocking {
    project.projectDir = null
    assertEquals(ToggleResult.NoProject, actions.toggleActive(alpha, true))
    assertTrue(project.applied.isEmpty())
  }

  @Test
  fun `toggle on with no prior active region activates it`() = runBlocking {
    assertEquals(ToggleResult.Activated("Alpha Town"), actions.toggleActive(alpha, true))
    assertEquals("alpha", active())
    assertEquals(listOf("alpha"), project.applied)
  }

  @Test
  fun `toggle on over another active region names both`() = runBlocking {
    setActive("beta")
    assertEquals(ToggleResult.Switched("Beta City", "Alpha Town"), actions.toggleActive(alpha, true))
    assertEquals("alpha", active())
  }

  @Test
  fun `a prior region missing from the cache is named by its id`() = runBlocking {
    setActive("gone")
    assertEquals(ToggleResult.Switched("gone", "Alpha Town"), actions.toggleActive(alpha, true))
  }

  @Test
  fun `an unreadable cache still switches, naming the prior region by its id`() = runBlocking {
    setActive("beta")
    listFails = true
    assertEquals(ToggleResult.Switched("beta", "Alpha Town"), actions.toggleActive(alpha, true))
    assertEquals("alpha", active())
  }

  @Test
  fun `toggling on the region that is already active is an activation, not a switch`() =
    runBlocking {
      setActive("alpha")
      assertEquals(ToggleResult.Activated("Alpha Town"), actions.toggleActive(alpha, true))
    }

  @Test
  fun `a failed copy reports it and leaves the prior active region in place`() = runBlocking {
    setActive("beta")
    project.applyResult = false
    assertEquals(ToggleResult.ApplyFailed, actions.toggleActive(alpha, true))
    assertEquals("beta", active())
  }

  @Test
  fun `toggle off clears the active region without copying`() = runBlocking {
    setActive("alpha")
    assertEquals(ToggleResult.Deactivated("Alpha Town"), actions.toggleActive(alpha, false))
    assertNull(active())
    assertTrue(project.applied.isEmpty())
  }

  @Test
  fun `deleting the active region clears active_txt`() = runBlocking {
    setActive("alpha")
    assertTrue(actions.delete(alpha))
    assertEquals(listOf("alpha"), deleted)
    assertNull(active())
  }

  @Test
  fun `deleting another region keeps the active one`() = runBlocking {
    setActive("beta")
    assertTrue(actions.delete(alpha))
    assertEquals("beta", active())
  }

  @Test
  fun `a failed delete reports false and keeps active_txt`() = runBlocking {
    setActive("alpha")
    deleteResult = false
    assertFalse(actions.delete(alpha))
    assertEquals("alpha", active())
  }

  @Test
  fun `deleting with no open project still deletes`() = runBlocking {
    project.projectDir = null
    assertTrue(actions.delete(alpha))
    assertEquals(listOf("alpha"), deleted)
  }

  @Test
  fun `redownload re-opens the wizard on the region's id, name and bounds`() {
    val bounds = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
    val event = actions.redownloadRequest(regionInfo("gamma", "Gamma", bounds))
    event as Event.RedownloadRequested
    assertEquals("gamma", event.regionId)
    assertEquals("Gamma", event.displayName)
    assertArrayEquals(bounds, event.bbox, 0.0)
  }
}
