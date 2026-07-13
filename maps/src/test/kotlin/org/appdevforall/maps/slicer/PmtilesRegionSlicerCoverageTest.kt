package org.appdevforall.maps.slicer

import org.appdevforall.maps.domain.Bbox
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Branch-coverage tests for [PmtilesRegionSlicer]'s orchestrator paths that the
 * happy-path slicer tests don't reach:
 *
 *  - `tilesInRegion` zoom-clip-to-empty (`zMin > zMax`) and `runCatching` failure.
 *  - `tilesInRegionImpl` `require(...)` precondition branches.
 *  - `tileIdRangesForBbox` empty / single-tile / multi-zoom branches.
 *  - `splitOversizedChunk` mid-stream "close out a non-empty part before a blob
 *    that would overflow" branch.
 *  - `downloadAndSlice` empty-tile-list precondition + dedup/run-length path.
 *  - `walkEntries` leaf-pointer arm — leaf fetch + recursion + per-leaf merge
 *    AND the non-overlapping-leaf `continue` — via a synthetic TWO-level archive
 *    (the bundled NE archive has `leafDirsBytes==0`, so its directory is flat and
 *    those branches are otherwise unreachable offline).
 *
 * Everything runs wholly offline against the bundled Natural Earth z0-z4 archive
 * (same source the existing slicer tests load), pure-Kotlin helper inputs, and a
 * hand-built leaf-bearing PMTiles fixture written to a temp file.
 */
class PmtilesRegionSlicerCoverageTest {

    private val bundledNe: File = File("src/main/assets/maps/natural-earth-z0-z4.pmtiles")

    private val tempFiles = mutableListOf<File>()

    @After
    fun cleanup() {
        tempFiles.forEach { f ->
            f.delete()
            File(f.parentFile, f.name + ".partial").delete()
        }
        tempFiles.clear()
    }

    private fun sourceUrl(): String = "file://" + bundledNe.absolutePath

    private fun sourceHeader(): PmtilesHeader {
        val headerBytes = bundledNe.inputStream().use { it.readNBytes(PmtilesV3.HEADER_BYTES) }
        return PmtilesHeader.parse(
            ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        )
    }

    // ---------- tilesInRegion / tilesInRegionImpl branches ----------

