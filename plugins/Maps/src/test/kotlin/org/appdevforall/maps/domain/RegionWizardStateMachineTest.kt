package org.appdevforall.maps.domain

import org.appdevforall.maps.domain.RegionWizardStateMachine.Event
import org.appdevforall.maps.domain.RegionWizardStateMachine.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [RegionWizardStateMachine] — the wizard's step sequencing +
 * prefill-propagation rules extracted from `RegionManagerFragment`. Covers the
 * full new-download sequence, the redownload-prefill flow, the Step-3 Back
 * hop, the zoom-range threading, and the state reset on every list-returning
 * event.
 */
class RegionWizardStateMachineTest {

    private val machine = RegionWizardStateMachine()

    private val bbox = Bbox(6.0, 3.0, 7.0, 4.0)
    private val estimate = TileEstimate(
        tileCount = 100L,
        sizeBytesEstimate = 5_000_000L,
        zoomMin = 6,
        zoomMax = 12,
    )

    // ----- New-download happy path -----

    @Test
    fun `new download walks source - bbox - save - download - list`() {
        val source = machine.onEvent(Event.NewDownloadRequested)
        assertEquals(Step.SourcePicker(null, null), source)

        val picker = machine.onEvent(
            Event.SourceConfirmed(SourceKind.INTERNET, "iiab.switnet.org"),
        ) as Step.BboxPicker
        assertNull(picker.prefillRegionId)
        assertNull(picker.prefillRegionName)
        assertNull("fresh wizard must not carry a stale bbox", picker.prefillBbox)
        assertEquals(SourceKind.INTERNET, picker.sourceKind)
        assertEquals("iiab.switnet.org", picker.sourceHost)

        val save = machine.onEvent(
            Event.BboxPicked(bbox, estimate, prefillRegionId = null, prefillRegionName = null),
        ) as Step.SaveStep
        assertSame(bbox, save.bbox)
        assertSame(estimate, save.estimate)
        assertEquals(SourceKind.INTERNET, save.sourceKind)
        assertNull(save.prefillRegionId)

        val download = machine.onEvent(
            Event.SaveConfirmed(displayName = "Lagos", regionId = "lagos"),
        ) as Step.DownloadProgress
        assertEquals("lagos", download.regionId)
        assertEquals("Lagos", download.displayName)
        assertSame(bbox, download.bbox)
        assertEquals(SourceKind.INTERNET, download.sourceKind)
        // The picker's auto-capped zoom range is threaded to the downloader.
        assertEquals(6, download.zoomMin)
        assertEquals(12, download.zoomMax)

        assertEquals(Step.RegionList, machine.onEvent(Event.DownloadDone))
    }

    @Test
    fun `save without an estimate falls back to the downloader default zoom range`() {
        machine.onEvent(Event.NewDownloadRequested)
        machine.onEvent(Event.SourceConfirmed(SourceKind.INTERNET, null))
        machine.onEvent(Event.BboxPicked(bbox, estimate = null, prefillRegionId = null, prefillRegionName = null))
        val download = machine.onEvent(
            Event.SaveConfirmed("Lagos", "lagos"),
        ) as Step.DownloadProgress
        assertEquals(6, download.zoomMin)
        assertEquals(14, download.zoomMax)
    }

    // ----- Step 3 Back → Step 2 -----

    @Test
    fun `save back re-opens the bbox picker with the picked bbox as prefill`() {
        machine.onEvent(Event.NewDownloadRequested)
        machine.onEvent(Event.SourceConfirmed(SourceKind.INTERNET, "iiab.switnet.org"))
        machine.onEvent(Event.BboxPicked(bbox, estimate, null, null))

        val picker = machine.onEvent(Event.SaveBackRequested) as Step.BboxPicker
        assertSame("Back must re-open on the same selection", bbox, picker.prefillBbox)
        assertEquals(SourceKind.INTERNET, picker.sourceKind)
        assertEquals("iiab.switnet.org", picker.sourceHost)
    }

    // ----- Redownload (Refresh) prefill flow -----

