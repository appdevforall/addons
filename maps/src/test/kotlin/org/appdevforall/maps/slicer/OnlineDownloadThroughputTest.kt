package org.appdevforall.maps.slicer

import org.appdevforall.maps.domain.Bbox
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.measureTimeMillis

/**
 * End-to-end throughput verification against the real IIAB OSM PMTiles archive.
 *
 * Runs an actual `downloadAndSlice` of a small Cox's Bazar slice (z6-z12 ≈
 * a few MB) and asserts:
 *   - the resulting file is a valid PMTiles v3 archive,
 *   - re-slicing it returns the same tile count (round-trip correctness),
 *   - throughput is at least 1 MB/s (Bryan's "few MB/s" target floor).
 *
 * **Opt-in**: only runs when `-DrunOnlineSlicerTests=true` is passed. CI
 * shouldn't hit iiab.switnet.org by default.
 *
 * If throughput is below the floor:
 *   - check the network path to iiab.switnet.org (a slow public internet hop
 *     genuinely caps us);
 *   - re-check the coalescing constants in PmtilesRegionSlicer
 *     (COALESCE_GAP_BYTES, MAX_CHUNK_BYTES, PARALLEL_FETCHES);
 *   - confirm OkHttpClient's dispatcher/connectionPool tuning in
 *     RegionDownloader hasn't regressed.
 */
class OnlineDownloadThroughputTest {

    private val osmVectorUrl =
        "https://iiab.switnet.org/maps/2/openstreetmap-openmaptiles.2026-04-01.z00-z14.pmtiles"

    @Test
    fun coxs_bazar_z6_z14_downloads_at_or_above_throughput_floor() = runBlocking {
        assumeTrue(
            "Online tests are opt-in. Pass -DrunOnlineSlicerTests=true to enable.",
            System.getProperty("runOnlineSlicerTests") == "true"
        )

        // Cox's Bazar z6-z14 is the smallest "realistic" region
        // (catalog estimate 100-150 MB). Big enough for
        // bandwidth to dominate the fixed HTTP-setup cost so the throughput
        // number is meaningful.
        val bbox = Bbox(21.10, 92.10, 21.30, 92.25)
        val zMin = 6
        val zMax = 14

        // Stage 1: discover tiles + read source header. Not on the throughput
        // clock — this is a fixed-cost setup.
        val tiles = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = osmVectorUrl,
            bbox = bbox,
            zoomMin = zMin,
            zoomMax = zMax,
        ).getOrThrow()
        assertTrue("expected tiles for Cox's Bazar z$zMin-z$zMax", tiles.isNotEmpty())
        val totalTileBytes = tiles.sumOf { it.byteLength }
        // Diagnostic: how many fetch chunks does coalescing produce? Big tile
        // count + small chunk count means coalescing collapsed a lot of
        // adjacent ranges into single requests, which is the win we want.
        val uniqueBlobs = tiles
            .map { it.byteOffset to it.byteLength }
            .distinct()
            .sortedBy { it.first }
        val chunks = PmtilesRegionSlicer.coalesceBlobs(
            uniqueBlobs,
            PmtilesRegionSlicer.COALESCE_GAP_BYTES,
        ).flatMap { PmtilesRegionSlicer.splitOversizedChunk(it, PmtilesRegionSlicer.MAX_CHUNK_BYTES) }
        println("[throughput-test] Cox's Bazar z$zMin-z$zMax: ${tiles.size} tiles, " +
            "${uniqueBlobs.size} unique blobs, ${chunks.size} fetch chunks, " +
            "${totalTileBytes / 1024L} KB total")

        // Fetch the source header once (downloadAndSlice needs it as input).
        val headerBytes = run {
            val fetcher = RangeFetcher.forUrl(osmVectorUrl)
            try { fetcher.readRange(0L, PmtilesV3.HEADER_BYTES) } finally { fetcher.close() }
        }
        val sourceHeader = PmtilesHeader.parse(
            ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        )

        // Stage 2: timed download.
        val tmp = File.createTempFile("throughput-test-", ".pmtiles")
        tmp.deleteOnExit()
        val downloadMs = measureTimeMillis {
            PmtilesRegionSlicer.downloadAndSlice(
                tiles = tiles,
                globalPmtilesUrl = osmVectorUrl,
                sourceHeader = sourceHeader,
                bbox = bbox,
                zoomMin = zMin,
                zoomMax = zMax,
                targetFile = tmp,
                onProgress = { _, _ -> },
            ).getOrThrow()
        }
        val slicedBytes = tmp.length()
        val throughputMbPerSec = (slicedBytes.toDouble() / (1024.0 * 1024.0)) /
            (downloadMs.toDouble() / 1000.0)

        println("[throughput-test] downloaded ${slicedBytes / 1024L} KB in ${downloadMs}ms = %.2f MB/s"
            .format(throughputMbPerSec))

        // Correctness: round-trip the sliced file.
        val resliced = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = "file://" + tmp.absolutePath,
            bbox = bbox,
            zoomMin = zMin,
            zoomMax = zMax,
        ).getOrThrow()
        assertTrue(
            "round-trip tile count: sliced ${tiles.size}, resliced ${resliced.size}",
            resliced.size == tiles.size,
        )

        // Throughput floor: 1 MB/s. Bryan's target is "few MB/s"; 1 MB/s is
        // the floor we should easily clear on any reasonable internet path
        // to iiab.switnet.org once the parallel slicer is in place.
        assertTrue(
            "throughput %.2f MB/s is below 1 MB/s floor for Cox's Bazar z6-z12"
                .format(throughputMbPerSec),
            throughputMbPerSec >= 1.0,
        )
    }
}
