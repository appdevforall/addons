package org.appdevforall.maps.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Branch-coverage top-up for [ActiveRegionStore] — the branches the round-trip
 * suite in `ActiveRegionStoreTest` leaves uncovered:
 *
 *  - `clear` when neither marker file exists (the `if (f.exists())` false branch).
 *  - `readMarker` when the path is a directory, not a regular file (`isFile` false).
 *  - `write` happy path with mapsSubpath created on demand (the onSuccess log path).
 *  - `read` falling through to null when both markers are absent.
 */
class ActiveRegionStoreBranchCoverageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val mapsSubpath = "app/src/main/assets/maps"

    private fun activeFile(project: File) =
        File(project, "$mapsSubpath/${ActiveRegionStore.ACTIVE_REGION_FILE}")

    @Test
    fun clearIsIdempotentWhenNoMarkersExist() {
        // Neither active.txt nor region-id.txt present → clear is a no-op and
        // must not throw (exercises the `if (f.exists())` false branch twice).
        val project = tmp.newFolder("project")
        ActiveRegionStore.clear(project, mapsSubpath)
        assertNull(ActiveRegionStore.read(project, mapsSubpath))
    }

    @Test
    fun readIgnoresMarkerThatIsADirectory() {
        // A directory named active.txt exists → `isFile` is false, so readMarker
        // returns null and read falls through to the (absent) legacy marker.
        val project = tmp.newFolder("project")
        File(project, "$mapsSubpath/${ActiveRegionStore.ACTIVE_REGION_FILE}").mkdirs()
        assertNull(ActiveRegionStore.read(project, mapsSubpath))
    }

    @Test
    fun writeCreatesMapsSubpathOnDemand() {
        // mapsSubpath doesn't exist yet → write mkdirs() it and lands a valid id.
        val project = tmp.newFolder("project")
        val mapsRoot = File(project, mapsSubpath)
        assertFalse("precondition: subpath absent", mapsRoot.exists())

        ActiveRegionStore.write(project, mapsSubpath, "kandahar-afg")

        assertTrue("subpath created", mapsRoot.exists())
        assertTrue(activeFile(project).exists())
        assertEquals("kandahar-afg", ActiveRegionStore.read(project, mapsSubpath))
    }

    @Test
    fun writeSwallowsFailureWhenMapsPathIsBlockedByAFile() {
        // Force AtomicFiles.writeText to throw: plant a regular FILE where the
        // maps subpath's parent directory needs to be, so mkdirs() can't create
        // the maps dir and the temp-file write fails. write() must catch it (the
        // onFailure log arm) and not propagate — the panel can't crash on a
        // wedged filesystem.
        val project = tmp.newFolder("project")
        // mapsSubpath = "app/src/main/assets/maps"; block it at "app" by making
        // "app" a file, so File(project, mapsSubpath) can never be a directory.
        File(project, "app").writeText("I am a file, not a directory")

        // Must not throw.
        ActiveRegionStore.write(project, mapsSubpath, "blocked-region")

        // And nothing landed.
        assertNull(ActiveRegionStore.read(project, mapsSubpath))
    }

    @Test
    fun readPrefersActiveOverLegacyWhenBothValid() {
        // Both markers present & valid → the first readMarker returns non-null,
        // so `?.let { return it }` short-circuits before the legacy read (the
        // success arm of the first marker lookup).
        val project = tmp.newFolder("project")
        File(project, "$mapsSubpath/${ActiveRegionStore.ACTIVE_REGION_FILE}")
            .apply { parentFile!!.mkdirs(); writeText("active-region") }
        File(project, "$mapsSubpath/${ActiveRegionStore.REGION_MARKER_FILE}")
            .writeText("legacy-region")
        assertEquals("active-region", ActiveRegionStore.read(project, mapsSubpath))
    }

    @Test
    fun writeOverwritesPreviousActiveRegion() {
        // Two writes — the second wins (no stale value left behind).
        val project = tmp.newFolder("project")
        ActiveRegionStore.write(project, mapsSubpath, "first-region")
        ActiveRegionStore.write(project, mapsSubpath, "second-region")
        assertEquals("second-region", ActiveRegionStore.read(project, mapsSubpath))
    }
}