    @Test
    fun `redownload prefills id, name and bounds through the whole wizard`() {
        val source = machine.onEvent(
            Event.RedownloadRequested(
                regionId = "lagos",
                displayName = "Lagos",
                bbox = doubleArrayOf(6.0, 3.0, 7.0, 4.0),
            ),
        ) as Step.SourcePicker
        assertEquals("lagos", source.prefillRegionId)
        assertEquals("Lagos", source.prefillRegionName)

        val picker = machine.onEvent(
            Event.SourceConfirmed(SourceKind.INTERNET, null),
        ) as Step.BboxPicker
        assertEquals("lagos", picker.prefillRegionId)
        assertEquals("Lagos", picker.prefillRegionName)
        val prefill = picker.prefillBbox
        assertTrue("saved bounds must prefill the picker", prefill != null)
        assertEquals(6.0, prefill!!.south, 0.0)
        assertEquals(3.0, prefill.west, 0.0)
        assertEquals(7.0, prefill.north, 0.0)
        assertEquals(4.0, prefill.east, 0.0)

        // Step 2 returning null prefill fields must NOT wipe the redownload ids.
        val save = machine.onEvent(
            Event.BboxPicked(bbox, estimate, prefillRegionId = null, prefillRegionName = null),
        ) as Step.SaveStep
        assertEquals("lagos", save.prefillRegionId)
        assertEquals("Lagos", save.prefillRegionName)
    }

    @Test
    fun `bbox picker can override the prefill ids when it forwards non-null ones`() {
        machine.onEvent(Event.RedownloadRequested("lagos", "Lagos", null))
        machine.onEvent(Event.SourceConfirmed(SourceKind.INTERNET, null))
        val save = machine.onEvent(
            Event.BboxPicked(bbox, estimate, prefillRegionId = "lagos-v2", prefillRegionName = "Lagos v2"),
        ) as Step.SaveStep
        assertEquals("lagos-v2", save.prefillRegionId)
        assertEquals("Lagos v2", save.prefillRegionName)
    }

    @Test
    fun `redownload drops a malformed bounds tuple instead of crashing`() {
        // Wrong size.
        machine.onEvent(Event.RedownloadRequested("a", "A", doubleArrayOf(1.0, 2.0, 3.0)))
        var picker = machine.onEvent(Event.SourceConfirmed(SourceKind.INTERNET, null)) as Step.BboxPicker
        assertNull(picker.prefillBbox)
        machine.onEvent(Event.Exit)

        // Inverted bounds (south > north) — Bbox's init{} would throw.
        machine.onEvent(Event.RedownloadRequested("b", "B", doubleArrayOf(7.0, 3.0, 6.0, 4.0)))
        picker = machine.onEvent(Event.SourceConfirmed(SourceKind.INTERNET, null)) as Step.BboxPicker
        assertNull(picker.prefillBbox)
    }

    // ----- Reset on every list-returning event -----

    @Test
    fun `exit resets all wizard state`() {
        machine.onEvent(Event.RedownloadRequested("lagos", "Lagos", doubleArrayOf(6.0, 3.0, 7.0, 4.0)))
        machine.onEvent(Event.SourceConfirmed(SourceKind.IIAB_LAN, "iiab.local"))
        machine.onEvent(Event.BboxPicked(bbox, estimate, null, null))

        assertEquals(Step.RegionList, machine.onEvent(Event.Exit))

        // A later wizard run must not inherit anything from the abandoned one.
        assertEquals(Step.SourcePicker(null, null), machine.onEvent(Event.NewDownloadRequested))
        val picker = machine.onEvent(
            Event.SourceConfirmed(SourceKind.INTERNET, null),
        ) as Step.BboxPicker
        assertNull(picker.prefillBbox)
        assertNull(picker.prefillRegionId)
        assertNull(picker.prefillRegionName)
    }

    @Test
    fun `download done, failed and cancelled all return to the list and reset`() {
        for (terminal in listOf(Event.DownloadDone, Event.DownloadFailed, Event.DownloadCancelled)) {
            machine.onEvent(Event.RedownloadRequested("lagos", "Lagos", null))
            machine.onEvent(Event.SourceConfirmed(SourceKind.INTERNET, null))
            machine.onEvent(Event.BboxPicked(bbox, estimate, null, null))

            assertEquals(Step.RegionList, machine.onEvent(terminal))

            val picker = machine.onEvent(
                Event.SourceConfirmed(SourceKind.INTERNET, null),
            ) as Step.BboxPicker
            assertNull("$terminal must reset the bbox", picker.prefillBbox)
            assertNull("$terminal must reset the prefill id", picker.prefillRegionId)
            machine.onEvent(Event.Exit)
        }
    }
}
