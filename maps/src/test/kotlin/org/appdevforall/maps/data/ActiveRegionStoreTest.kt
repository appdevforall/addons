package org.appdevforall.maps.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [ActiveRegionStore]. Covers: read/write round-trip, the legacy
 * region-id.txt fallback, clear removing both markers, bounded reads, and
 * invalid-id refusal.
 */
class ActiveRegionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val mapsSubpath = "app/src/main/assets/maps"

    private fun activeFile(project: File) =
        File(project, "$mapsSubpath/${ActiveRegionStore.ACTIVE_REGION_FILE}")

    private fun legacyFile(project: File) =
        File(project, "$mapsSubpath/${ActiveRegionStore.REGION_MARKER_FILE}")

    @Test
    fun `write then read round-trips the regionId`() {
        val project = tmp.newFolder("project")
        ActiveRegionStore.write(project, mapsSubpath, "addis-ababa")
        assertEquals("addis-ababa", ActiveRegionStore.read(project, mapsSubpath))
    }

    @Test
    fun `read returns null when no marker exists`() {
        val project = tmp.newFolder("project")
        assertNull(ActiveRegionStore.read(project, mapsSubpath))
    }

    @Test
    fun `read prefers active-txt over the legacy marker`() {
        val project = tmp.newFolder("project")
        activeFile(project).apply { parentFile!!.mkdirs(); writeText("current-region") }
        legacyFile(project).writeText("old-region")
        assertEquals("current-region", ActiveRegionStore.read(project, mapsSubpath))
    }

    @Test
    fun `read falls back to the legacy region-id-txt marker`() {
        val project = tmp.newFolder("project")
        legacyFile(project).apply { parentFile!!.mkdirs(); writeText("legacy-region") }
        assertEquals("legacy-region", ActiveRegionStore.read(project, mapsSubpath))
    }

    @Test
    fun `clear removes both active-txt and the legacy marker`() {
        val project = tmp.newFolder("project")
        activeFile(project).apply { parentFile!!.mkdirs(); writeText("region-a") }
        legacyFile(project).writeText("region-b")
        ActiveRegionStore.clear(project, mapsSubpath)
        assertTrue("active.txt should be gone", !activeFile(project).exists())
        assertTrue("region-id.txt should be gone", !legacyFile(project).exists())
        assertNull(ActiveRegionStore.read(project, mapsSubpath))
    }

    @Test
    fun `write refuses an invalid regionId`() {
        val project = tmp.newFolder("project")
        ActiveRegionStore.write(project, mapsSubpath, "../escape")
        assertNull("invalid id must not land in active.txt", ActiveRegionStore.read(project, mapsSubpath))
    }

    @Test
    fun `read ignores a blank marker`() {
        val project = tmp.newFolder("project")
        activeFile(project).apply { parentFile!!.mkdirs(); writeText("   ") }
        assertNull(ActiveRegionStore.read(project, mapsSubpath))
    }

    @Test
    fun `read ignores an oversized marker`() {
        val project = tmp.newFolder("project")
        // Over the 1 KB defensive bound — even if it parses to a valid-looking id.
        activeFile(project).apply { parentFile!!.mkdirs(); writeText("a".repeat(2048)) }
        assertNull(ActiveRegionStore.read(project, mapsSubpath))
    }

    @Test
    fun `read ignores a marker whose content is not a valid regionId`() {
        val project = tmp.newFolder("project")
        activeFile(project).apply { parentFile!!.mkdirs(); writeText("Not A Valid Id!") }
        assertNull(ActiveRegionStore.read(project, mapsSubpath))
    }
}