    @Test
    fun tilesInRegion_zooms_above_archive_max_clip_to_empty() = runBlocking {
        assumeTrue("bundled NE archive must exist", bundledNe.exists())
        // NE z0-z4 has maxZoom=4. Requesting zoomMin=10..zoomMax=10 means after
        // clipping zMin=10, zMax=min(10,4)=4 → zMin > zMax → emptyList branch.
        val tiles = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = sourceUrl(),
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 10,
            zoomMax = 10,
        ).getOrThrow()
        assertTrue("zooms entirely above archive max produce no tiles", tiles.isEmpty())
    }

    @Test
    fun tilesInRegion_bad_url_returns_failure_result() = runBlocking {
        assumeTrue(bundledNe.exists())
        // A file:// URL pointing at a nonexistent path makes RangeFetcher throw,
        // which runCatching captures → Result.isFailure (the tilesInRegion$2
        // failure lambda).
        val result = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = "file:///definitely/not/a/real/path/nope.pmtiles",
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 0,
            zoomMax = 0,
        )
        assertTrue("nonexistent source must yield a failure Result", result.isFailure)
    }

    @Test
    fun tilesInRegionImpl_rejects_zoomMin_out_of_range() = runBlocking {
        assumeTrue(bundledNe.exists())
        // zoomMin=21 violates require(zoomMin in 0..20). runCatching wraps the
        // IllegalArgumentException into a failure Result.
        val result = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = sourceUrl(),
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 21,
            zoomMax = 21,
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun tilesInRegionImpl_rejects_zoomMax_below_zoomMin() = runBlocking {
        assumeTrue(bundledNe.exists())
        // zoomMax(0) < zoomMin(3) violates require(zoomMax in zoomMin..20).
        val result = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = sourceUrl(),
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 3,
            zoomMax = 0,
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun tilesInRegion_single_zoom_in_archive_range_returns_tiles() = runBlocking {
        assumeTrue(bundledNe.exists())
        // z2 only — exercises the zMin..zMax single-zoom associateWith path and
        // the non-leaf inline tile-collection branch in walkEntries.
        val tiles = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = sourceUrl(),
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 2,
            zoomMax = 2,
        ).getOrThrow()
        assertTrue("z2 worldwide returns tiles", tiles.isNotEmpty())
        assertTrue("all tiles are z2", tiles.all { it.z == 2 })
    }

    @Test
    fun tilesInRegion_tiny_bbox_filters_to_small_subset() = runBlocking {
        assumeTrue(bundledNe.exists())
        // A tiny bbox at a single point exercises tileIntersectsBbox's filter
        // (most tiles continue-skipped) and tileIdRangesForBbox's single-tile
        // ranges across z0..z4.
        val tiny = Bbox(0.0, 0.0, 0.001, 0.001)
        val tiles = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = sourceUrl(),
            bbox = tiny,
            zoomMin = 0,
            zoomMax = 4,
        ).getOrThrow()
        // At most one tile per zoom level (z0..z4 = 5).
        assertTrue("tiny bbox collects few tiles, got ${tiles.size}", tiles.size in 1..6)
    }

    @Test
    fun tilesInRegion_wide_thin_latitude_band_rejects_gap_tiles_by_bbox() = runBlocking {
        assumeTrue(bundledNe.exists())
        // A full-longitude, narrow-latitude band at z3..z4 is NOT a
        // Hilbert-contiguous region: the Hilbert query returns id-ranges that
        // weave through OTHER latitude rows, so some candidate tile-ids pass the
        // per-zoom id-range pre-filter yet land in rows the band doesn't touch.
        // Those candidates reach tileIntersectsBbox and must be rejected by its
        // latitude conditions (`tileNorth > bbox.south` / `tileSouth < bbox.north`
        // false). Proof the filter ran: every returned tile genuinely intersects
        // the band, and the count is a strict subset of the full-world count.
        val band = Bbox(south = -1.0, west = -180.0, north = 1.0, east = 180.0)
        val tiles = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = sourceUrl(),
            bbox = band,
            zoomMin = 3,
            zoomMax = 4,
        ).getOrThrow()
        assertTrue("band intersects the archive → some tiles", tiles.isNotEmpty())
        // Every surviving tile must actually overlap the band in latitude.
        for (t in tiles) {
            val n = Math.pow(2.0, t.z.toDouble())
            val tileNorth = tileLat(t.y, n)
            val tileSouth = tileLat(t.y + 1, n)
            assertTrue(
                "tile z${t.z}/${t.x}/${t.y} (lat [$tileSouth,$tileNorth]) must overlap band",
                tileNorth > band.south && tileSouth < band.north,
            )
        }
        // A thin band is a strict subset of the whole world at the same zooms.
        val whole = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = sourceUrl(),
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 3,
            zoomMax = 4,
        ).getOrThrow()
        assertTrue("band drops some tiles vs whole world", tiles.size < whole.size)
    }

    @Test
    fun tilesInRegion_tall_thin_longitude_band_rejects_gap_tiles_by_bbox() = runBlocking {
        assumeTrue(bundledNe.exists())
        // The mirror of the latitude-band case: a full-latitude, narrow-longitude
        // strip forces tileIntersectsBbox's LONGITUDE conditions
        // (`tileEast > bbox.west` / `tileWest < bbox.east` false) to fire for the
        // Hilbert-gap candidates that live in other longitude columns.
        val strip = Bbox(south = -84.0, west = -1.0, north = 84.0, east = 1.0)
        val tiles = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = sourceUrl(),
            bbox = strip,
            zoomMin = 3,
            zoomMax = 4,
        ).getOrThrow()
        assertTrue("strip intersects the archive → some tiles", tiles.isNotEmpty())
        for (t in tiles) {
            val n = Math.pow(2.0, t.z.toDouble())
            val tileWest = t.x / n * 360.0 - 180.0
            val tileEast = (t.x + 1) / n * 360.0 - 180.0
            assertTrue(
                "tile z${t.z}/${t.x}/${t.y} (lon [$tileWest,$tileEast]) must overlap strip",
                tileEast > strip.west && tileWest < strip.east,
            )
        }
        val whole = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = sourceUrl(),
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 3,
            zoomMax = 4,
        ).getOrThrow()
        assertTrue("strip drops some tiles vs whole world", tiles.size < whole.size)
    }

    /** Slippy-map tile north/south latitude (degrees) for tile row [y] on an n×n grid. */
    private fun tileLat(y: Int, n: Double): Double {
        val latRad = Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / n)))
        return Math.toDegrees(latRad)
    }

    // ---------- tileIdRangesForBbox branches ----------

    @Test
    fun tileIdRangesForBbox_single_tile_at_z0() {
        // z0 has exactly one tile (0,0). Range list is non-empty and covers
        // tile-id 0 (base accumulate(0)=0).
        val ranges = TileMath.tileIdRangesForBbox(
            Bbox(-85.0, -180.0, 85.0, 180.0), 0
        )
        assertEquals(1, ranges.size)
        assertTrue("z0 range covers tile-id 0", 0L in ranges[0])
    }

    @Test
    fun tileIdRangesForBbox_full_world_multi_tile_at_z2() {
        // z2 full world: xMin=0..xMax=3, yMin..yMax span the whole grid → the
        // Hilbert query returns one or more ranges covering all 16 ids.
        val ranges = TileMath.tileIdRangesForBbox(
            Bbox(-85.0, -180.0, 85.0, 180.0), 2
        )
        assertTrue("z2 full-world produces at least one range", ranges.isNotEmpty())
        val base = Hilbert.accumulate(2)
        // Every z2 tile-id (base..base+15) must be inside some returned range.
        for (id in base until base + 16) {
            assertTrue("tile-id $id must be covered", ranges.any { id in it })
        }
    }

    @Test
    fun tileIdRangesForBbox_west_edge_clamps_into_grid() {
        // West edge of the world at z3 — coerceIn clamps xMin to 0; the result
        // is a valid non-empty range list, exercising the coerceIn branches.
        val ranges = TileMath.tileIdRangesForBbox(
            Bbox(-80.0, -180.0, -70.0, -179.0), 3
        )
        assertTrue("west-edge bbox yields ranges", ranges.isNotEmpty())
    }

    // ---------- splitOversizedChunk mid-stream break branch ----------

    @Test
    fun splitOversizedChunk_closes_part_before_overflowing_blob() {
        // A small blob followed by a large blob, both inside a chunk that
        // exceeds maxBytes. Processing the small blob first leaves partBlobs
        // non-empty; the next (large) blob would push blobEnd-currentStart past
        // maxBytes, so the loop must `break` and close the part — exercising the
        // `partBlobs.isNotEmpty() && blobEnd - currentStart > maxBytes` branch.
        val small = 0L to 1_000L
        val large = 1_000L to 5_000_000L
        val chunk = PmtilesRegionSlicer.FetchChunk(
            offset = 0L,
            length = 5_001_000L,
            blobs = listOf(small, large),
        )
        val parts = PmtilesRegionSlicer.splitOversizedChunk(chunk, 4_000_000L)
        assertTrue("must split into >= 2 parts", parts.size >= 2)
        // The small blob is alone in the first part (the large one was deferred).
        assertEquals(listOf(small), parts[0].blobs)
        assertEquals(listOf(large), parts[1].blobs)
        // Every blob appears exactly once.
        val all = parts.flatMap { it.blobs }
        assertEquals(setOf(small, large), all.toSet())
    }

    @Test
    fun splitOversizedChunk_multiple_small_blobs_pack_then_break() {
        // Five 1.5 MB blobs in a 7.5 MB chunk, maxBytes=4 MB. The first part
        // packs blobs until adding the next would overflow, then breaks; the
        // remainder forms subsequent parts. Exercises both the pack-and-continue
        // and the break-on-overflow arms.
        val blobs = (0 until 5).map { (it * 1_500_000L) to 1_500_000L }
        val chunk = PmtilesRegionSlicer.FetchChunk(0L, 7_500_000L, blobs)
        val parts = PmtilesRegionSlicer.splitOversizedChunk(chunk, 4_000_000L)
        assertTrue("expected multiple parts", parts.size >= 2)
        for (p in parts) {
            assertTrue("part ${p.length} within cap (or single oversized blob)",
                p.length <= 4_000_000L || p.blobs.size == 1)
        }
        assertEquals(blobs.toSet(), parts.flatMap { it.blobs }.toSet())
    }

    // ---------- downloadAndSlice branches ----------

    @Test
    fun downloadAndSlice_empty_tiles_fails_precondition() = runBlocking {
        assumeTrue(bundledNe.exists())
        val tmp = File.createTempFile("empty-slice-", ".pmtiles").also { tempFiles += it }
        val result = PmtilesRegionSlicer.downloadAndSlice(
            tiles = emptyList(),
            globalPmtilesUrl = sourceUrl(),
            sourceHeader = sourceHeader(),
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 0,
            zoomMax = 0,
            targetFile = tmp,
            onProgress = { _, _ -> },
        )
        assertTrue("empty tile list must fail the require(...)", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun downloadAndSlice_overwrites_existing_target_file() = runBlocking {
        assumeTrue(bundledNe.exists())
        val src = sourceUrl()
        val tiles = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = src,
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 0,
            zoomMax = 2,
        ).getOrThrow()
        assertTrue(tiles.isNotEmpty())

        // Pre-create the target so the `if (targetFile.exists()) delete()` branch
        // runs. Also leave a stale `.partial` so its delete branch runs too.
        val tmp = File.createTempFile("overwrite-", ".pmtiles").also { tempFiles += it }
        tmp.writeText("stale contents that must be replaced")
        File(tmp.parentFile, tmp.name + ".partial").writeText("stale partial")

        var lastProgress = -1L to -1L
        PmtilesRegionSlicer.downloadAndSlice(
            tiles = tiles,
            globalPmtilesUrl = src,
            sourceHeader = sourceHeader(),
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 0,
            zoomMax = 2,
            targetFile = tmp,
            onProgress = { d, t -> lastProgress = d to t },
        ).getOrThrow()

        assertTrue("output rewritten as a real pmtiles file",
            tmp.length() > PmtilesV3.HEADER_BYTES.toLong())
        assertNotNull(lastProgress)
        assertTrue("final downloaded reached total", lastProgress.first == lastProgress.second)

        val sliced = PmtilesHeader.parse(
            ByteBuffer.wrap(tmp.inputStream().use { it.readNBytes(PmtilesV3.HEADER_BYTES) })
                .order(ByteOrder.LITTLE_ENDIAN)
        )
        assertEquals(3.toByte(), sliced.version)
        assertEquals(1.toByte(), sliced.clustered)
    }

    @Test
    fun downloadAndSlice_single_zoom_run_length_dedup_path() = runBlocking {
        assumeTrue(bundledNe.exists())
        val src = sourceUrl()
        // z0 single tile — exercises the run-length build loop with a one-tile
        // input (runEnd never extends) and the dedup blobLocalOffset path.
        val tiles = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = src,
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 0,
            zoomMax = 0,
        ).getOrThrow()
        assertEquals(1, tiles.size)

        val tmp = File.createTempFile("z0-slice-", ".pmtiles").also { tempFiles += it }
        PmtilesRegionSlicer.downloadAndSlice(
            tiles = tiles,
            globalPmtilesUrl = src,
            sourceHeader = sourceHeader(),
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 0,
            zoomMax = 0,
            targetFile = tmp,
            onProgress = { _, _ -> },
        ).getOrThrow()

        val resliced = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = "file://" + tmp.absolutePath,
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 0,
            zoomMax = 0,
        ).getOrThrow()
        assertEquals(tiles.size, resliced.size)
    }

    @Test
    fun downloadAndSlice_extends_run_for_contiguous_tiles_sharing_one_blob() = runBlocking {
        assumeTrue(bundledNe.exists())
        // The run-length EXTEND arm of the directory builder fires only when two
        // CONTIGUOUS tile-ids point at the SAME (byteOffset, byteLength) blob. The
        // NE archive's natural tiling rarely produces that pair for a real bbox, so
        // construct the TileEntry list by hand: two contiguous tile-ids (10, 11)
        // both aliasing NE's real tile-0 blob (absOffset 1215, length 30122). The
        // blob bytes genuinely exist in the source file, so the chunk fetch reads
        // real data; the builder must collapse the two entries into one run-length-2
        // directory entry (a single content blob, dedup preserved).
        val src = sourceUrl()
        val sharedOffset = 1215L
        val sharedLength = 30122L
        val tiles = listOf(
            TileEntry(z = 2, x = 0, y = 0, tileId = 10L, byteOffset = sharedOffset, byteLength = sharedLength),
            TileEntry(z = 2, x = 1, y = 0, tileId = 11L, byteOffset = sharedOffset, byteLength = sharedLength),
        )

        val tmp = File.createTempFile("run-extend-", ".pmtiles").also { tempFiles += it }
        var lastProgress = -1L to -1L
        PmtilesRegionSlicer.downloadAndSlice(
            tiles = tiles,
            globalPmtilesUrl = src,
            sourceHeader = sourceHeader(),
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 2,
            zoomMax = 2,
            targetFile = tmp,
            onProgress = { d, t -> lastProgress = d to t },
        ).getOrThrow()

        // Only ONE unique blob → total bytes = the single blob length (dedup),
        // not the doubled sum. That's the dedup+run-extend payoff.
        assertEquals("dedup collapses both entries to one blob's bytes",
            sharedLength, lastProgress.second)
        assertEquals("download reached total", lastProgress.first, lastProgress.second)

        // The written header reports two addressed tiles but one tile-content,
        // and a single tile-entry (the collapsed run) — proof the run extended.
        val sliced = PmtilesHeader.parse(
            ByteBuffer.wrap(tmp.inputStream().use { it.readNBytes(PmtilesV3.HEADER_BYTES) })
                .order(ByteOrder.LITTLE_ENDIAN)
        )
        assertEquals("two addressed tiles", 2L, sliced.addressedTilesCount)
        assertEquals("collapsed into ONE run entry", 1L, sliced.tileEntriesCount)
        assertEquals("one shared content blob", 1L, sliced.tileContentsCount)
    }

    // ---------- estimateRegionBytes empty branch ----------

    @Test
    fun estimateRegionBytes_empty_list_is_just_overhead() {
        // No tiles → sum is 0, estimate is the fixed 8 KB overhead.
        val estimate = PmtilesRegionSlicer.estimateRegionBytes(emptyList())
        assertEquals(8L * 1024L, estimate)
        assertFalse(estimate == 0L)
    }

    // ---------- walkEntries leaf-pointer branches (synthetic leaf archive) ----------

    @Test
    fun tilesInRegion_walks_leaf_directory_and_skips_non_overlapping_leaf() = runBlocking {
        // The bundled NE archive has leafDirsBytes==0, so its root directory holds
        // every entry inline — the leaf-pointer arm of walkEntries (the async leaf
        // fetch + recursion) is never exercised by any file:// test against it.
        // Build a tiny well-formed v3 archive with a TWO-level directory:
        //   root: [leaf-pointer @tileId 0] [leaf-pointer @tileId 1000]
        //   leafA (covers ids [0,1000)): one real z0 tile entry
        //   leafB (covers ids [1000,MAX)): one z-high tile entry, out of our zoom
        // Slicing z0..z0 full-world makes leafA OVERLAP (fetched + recursed) and
        // leafB NOT overlap (`if (!anyOverlap) continue`), covering both arms plus
        // the parallel leaf-fetch coroutineScope and the per-leaf merge loop.
        val archive = buildLeafBearingArchive().also { tempFiles += it.file }

        val tiles = PmtilesRegionSlicer.tilesInRegion(
            globalPmtilesUrl = "file://" + archive.file.absolutePath,
            bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
            zoomMin = 0,
            zoomMax = 0,
        ).getOrThrow()

        // Exactly the single z0 tile from leafA; the leafB entry was zoom-excluded
        // AND its leaf was never fetched (non-overlap continue).
        assertEquals("leaf walk yields the one z0 tile", 1, tiles.size)
        val t = tiles.single()
        assertEquals(0, t.z)
        assertEquals(0L, t.tileId)
        // byteOffset = header.tileDataOffset + entry.offset; entry.offset was 0.
        assertEquals(archive.tileDataOffset, t.byteOffset)
        assertEquals(LEAF_ARCHIVE_TILE_LEN, t.byteLength)
    }

    @Test
    fun tilesInRegionImpl_directly_on_open_fetcher_walks_leaves() = runBlocking {
        // Exercise the internal `tilesInRegionImpl(fetcher, ...)` entry point
        // (the "caller already has a RangeFetcher open" overload) against the
        // synthetic leaf archive, so its leaf-walk path is covered through the
        // fetcher-passing variant too.
        val archive = buildLeafBearingArchive().also { tempFiles += it.file }
        RangeFetcher.forUrl("file://" + archive.file.absolutePath).use { fetcher ->
            val tiles = PmtilesRegionSlicer.tilesInRegionImpl(
                fetcher = fetcher,
                bbox = Bbox(-85.0, -180.0, 85.0, 180.0),
                zoomMin = 0,
                zoomMax = 0,
            )
            assertEquals(1, tiles.size)
            assertEquals(0L, tiles.single().tileId)
        }
    }

    // ---------- synthetic leaf-bearing archive builder ----------

    /** A synthetic archive plus the offsets a test needs to assert against. */
    private class LeafArchive(val file: File, val tileDataOffset: Long)

    /**
     * Write a minimal but spec-valid PMTiles v3 archive that uses a real LEAF
     * directory level (which the bundled NE archive does not), so the slicer's
     * leaf-pointer / leaf-fetch / recursive-walk branches become reachable
     * offline via `file://`. Layout:
     * ```
     *   [0..127)            header
     *   [127..rootEnd)      root dir (gzip): 2 leaf pointers
     *   [rootEnd..metaEnd)  json metadata (empty {} — unused by tilesInRegion)
     *   [metaEnd..tdOff)    leaf section: leafA then leafB (each gzip)
     *   [tdOff..)           tile-data placeholder (never read by tilesInRegion)
     * ```
     */
    private fun buildLeafBearingArchive(): LeafArchive {
        val comp = PmtilesV3.COMPRESSION_GZIP

        // leafA: covers tile-ids [0,1000); holds the single z0 tile (id 0).
        val leafA = PmtilesDirectory.maybeCompress(
            PmtilesDirectory.serialize(
                listOf(
                    DirectoryEntry(
                        tileId = 0L,
                        runLength = 1L,
                        length = LEAF_ARCHIVE_TILE_LEN,
                        offset = 0L,
                    )
                )
            ),
            comp,
        )
        // leafB: covers tile-ids [1000,MAX); a lone high-zoom tile we never request.
        val leafB = PmtilesDirectory.maybeCompress(
            PmtilesDirectory.serialize(
                listOf(
                    DirectoryEntry(
                        tileId = 1000L,
                        runLength = 1L,
                        length = 50L,
                        offset = LEAF_ARCHIVE_TILE_LEN,
                    )
                )
            ),
            comp,
        )

        // root: two leaf pointers (runLength==0). offset is relative to
        // leafDirsOffset; lengths are the on-disk (compressed) leaf sizes.
        val rootRaw = PmtilesDirectory.serialize(
            listOf(
                DirectoryEntry(tileId = 0L, runLength = 0L, length = leafA.size.toLong(), offset = 0L),
                DirectoryEntry(
                    tileId = 1000L,
                    runLength = 0L,
                    length = leafB.size.toLong(),
                    offset = leafA.size.toLong(),
                ),
            )
        )
        val rootComp = PmtilesDirectory.maybeCompress(rootRaw, comp)

        val metadata = PmtilesDirectory.maybeCompress("{}".toByteArray(), comp)

        val headerBytes = PmtilesV3.HEADER_BYTES.toLong()
        val rootDirOffset = headerBytes
        val rootDirBytes = rootComp.size.toLong()
        val metaOffset = rootDirOffset + rootDirBytes
        val metaBytes = metadata.size.toLong()
        val leafOffset = metaOffset + metaBytes
        val leafBytes = (leafA.size + leafB.size).toLong()
        val tileDataOffset = leafOffset + leafBytes
        // A couple of placeholder tile-data bytes — tilesInRegion never reads them.
        val tileDataBytes = LEAF_ARCHIVE_TILE_LEN + 50L

        val header = PmtilesHeader(
            version = PmtilesV3.VERSION,
            rootDirOffset = rootDirOffset,
            rootDirBytes = rootDirBytes,
            jsonMetadataOffset = metaOffset,
            jsonMetadataBytes = metaBytes,
            leafDirsOffset = leafOffset,
            leafDirsBytes = leafBytes,
            tileDataOffset = tileDataOffset,
            tileDataBytes = tileDataBytes,
            addressedTilesCount = 2L,
            tileEntriesCount = 2L,
            tileContentsCount = 2L,
            clustered = 1,
            internalCompression = comp,
            tileCompression = PmtilesV3.COMPRESSION_NONE,
            tileType = PmtilesV3.TYPE_MVT,
            minZoom = 0,
            maxZoom = 14,
            minLonE7 = -1_800_000_000,
            minLatE7 = -850_000_000,
            maxLonE7 = 1_800_000_000,
            maxLatE7 = 850_000_000,
            centerZoom = 0,
            centerLonE7 = 0,
            centerLatE7 = 0,
        )

        val file = File.createTempFile("leaf-archive-", ".pmtiles")
        java.io.RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(tileDataOffset + tileDataBytes)
            raf.write(header.toByteArray())
            raf.write(rootComp)
            raf.write(metadata)
            raf.write(leafA)
            raf.write(leafB)
            // tile-data region left zero-filled; never read by tilesInRegion.
        }
        return LeafArchive(file, tileDataOffset)
    }

    private companion object {
        private const val LEAF_ARCHIVE_TILE_LEN = 100L
    }
}
