package org.appdevforall.maps.ui

import org.appdevforall.maps.data.RegionSizeEstimator
import org.appdevforall.maps.domain.Bbox
import org.appdevforall.maps.domain.SourceKind
import org.appdevforall.maps.slicer.TileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BboxSelectionModel] — the bbox/estimate state transitions
 * extracted from `BboxPickerFragment`. Pure JVM: no Robolectric, no Android.
 */
class BboxSelectionModelTest {

    private val initialBbox = Bbox.aroundPoint(0.0, 0.0, 200.0)

    private fun newModel() = BboxSelectionModel(initialBbox)

    private fun tile(bytes: Long) =
        TileEntry(z = 10, x = 1, y = 1, tileId = 0L, byteOffset = 0L, byteLength = bytes)

    // ----- Initial state -----

    @Test
    fun `initial state - constructor bbox, defaults, no fix, no estimate`() {
        val m = newModel()
        assertSame(initialBbox, m.currentBbox)
        assertEquals(BboxPickerArgs.DEFAULT_ZOOM_MIN, m.zoomMin)
        assertEquals(BboxPickerArgs.DEFAULT_ZOOM_MAX, m.zoomMax)
        assertEquals(0.0, m.anchorLat, 0.0)
        assertEquals(0.0, m.anchorLon, 0.0)
        assertFalse(m.haveRealGpsFix)
        assertNull(m.realByteEstimate)
        assertFalse(m.realEstimateFailed)
        assertEquals(SourceKind.UNKNOWN, m.sourceKind)
        assertNull(m.sourceHost)
    }

    // ----- applyLaunchArgs -----

    @Test
    fun `applyLaunchArgs seeds source context and zoom range`() {
        val m = newModel()
        m.applyLaunchArgs(
            sourceKind = SourceKind.IIAB_LAN,
            sourceHost = "iiab.local",
            zoomMin = 4,
            zoomMax = 12,
            prefillBbox = null,
        )
        assertEquals(SourceKind.IIAB_LAN, m.sourceKind)
        assertEquals("iiab.local", m.sourceHost)
        assertEquals(4, m.zoomMin)
        assertEquals(12, m.zoomMax)
        // No prefill → the constructor bbox stays.
        assertSame(initialBbox, m.currentBbox)
    }

    @Test
    fun `applyLaunchArgs with a prefill bbox adopts it and re-centers the anchor`() {
        val m = newModel()
        m.applyLaunchArgs(
            sourceKind = SourceKind.INTERNET,
            sourceHost = null,
            zoomMin = 6,
            zoomMax = 14,
            prefillBbox = doubleArrayOf(6.0, 3.0, 7.0, 4.0), // S, W, N, E
        )
        assertEquals(6.0, m.currentBbox.south, 0.0)
        assertEquals(3.0, m.currentBbox.west, 0.0)
        assertEquals(7.0, m.currentBbox.north, 0.0)
        assertEquals(4.0, m.currentBbox.east, 0.0)
        assertEquals(6.5, m.anchorLat, 1e-9)
        assertEquals(3.5, m.anchorLon, 1e-9)
        // A saved-bounds prefill is not a GPS fix.
        assertFalse(m.haveRealGpsFix)
    }

    @Test
    fun `applyLaunchArgs with an invalid prefill bbox keeps the default selection`() {
        val m = newModel()
        m.applyLaunchArgs(
            sourceKind = SourceKind.INTERNET,
            sourceHost = null,
            zoomMin = 6,
            zoomMax = 14,
            prefillBbox = doubleArrayOf(7.0, 3.0, 6.0, 4.0), // south > north → invalid
        )
        // The malformed bounds are dropped; the constructor default survives.
        assertSame(initialBbox, m.currentBbox)
    }

    @Test
    fun `applyLaunchArgs ignores a wrong-size prefill array`() {
        val m = newModel()
        m.applyLaunchArgs(
            sourceKind = SourceKind.INTERNET,
            sourceHost = null,
            zoomMin = 6,
            zoomMax = 14,
            prefillBbox = doubleArrayOf(1.0, 2.0, 3.0), // size 3
        )
        assertSame(initialBbox, m.currentBbox)
        assertEquals(0.0, m.anchorLat, 0.0)
    }

    // ----- applyBboxChange -----

    @Test
    fun `applyBboxChange adopts the bbox and invalidates the estimate`() {
        val m = newModel()
        m.onEstimateState(RegionSizeEstimator.State.Done(listOf(tile(1000L))))
        assertNotNull(m.realByteEstimate)

        val newBbox = Bbox.aroundPoint(10.0, 10.0, 50.0)
        m.applyBboxChange(newBbox)

        assertSame(newBbox, m.currentBbox)
        assertNull("bbox change must invalidate the stale estimate", m.realByteEstimate)
        assertFalse(m.realEstimateFailed)
    }

