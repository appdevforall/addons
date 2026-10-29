package org.appdevforall.maps.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Covers [ActiveRegionStore.read] falling back to the legacy marker when active.txt cannot be read. */
class ActiveRegionStoreUnreadableTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val subpath = "app/src/main/assets/maps"

    @Test
    fun `an unreadable active marker falls back to the legacy marker`() = runBlocking {
        val project = tmp.newFolder("project")
        val active = File(project, "$subpath/${ActiveRegionStore.ACTIVE_REGION_FILE}").apply {
            parentFile.mkdirs()
            writeText("lagos")
        }
        File(project, "$subpath/${ActiveRegionStore.REGION_MARKER_FILE}").writeText("dhaka")
        active.setReadable(false)
        try {
            // Root ignores file modes, so the read would succeed and test nothing.
            assumeFalse("running as root", active.canRead())

            assertEquals("dhaka", ActiveRegionStore.read(project, subpath))
        } finally {
            active.setReadable(true)
        }
    }
}
