package org.appdevforall.maps.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Trivial enum coverage for [RegionDownloader.Phase] — `values()` and `valueOf`
 * generated members. The wizard progress UI switches on these constants, so the
 * set is part of the public contract.
 */
class RegionDownloaderPhaseTest {

    @Test
    fun enumHasExactlyTheTwoExpectedPhases() {
        val phases = RegionDownloader.Phase.values()
        assertEquals(2, phases.size)
        assertEquals(RegionDownloader.Phase.BASEMAP, phases[0])
        assertEquals(RegionDownloader.Phase.TILES, phases[1])
    }

    @Test
    fun valueOfRoundTripsEachName() {
        assertSame(
            RegionDownloader.Phase.BASEMAP,
            RegionDownloader.Phase.valueOf("BASEMAP"),
        )
        assertSame(
            RegionDownloader.Phase.TILES,
            RegionDownloader.Phase.valueOf("TILES"),
        )
    }
}