    @Test
    fun `applyBboxChange re-caps zoomMax - a small bbox allows deeper zoom than a huge one`() {
        val small = newModel().apply { applyBboxChange(Bbox.aroundPoint(10.0, 10.0, 10.0)) }
        val huge = newModel().apply { applyBboxChange(Bbox(-60.0, -170.0, 60.0, 170.0)) }
        assertTrue(
            "10 km box (zMax=${small.zoomMax}) must out-zoom a near-world box (zMax=${huge.zoomMax})",
            small.zoomMax > huge.zoomMax,
        )
        assertTrue(huge.zoomMax >= huge.zoomMin)
    }

    @Test
    fun `applyBboxChange clears a failed estimate state`() {
        val m = newModel()
        m.onEstimateState(RegionSizeEstimator.State.Failed)
        assertTrue(m.realEstimateFailed)
        m.applyBboxChange(Bbox.aroundPoint(5.0, 5.0, 50.0))
        assertFalse(m.realEstimateFailed)
    }

    // ----- onEstimateState -----

    @Test
    fun `estimate Done sums tile bytes plus overhead`() {
        val m = newModel()
        m.onEstimateState(RegionSizeEstimator.State.Done(listOf(tile(1000L), tile(500L))))
        // PmtilesRegionSlicer.estimateRegionBytes = Σ byteLength + 8 KB overhead.
        assertEquals(1000L + 500L + 8L * 1024L, m.realByteEstimate)
        assertFalse(m.realEstimateFailed)
    }

    @Test
    fun `estimate Failed flags the failure but keeps the last bytes`() {
        val m = newModel()
        m.onEstimateState(RegionSizeEstimator.State.Done(listOf(tile(1000L))))
        val lastBytes = m.realByteEstimate
        m.onEstimateState(RegionSizeEstimator.State.Failed)
        assertTrue(m.realEstimateFailed)
        assertEquals("Failed must not wipe the last known bytes", lastBytes, m.realByteEstimate)
    }

    @Test
    fun `estimate Calculating clears a stale failure flag`() {
        val m = newModel()
        m.onEstimateState(RegionSizeEstimator.State.Failed)
        m.onEstimateState(RegionSizeEstimator.State.Calculating)
        assertFalse(m.realEstimateFailed)
    }

    // ----- hasSliceableSource -----

    @Test
    fun `hasSliceableSource - internet always, LAN only with a host, unknown never`() {
        val m = newModel()
        assertFalse("UNKNOWN source is not sliceable", m.hasSliceableSource())

        m.applyLaunchArgs(SourceKind.INTERNET, null, 6, 14, null)
        assertTrue(m.hasSliceableSource())

        m.applyLaunchArgs(SourceKind.IIAB_LAN, null, 6, 14, null)
        assertFalse("LAN without a host is not sliceable", m.hasSliceableSource())

        m.applyLaunchArgs(SourceKind.IIAB_LAN, "  ", 6, 14, null)
        assertFalse("LAN with a blank host is not sliceable", m.hasSliceableSource())

        m.applyLaunchArgs(SourceKind.IIAB_LAN, "iiab.local", 6, 14, null)
        assertTrue(m.hasSliceableSource())
    }

    // ----- maybeShrinkToFit -----

    @Test
    fun `maybeShrinkToFit no-ops when the bbox is fully inside the viewport`() {
        val m = newModel()
        val bbox = Bbox(-1.0, -1.0, 1.0, 1.0)
        m.applyBboxChange(bbox)
        m.noteUserCameraGesture()
        m.onEstimateState(RegionSizeEstimator.State.Done(listOf(tile(1000L))))

        val result = m.maybeShrinkToFit(
            viewN = 5.0, viewS = -5.0, viewE = 5.0, viewW = -5.0, margin = 0.35,
        )

        assertNull(result)
        assertSame("a no-op must not touch the selection", bbox, m.currentBbox)
        assertNotNull("a no-op must not invalidate the estimate", m.realByteEstimate)
    }

    @Test
    fun `maybeShrinkToFit shrinks an out-of-viewport bbox and invalidates the estimate`() {
        val m = newModel()
        m.applyBboxChange(Bbox(0.0, 0.0, 10.0, 10.0))
        m.noteUserCameraGesture()
        m.onEstimateState(RegionSizeEstimator.State.Done(listOf(tile(1000L))))

        val result = m.maybeShrinkToFit(
            viewN = 5.0, viewS = -5.0, viewE = 5.0, viewW = -5.0, margin = 0.35,
        )

        assertNotNull("bbox pokes out of the viewport → must shrink", result)
        assertSame(result, m.currentBbox)
        // Shrunk box sits inside the viewport (inset by the margin).
        assertTrue(m.currentBbox.north <= 5.0 && m.currentBbox.south >= -5.0)
        assertTrue(m.currentBbox.east <= 5.0 && m.currentBbox.west >= -5.0)
        assertNull("a real shrink is a bbox change → estimate invalidated", m.realByteEstimate)
    }

