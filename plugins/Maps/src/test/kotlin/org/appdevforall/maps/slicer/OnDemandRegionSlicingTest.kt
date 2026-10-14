package org.appdevforall.maps.slicer

import org.appdevforall.maps.domain.Bbox
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manually-invoked test that slices a real region from
 * `iiab.switnet.org` to a file on disk. Used to seed a real
 * `tiles.pmtiles` for end-to-end M8+M9 validation of the apply path.
 *
 * Opt in via:
 *   `./gradlew :testDebugUnitTest \
 *       --tests 'org.appdevforall.maps.slicer.OnDemandRegionSlicingTest' \
 *       -DrunOnlineSlicerTests=true \
 *       -DsliceRegionId=coxs-bazar-test \
 *       -DsliceOutputDir=/tmp/m9-validate/sliced \
 *       -DsliceZoomMax=12`
 *
 * Produces `<output-dir>/<region-id>/tiles.pmtiles`. The bbox is fixed to
 * Cox's Bazar's hot-zone (z 6-12 by default for fast iteration).
 */
class OnDemandRegionSlicingTest {

    private val osmVectorUrl =
        "https://iiab.switnet.org/maps/2/openstreetmap-openmaptiles.2026-04-01.z00-z14.pmtiles"

    @Test
    fun slice_cox_bazar_to_disk() = runBlocking {
        assumeTrue(
            "Online slicer tests are opt-in. Pass -DrunOnlineSlicerTests=true to enable.",
            System.getProperty("runOnlineSlicerTests") == "true",
        )
        val regionId = System.getProperty("sliceRegionId") ?: "coxs-bazar-real"
        val outputDir = File(
            System.getProperty("sliceOutputDir") ?: "/tmp/m9-validate/sliced",
        ).apply { mkdirs() }
        val zoomMax = (System.getProperty("sliceZoomMax") ?: "12").toInt()

        val regionDir = File(outputDir, regionId).apply { mkdirs() }
        val target = File(regionDir, "tiles.pmtiles")
        // Cox's Bazar refugee camps, small bbox.
        val bbox = Bbox(21.10, 92.10, 21.30, 92.25)
        val zoomMin = 6

        println("Slicing $regionId from $osmVectorUrl → $target (z $zoomMin-$zoomMax)")
        // Open a single fetcher; read the header from it ourselves so the
        // downloadAndSlice call has the right PmtilesHeader instance.
        val fetcher = RangeFetcher.forUrl(osmVectorUrl)
        val header = fetcher.use { f ->
            val headerBytes = f.readRange(0L, PmtilesV3.HEADER_BYTES)
            PmtilesHeader.parse(
                ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            )
        }
        val tiles = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = osmVectorUrl,
            bbox = bbox,
            zoomMin = zoomMin,
            zoomMax = zoomMax,
        ).getOrThrow()
        println("Total tiles to fetch: ${tiles.size}")

        val result = PmtilesRegionSlicer.downloadAndSlice(
            tiles = tiles,
            globalPmtilesUrl = osmVectorUrl,
            sourceHeader = header,
            bbox = bbox,
            zoomMin = zoomMin,
            zoomMax = zoomMax,
            targetFile = target,
            onProgress = { downloaded, total ->
                val pct = if (total > 0) downloaded * 100 / total else 0
                if (downloaded == 0L || downloaded == total) {
                    println("  progress: $downloaded / $total ($pct%)")
                }
            },
        )
        result.getOrThrow()
        println("DONE: ${target.absolutePath} (${target.length()} bytes)")
        // Don't assert size — Cox's Bazar at z 6-12 is much smaller than full
        // 6-14 catalog estimate.
        assert(target.length() > 1024L) {
            "Expected sliced file >1 KB; got ${target.length()}"
        }
    }
}
