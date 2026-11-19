package org.appdevforall.maps.slicer

import kotlinx.coroutines.runBlocking
import org.appdevforall.maps.domain.Bbox
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Upper-bound arms of [PmtilesRegionSlicer.tilesInRegionImpl]'s zoom
 * preconditions — [PmtilesRegionSlicerCoverageTest] rejects the LOW side
 * (negative zoomMin, zoomMax < zoomMin); these reject the HIGH side of the
 * same `in ..20` range checks (z=21 would mean a 4-trillion-tile walk).
 */
class PmtilesRegionSlicerZoomBoundsTest {

    private val bundledNe: File = File("src/main/assets/maps/natural-earth-z0-z4.pmtiles")
    private val world = Bbox(-85.0, -180.0, 85.0, 180.0)

    @Test
    fun rejects_zoomMin_above_20() {
        assumeTrue(bundledNe.exists())
        FileRangeFetcher(bundledNe).use { fetcher ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    PmtilesRegionSlicer.tilesInRegionImpl(fetcher, world, zoomMin = 21, zoomMax = 21)
                }
            }
        }
    }

    @Test
    fun rejects_zoomMax_above_20() {
        assumeTrue(bundledNe.exists())
        FileRangeFetcher(bundledNe).use { fetcher ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    PmtilesRegionSlicer.tilesInRegionImpl(fetcher, world, zoomMin = 4, zoomMax = 21)
                }
            }
        }
    }
}