    // ----- Auto-shrink vs prefilled (Refresh) selection — ADFA-2436 regression -----

    /**
     * On-device regression repro (A56, region Refresh): the wizard opened Step 2
     * prefilled with the region's saved bounds — meta.json bbox
     * `[22.94, -25.62, 33.08, -16.19]` (south, west, north, east; ~926×1128 km) —
     * and a camera-idle-driven auto-shrink check ran against a transient viewport
     * of ~327×533 km while the fullscreen dialog was still settling. The shrink
     * collapsed the saved selection to 30 % of that viewport ≈ **98×160 km**, a
     * ~10× linear loss of the user's saved extent, before the user touched
     * anything.
     *
     * The contract this pins: a selection the user has NOT driven the camera over
     * (no pan/zoom gesture yet) is authoritative — auto-shrink must never fire on
     * it, regardless of what viewport the camera-idle happens to report.
     */
    @Test
    fun `auto-shrink must not fire on a prefilled selection before any user camera gesture`() {
        val m = newModel()
        m.applyLaunchArgs(
            sourceKind = SourceKind.INTERNET,
            sourceHost = null,
            zoomMin = 6,
            zoomMax = 14,
            // The exact saved bounds from the on-device repro (s, w, n, e).
            prefillBbox = doubleArrayOf(22.94, -25.62, 33.08, -16.19),
        )
        val saved = m.currentBbox
        m.onEstimateState(RegionSizeEstimator.State.Done(listOf(tile(1000L))))

        // Transient viewport at the idle+debounce mark: ~327×533 km centred on
        // the saved box (lat span 4.79°, lon span 3.33°) — smaller than the
        // selection, so the naive check sees "edges outside" and shrinks.
        val result = m.maybeShrinkToFit(
            viewN = 30.41, viewS = 25.62, viewE = -19.24, viewW = -22.57, margin = 0.35,
        )

        assertNull(
            "a prefilled selection the user hasn't touched must never be auto-shrunk " +
                "(on-device this collapsed 926×1128 km to ~98×160 km)",
            result,
        )
        assertSame("the saved bounds must survive untouched", saved, m.currentBbox)
        assertNotNull("the estimate for the saved bounds must not be invalidated", m.realByteEstimate)
    }

    @Test
    fun `auto-shrink resumes for a prefilled selection after a real user camera gesture`() {
        val m = newModel()
        m.applyLaunchArgs(
            sourceKind = SourceKind.INTERNET,
            sourceHost = null,
            zoomMin = 6,
            zoomMax = 14,
            prefillBbox = doubleArrayOf(22.94, -25.62, 33.08, -16.19),
        )

        // The user pans/zooms (Fragment reports REASON_API_GESTURE) — from here
        // on the selection tracks the viewport, Google-Maps-style.
        m.noteUserCameraGesture()

        val result = m.maybeShrinkToFit(
            viewN = 30.41, viewS = 25.62, viewE = -19.24, viewW = -22.57, margin = 0.35,
        )

        assertNotNull("after a user gesture the selection must track the viewport", result)
        assertSame(result, m.currentBbox)
        assertTrue(m.currentBbox.north <= 30.41 && m.currentBbox.south >= 25.62)
        assertTrue(m.currentBbox.east <= -19.24 && m.currentBbox.west >= -22.57)
    }

    @Test
    fun `default (non-prefill) selection is also protected until the first user gesture`() {
        val m = newModel()
        // No prefill: the constructor default (200×200 km at 0,0) is programmatic
        // placement too — a spurious early camera-idle must not collapse it either.
        val result = m.maybeShrinkToFit(
            viewN = 0.5, viewS = -0.5, viewE = 0.5, viewW = -0.5, margin = 0.35,
        )
        assertNull(result)
        assertSame(initialBbox, m.currentBbox)
    }

    // ----- Anchor / GPS fix -----

    @Test
    fun `updateAnchor moves the anchor without claiming a GPS fix`() {
        val m = newModel()
        m.updateAnchor(12.5, -3.25)
        assertEquals(12.5, m.anchorLat, 0.0)
        assertEquals(-3.25, m.anchorLon, 0.0)
        assertFalse("a camera-idle anchor is NOT a GPS fix", m.haveRealGpsFix)
    }

    @Test
    fun `applyLocationFix moves the anchor and records the real fix`() {
        val m = newModel()
        m.applyLocationFix(6.52, 3.37)
        assertEquals(6.52, m.anchorLat, 0.0)
        assertEquals(3.37, m.anchorLon, 0.0)
        assertTrue(m.haveRealGpsFix)
    }
}
