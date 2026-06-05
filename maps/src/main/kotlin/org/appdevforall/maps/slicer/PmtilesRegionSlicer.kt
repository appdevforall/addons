package org.appdevforall.maps.slicer

import org.appdevforall.maps.domain.Bbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.davidmoten.hilbert.HilbertCurve
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * PMTiles v3 region slicer — single source of truth for "which tiles cover a
 * bbox", shared by the size estimator and the downloader.
 *
 * The IIAB exposes one giant offset-addressable PMTiles file; this produces a
 * single per-region file with the same structure but only the region's data.
 *
 * The shared function is [tilesInRegion]. It:
 *  1. Range-fetches the 127-byte v3 header from `globalPmtilesUrl`.
 *  2. Range-fetches the root directory.
 *  3. Recursively expands any leaf directories whose tile-id range overlaps the
 *     bbox's [zoomMin..zoomMax] tile-id ranges.
 *  4. Filters directory entries to those whose (z, x, y) intersects the bbox.
 *  5. Returns a flat list of [TileEntry], byte offsets absolute in the global file.
 *
 * **No tile bytes are fetched by `tilesInRegion`** — only header + directories.
 * That keeps the size-estimate path cheap (sub-megabyte network) and lets the
 * downloader reuse the same `List<TileEntry>`.
 */
internal object PmtilesRegionSlicer {

    /**
     * Single source of truth: tiles inside [bbox] across [zoomMin]..[zoomMax].
     * Reads PMTiles v3 header + directories via HTTP Range; does **not**
     * fetch tile bytes.
     */
    suspend fun tilesInRegion(
        globalPmtilesUrl: String,
        bbox: Bbox,
        zoomMin: Int,
        zoomMax: Int,
        client: OkHttpClient = RangeFetcher.defaultClient(),
    ): Result<List<TileEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            RangeFetcher.forUrl(globalPmtilesUrl, client).use { fetcher ->
                tilesInRegionImpl(fetcher, bbox, zoomMin, zoomMax)
            }
        }
    }

    /**
     * Variant that operates on an open [RangeFetcher] — useful when the caller
     * has already opened one for `downloadAndSlice` and wants to avoid a
     * re-connection.
     */
    internal suspend fun tilesInRegionImpl(
        fetcher: RangeFetcher,
        bbox: Bbox,
        zoomMin: Int,
        zoomMax: Int,
    ): List<TileEntry> {
        require(zoomMin in 0..20)
        require(zoomMax in zoomMin..20)
        // 1. Header
        coroutineContext.ensureActive()
        val headerBytes = fetcher.readRange(0L, PmtilesV3.HEADER_BYTES)
        val header = PmtilesHeader.parse(
            ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        )

        // The user might pick zooms outside what the archive carries — clip.
        val zMin = max(zoomMin, header.minZoom.toInt())
        val zMax = min(zoomMax, header.maxZoom.toInt())
        if (zMin > zMax) return emptyList()

        // 2. Root directory
        coroutineContext.ensureActive()
        val rootRaw = fetcher.readRange(header.rootDirOffset, header.rootDirBytes.toInt())
        val rootEntries = PmtilesDirectory.parseCompressed(rootRaw, header.internalCompression)

        // 3. Compute the tile-id ranges we care about, per zoom level. A leaf
        //    directory whose entry range overlaps any of these has tiles we need;
        //    otherwise we skip it (and its bytes).
        //
        //    Uses davidmoten/hilbert-curve's SmallHilbertCurve.query() —
        //    perimeter-walk range decomposition (Lawder & King family) — to get
        //    tight ranges, which means fewer false-positive leaf fetches over HTTP
        //    than a single loose [lo, hi] per zoom. Same Hilbert ordering as our
        //    Hilbert function (verified by DavidmotenHilbertCompatTest).
        val zoomToIdRanges: Map<Int, List<LongRange>> = (zMin..zMax).associateWith { z ->
            tileIdRangesForBbox(bbox, z)
        }

        // 4. Walk root, recursing into leaves on overlap, collecting tiles. Pass
        //    ONE semaphore through the whole recursion so total in-flight HTTP
        //    fetches stay bounded at [PARALLEL_FETCHES]. Per-call semaphores would
        //    multiply at each recursion level (6^N at depth N — trees go 2-3 deep),
        //    filling the heap with pending Deferreds + leaf ByteArrays.
        val tiles = mutableListOf<TileEntry>()
        val sem = Semaphore(PARALLEL_FETCHES)
        walkEntries(
            entries = rootEntries,
            zoomToIdRanges = zoomToIdRanges,
            header = header,
            fetcher = fetcher,
            bbox = bbox,
            out = tiles,
            sem = sem,
        )
        return tiles
    }

    private suspend fun walkEntries(
        entries: List<DirectoryEntry>,
        zoomToIdRanges: Map<Int, List<LongRange>>,
        header: PmtilesHeader,
        fetcher: RangeFetcher,
        bbox: Bbox,
        out: MutableList<TileEntry>,
        sem: Semaphore,
    ) {
        // For each entry: if it's a leaf pointer (runLength==0), the leaf directory
        // describes the tile-id range [e.tileId, nextSiblingTileId). Leaves are
        // fetched in parallel (sequential routinely took minutes on cold-cache
        // global archives); non-leaf tile entries are processed inline (CPU-cheap).
        val sorted = entries.sortedBy { it.tileId }
        data class LeafToFetch(val pointer: DirectoryEntry, val nextId: Long)
        val leavesToFetch = mutableListOf<LeafToFetch>()
        for (i in sorted.indices) {
            coroutineContext.ensureActive()
            val e = sorted[i]
            val nextId = if (i + 1 < sorted.size) sorted[i + 1].tileId else Long.MAX_VALUE
            if (e.isLeafPointer()) {
                val leafIdRange = e.tileId until nextId
                val anyOverlap = zoomToIdRanges.values.any { ranges ->
                    ranges.any { it.overlaps(leafIdRange) }
                }
                if (!anyOverlap) continue
                leavesToFetch += LeafToFetch(e, nextId)
            } else {
                // Not a leaf pointer, so runLength != 0 (isLeafPointer() IS
                // runLength == 0L) — use it directly as the run count.
                val runLen = e.runLength
                for (k in 0 until runLen) {
                    val tid = e.tileId + k
                    val (z, x, y) = Hilbert.tileIdToZxy(tid)
                    val ranges = zoomToIdRanges[z] ?: continue
                    if (ranges.none { tid in it }) continue
                    if (!tileIntersectsBbox(z, x, y, bbox)) continue
                    out += TileEntry(
                        z = z,
                        x = x,
                        y = y,
                        tileId = tid,
                        byteOffset = header.tileDataOffset + e.offset,
                        byteLength = e.length,
                    )
                }
            }
        }

        if (leavesToFetch.isEmpty()) return

        // Fan out leaf fetches under the shared [sem] passed down from
        // [tilesInRegionImpl]. Each leaf is a small range request (few KB)
        // followed by a recursive walk; recursive walks reuse the SAME
        // semaphore so total in-flight HTTP fetches stay bounded across
        // the whole tree.
        coroutineScope {
            val perLeafResults = leavesToFetch.map { leaf ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        coroutineContext.ensureActive()
                        val leafBytes = fetcher.readRange(
                            header.leafDirsOffset + leaf.pointer.offset,
                            leaf.pointer.length.toInt(),
                        )
                        val leafEntries = PmtilesDirectory.parseCompressed(
                            leafBytes, header.internalCompression
                        )
                        // Each leaf walks into its own private list so we can
                        // merge in a fixed order at the end, without locking
                        // `out` per-tile. The merge is bounded by total tiles
                        // matched, which is what we want anyway.
                        val localOut = mutableListOf<TileEntry>()
                        walkEntries(leafEntries, zoomToIdRanges, header, fetcher, bbox, localOut, sem)
                        localOut
                    }
                }
            }.awaitAll()
            for (local in perLeafResults) out += local
        }
    }

    /**
     * Sum of `byteLength` across [tiles] = approximate sliced archive size
     * (just the tile-data section; header + dir overhead is ~1-10 KB and
     * negligible relative to tile MBs).
     *
     * Note: this **double-counts** tiles that share a content blob in the
     * global archive. The true sliced archive size will be slightly smaller
     * because dedup is preserved. For the wizard's "show MB estimate" use
     * case this overestimate is acceptable and conservative (we won't
     * surprise the user with a *bigger* download than estimated).
     */
    fun estimateRegionBytes(tiles: List<TileEntry>): Long {
        return tiles.sumOf { it.byteLength } + ESTIMATE_OVERHEAD_BYTES
    }

    /**
     * Header + directory overhead estimate. 127 bytes header + ~5 KB worst-case
     * gzipped root directory. Rounded up for safety.
     */
    private const val ESTIMATE_OVERHEAD_BYTES = 8L * 1024L

    // ----- Fetch coalescing + parallelism knobs -----
    //
    // Tuned for IIAB over typical home wifi (10-50 Mbps) and LAN (100+ Mbps).
    // Internet RTT to iiab.switnet.org runs ~80-150ms; LAN RTT ~5-20ms.
    //
    // At "few MB/s" target throughput, a 64KB gap takes ~10-20ms to download
    // vs. ~80-150ms RTT to make an extra request — so coalescing any gap
    // below ~512KB is profitable on internet, ~50KB on LAN. 64KB is a safe
    // mid-point that wins on both.

    /** Max byte gap between adjacent blobs that we merge into one fetch. */
    internal const val COALESCE_GAP_BYTES: Long = 64L * 1024L

    /** Max bytes per single HTTP range request. Caps memory + lets a single
     *  huge cluster (e.g., dense city at z14) still benefit from parallelism. */
    internal const val MAX_CHUNK_BYTES: Long = 4L * 1024L * 1024L

    /** Concurrent in-flight HTTP range requests during a download. */
    internal const val PARALLEL_FETCHES = 6

    /**
     * A contiguous fetch — covers one or more original blobs whose byte ranges
     * are within [COALESCE_GAP_BYTES] of each other.
     */
    internal data class FetchChunk(
        val offset: Long,
        val length: Long,
        val blobs: List<Pair<Long, Long>>,
    )

    /**
     * Coalesce a list of unique blobs (sorted by [Pair.first] = byte offset)
     * into fewer fetch chunks: any two blobs whose gap is ≤ [maxGap] bytes
     * get merged into the same chunk. Returns chunks in ascending byte order.
     */
    internal fun coalesceBlobs(
        sortedBlobs: List<Pair<Long, Long>>,
        maxGap: Long,
    ): List<FetchChunk> {
        if (sortedBlobs.isEmpty()) return emptyList()
        val out = mutableListOf<FetchChunk>()
        var startOff = sortedBlobs[0].first
        var endOff = startOff + sortedBlobs[0].second
        val running = mutableListOf(sortedBlobs[0])
        for (i in 1 until sortedBlobs.size) {
            val (o, len) = sortedBlobs[i]
            val gap = o - endOff
            if (gap <= maxGap) {
                running += sortedBlobs[i]
                endOff = maxOf(endOff, o + len)
            } else {
                out += FetchChunk(startOff, endOff - startOff, running.toList())
                running.clear()
                running += sortedBlobs[i]
                startOff = o
                endOff = o + len
            }
        }
        out += FetchChunk(startOff, endOff - startOff, running.toList())
        return out
    }

    /**
     * Split a [chunk] whose length exceeds [maxBytes] into multiple chunks of
     * ≤ [maxBytes] each, distributing the contained blobs by their offset.
     * No-op for chunks already within the bound.
     */
    internal fun splitOversizedChunk(
        chunk: FetchChunk,
        maxBytes: Long,
    ): List<FetchChunk> {
        if (chunk.length <= maxBytes) return listOf(chunk)
        val parts = mutableListOf<FetchChunk>()
        var currentStart = chunk.offset
        val sortedBlobs = chunk.blobs.sortedBy { it.first }
        var idx = 0
        while (idx < sortedBlobs.size) {
            val budgetEnd = currentStart + maxBytes
            val partBlobs = mutableListOf<Pair<Long, Long>>()
            var partEnd = currentStart
            while (idx < sortedBlobs.size) {
                val (o, len) = sortedBlobs[idx]
                val blobEnd = o + len
                // If adding this blob would push the part beyond budgetEnd
                // AND the part is non-empty, close out the part. If a single
                // blob exceeds maxBytes by itself, take it anyway (one giant
                // tile — atypical but possible).
                if (partBlobs.isNotEmpty() && blobEnd - currentStart > maxBytes) break
                partBlobs += sortedBlobs[idx]
                partEnd = maxOf(partEnd, blobEnd)
                idx++
            }
            parts += FetchChunk(currentStart, partEnd - currentStart, partBlobs.toList())
            currentStart = partEnd
        }
        return parts
    }

    /**
     * Fetch every tile in [tiles] from [globalPmtilesUrl] and write a sliced
     * v3 PMTiles archive to [targetFile].
     *
     * Tile content dedup is preserved: tiles sharing `(byteOffset, byteLength)`
     * in the source archive get a single content blob in the output, with
     * multiple directory entries pointing at it.
     *
     * [onProgress] reports downloaded vs total **bytes of unique tile content**
     * (so it tracks the actual network spend, not the inflated double-counted
     * estimate).
     */
    suspend fun downloadAndSlice(
        tiles: List<TileEntry>,
        globalPmtilesUrl: String,
        sourceHeader: PmtilesHeader,
        bbox: Bbox,
        zoomMin: Int,
        zoomMax: Int,
        targetFile: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        client: OkHttpClient = RangeFetcher.defaultClient(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(tiles.isNotEmpty()) { "no tiles to slice — empty bbox/zoom intersection?" }
            // Group entries by (offset, length): each unique blob becomes one
            // entry in the sliced archive. Preserve insertion order; multiple
            // tile_ids will be grouped into a directory run later.
            val blobByKey = linkedMapOf<Pair<Long, Long>, MutableList<TileEntry>>()
            for (t in tiles) {
                val key = t.byteOffset to t.byteLength
                blobByKey.getOrPut(key) { mutableListOf() } += t
            }

            val totalBytes = blobByKey.keys.sumOf { it.second }
            onProgress(0L, totalBytes)

            // Build the sliced directory entries in tile-id order. For each
            // blob: one entry per tile_id, but if a blob's tile_ids are
            // contiguous on the Hilbert curve, collapse into one run.
            val orderedTiles = tiles.sortedBy { it.tileId }
            val outEntries = mutableListOf<DirectoryEntry>()
            val blobLocalOffset = mutableMapOf<Pair<Long, Long>, Long>() // key -> offset in sliced tile-data
            var nextLocalOffset = 0L

            // First pass: assign local offsets in the same order tile-ids appear.
            for (t in orderedTiles) {
                val key = t.byteOffset to t.byteLength
                if (key !in blobLocalOffset) {
                    blobLocalOffset[key] = nextLocalOffset
                    nextLocalOffset += t.byteLength
                }
            }

            // Second pass: build directory entries with run-length compression.
            run {
                var i = 0
                while (i < orderedTiles.size) {
                    val t = orderedTiles[i]
                    val key = t.byteOffset to t.byteLength
                    var runEnd = i
                    // Extend run as long as: contiguous tile_id and same key.
                    while (runEnd + 1 < orderedTiles.size &&
                        orderedTiles[runEnd + 1].tileId == orderedTiles[runEnd].tileId + 1 &&
                        (orderedTiles[runEnd + 1].byteOffset to orderedTiles[runEnd + 1].byteLength) == key
                    ) {
                        runEnd++
                    }
                    val runLen = (runEnd - i + 1).toLong()
                    outEntries += DirectoryEntry(
                        tileId = t.tileId,
                        runLength = runLen,
                        length = t.byteLength,
                        offset = blobLocalOffset[key]!!,
                    )
                    i = runEnd + 1
                }
            }

            // Serialize directory.
            val dirRaw = PmtilesDirectory.serialize(outEntries)
            val dirCompressed = PmtilesDirectory.maybeCompress(dirRaw, sourceHeader.internalCompression)

            // Compute the new header offsets:
            //   [0..127)        header
            //   [127..rd_end)   root directory (we use no leaves — small slices fit in root)
            //   [rd_end..meta)  metadata (copied from source — MapLibre needs
            //                   `vector_layers` etc. to render anything)
            //   [meta..td)      empty leaf-dirs section
            //   [td..end)       tile data
            //
            // The metadata block is copied verbatim from the source. MapLibre reads
            // `vector_layers` from it to learn which source-layers exist (water,
            // transportation, place, …) and their per-layer min/max zooms. Without
            // it, every layer referencing a `source-layer` silently has no data —
            // the user sees the basemap with an empty overlay.
            //
            // One-shot fetcher for this tiny read; the main parallel fetcher below
            // opens its own scope.
            val metadataCompressed = RangeFetcher.forUrl(globalPmtilesUrl, client).use { metaFetcher ->
                metaFetcher.readRange(
                    sourceHeader.jsonMetadataOffset,
                    sourceHeader.jsonMetadataBytes.toInt(),
                )
            }
            val headerEnd = PmtilesV3.HEADER_BYTES.toLong()
            val rootDirOffset = headerEnd
            val rootDirBytes = dirCompressed.size.toLong()
            val metaOffset = rootDirOffset + rootDirBytes
            val metaBytes = metadataCompressed.size.toLong()
            val leafOffset = metaOffset + metaBytes
            val leafBytes = 0L
            val tileDataOffset = leafOffset + leafBytes
            val tileDataBytes = nextLocalOffset

            // Compute new bbox from the sliced tiles' coverage.
            val tileMinLon = tiles.minOf { tileToLon(it.x, it.z) }
            val tileMaxLon = tiles.maxOf { tileToLon(it.x + 1, it.z) }
            val tileMaxLat = tiles.maxOf { tileToLat(it.y, it.z) }
            val tileMinLat = tiles.minOf { tileToLat(it.y + 1, it.z) }

            val newHeader = sourceHeader.copy(
                rootDirOffset = rootDirOffset,
                rootDirBytes = rootDirBytes,
                jsonMetadataOffset = metaOffset,
                jsonMetadataBytes = metaBytes,
                leafDirsOffset = leafOffset,
                leafDirsBytes = leafBytes,
                tileDataOffset = tileDataOffset,
                tileDataBytes = tileDataBytes,
                addressedTilesCount = orderedTiles.size.toLong(),
                tileEntriesCount = outEntries.size.toLong(),
                tileContentsCount = blobLocalOffset.size.toLong(),
                clustered = 1,
                minZoom = zoomMin.toByte().coerceAtLeast(sourceHeader.minZoom),
                maxZoom = zoomMax.toByte().coerceAtMost(sourceHeader.maxZoom),
                minLonE7 = (tileMinLon * 1e7).toInt(),
                minLatE7 = (tileMinLat * 1e7).toInt(),
                maxLonE7 = (tileMaxLon * 1e7).toInt(),
                maxLatE7 = (tileMaxLat * 1e7).toInt(),
                centerZoom = ((zoomMin + zoomMax) / 2).toByte(),
                centerLonE7 = (((tileMinLon + tileMaxLon) / 2.0) * 1e7).toInt(),
                centerLatE7 = (((tileMinLat + tileMaxLat) / 2.0) * 1e7).toInt(),
            )

            // Write to a .partial then atomic move.
            val tmp = File(targetFile.parentFile, targetFile.name + ".partial")
            if (tmp.exists()) tmp.delete()

            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(tileDataOffset + tileDataBytes)
                raf.write(newHeader.toByteArray())
                raf.write(dirCompressed)
                raf.write(metadataCompressed)
                // leaf section empty — nothing to write

                // Fetch + write each unique blob.
                //
                // PMTiles tile data is Hilbert-clustered, so the unique blobs
                // for a region cluster tightly in byte space — adjacent blobs
                // are typically a few bytes apart. Doing one HTTP range
                // request per blob pays a full RTT (~100ms on the public
                // internet, ~10-20ms LAN) for what's often only a few KB of
                // data — that's what cratered throughput on the original
                // implementation.
                //
                // Two optimizations here:
                //
                //  1. Coalesce adjacent blobs (gap ≤ COALESCE_GAP_BYTES) into
                //     a single "fetch chunk." A blob's gap-cost-equivalent
                //     download time is roughly the per-request RTT, so any
                //     gap smaller than that breaks even when fetched as part
                //     of a larger request.
                //
                //  2. Parallel-fetch chunks via a Semaphore-capped pool. The
                //     OkHttp dispatcher's maxRequestsPerHost (raised in
                //     RegionDownloader) caps the real concurrency; we use a
                //     coroutine Semaphore to bound async dispatch as well so
                //     the heap doesn't fill with pending Deferreds.
                //
                // Chunks larger than MAX_CHUNK_BYTES are split to bound
                // per-fetch memory and let a single huge cluster still
                // benefit from parallelism.
                val orderedBlobs = blobLocalOffset.keys.sortedBy { it.first }
                val chunks = coalesceBlobs(orderedBlobs, COALESCE_GAP_BYTES)
                    .flatMap { splitOversizedChunk(it, MAX_CHUNK_BYTES) }
                val downloaded = AtomicLong(0L)
                RangeFetcher.forUrl(globalPmtilesUrl, client).use { fetcher ->
                    coroutineScope {
                        val sem = Semaphore(PARALLEL_FETCHES)
                        chunks.map { chunk ->
                            async(Dispatchers.IO) {
                                sem.withPermit {
                                    coroutineContext.ensureActive()
                                    val data = fetcher.readRange(
                                        chunk.offset,
                                        chunk.length.toInt(),
                                    )
                                    // Write each contained blob to its position
                                    // in the sliced tile-data section. The
                                    // RandomAccessFile isn't thread-safe across
                                    // seek+write, so synchronize on the raf
                                    // for the actual disk write. Time spent
                                    // here is microseconds per chunk; the
                                    // bottleneck is the network.
                                    synchronized(raf) {
                                        for (blob in chunk.blobs) {
                                            val (blobOff, blobLen) = blob
                                            val srcOff = (blobOff - chunk.offset).toInt()
                                            val localOff = blobLocalOffset[blob]!!
                                            raf.seek(tileDataOffset + localOff)
                                            raf.write(
                                                data,
                                                srcOff,
                                                blobLen.toInt(),
                                            )
                                        }
                                    }
                                    val chunkBlobBytes = chunk.blobs.sumOf { it.second }
                                    val newDownloaded = downloaded.addAndGet(chunkBlobBytes)
                                    onProgress(newDownloaded, totalBytes)
                                }
                            }
                        }.awaitAll()
                    }
                }
            }
            // Atomic move into place.
            if (targetFile.exists()) targetFile.delete()
            require(tmp.renameTo(targetFile)) {
                "Couldn't rename ${tmp.absolutePath} → ${targetFile.absolutePath}"
            }
        }
    }

    // ---------- Geo helpers ----------

    /**
     * The set of Hilbert tile-id ranges that exactly covers every (z, x, y)
     * tile inside [bbox] at zoom [z], expressed as a list of merged
     * `[lo, hi]` ranges (PMTiles tile-id space, i.e. including the zoom-base
     * accumulator).
     *
     * Backed by davidmoten/hilbert-curve's [SmallHilbertCurve.query], which
     * implements the Skilling 2004 perimeter-walk range-decomposition
     * algorithm (Lawder & King family). Returns `O(perimeter × log scale)`
     * tight ranges instead of one loose `O(area)` range — meaning the slicer's
     * leaf-overlap pre-filter actually filters, instead of marking ~30% of
     * leaves as "potentially overlapping" for a 20° bbox.
     *
     * Verified to use the SAME Hilbert ordering as our [Hilbert.zxyToTileId]
     * via DavidmotenHilbertCompatTest — so the returned ranges, after
     * adding the zoom base accumulator, are exactly leaf-id-comparable.
     */
    internal fun tileIdRangesForBbox(bbox: Bbox, z: Int): List<LongRange> {
        val n = 1 shl z
        val xMin = lonToTileX(bbox.west, z).coerceIn(0, n - 1)
        val xMax = lonToTileX(bbox.east, z).coerceIn(0, n - 1)
        val yMin = latToTileY(bbox.north, z).coerceIn(0, n - 1)
        val yMax = latToTileY(bbox.south, z).coerceIn(0, n - 1)
        if (xMin > xMax || yMin > yMax) return emptyList()

        // davidmoten's bits-per-dimension equals z (each dim takes z bits
        // in a 2^z grid). 2D curve.
        val curve = HilbertCurve.small().bits(z).dimensions(2)
        val ranges = curve.query(
            longArrayOf(xMin.toLong(), yMin.toLong()),
            longArrayOf(xMax.toLong(), yMax.toLong()),
        )
        val base = Hilbert.accumulate(z)
        // Add the zoom base so the ranges live in PMTiles global tile-id
        // space (matches the ids encoded into leaf directories).
        return ranges.toList().map { (it.low() + base)..(it.high() + base) }
    }

    private fun lonToTileX(lon: Double, z: Int): Int {
        val n = 2.0.pow(z)
        return floor((lon + 180.0) / 360.0 * n).toInt()
    }

    private fun latToTileY(lat: Double, z: Int): Int {
        val n = 2.0.pow(z)
        val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return floor((1 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n).toInt()
    }

    private fun tileToLon(x: Int, z: Int): Double {
        val n = 2.0.pow(z)
        return x / n * 360.0 - 180.0
    }

    private fun tileToLat(y: Int, z: Int): Double {
        val n = 2.0.pow(z)
        val latRad = Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / n)))
        return Math.toDegrees(latRad)
    }

    /** True iff the (z, x, y) slippy-map tile overlaps [bbox]. */
    private fun tileIntersectsBbox(z: Int, x: Int, y: Int, bbox: Bbox): Boolean {
        val tileWest = tileToLon(x, z)
        val tileEast = tileToLon(x + 1, z)
        val tileNorth = tileToLat(y, z)
        val tileSouth = tileToLat(y + 1, z)
        return tileEast > bbox.west && tileWest < bbox.east &&
            tileNorth > bbox.south && tileSouth < bbox.north
    }
}

private fun LongRange.overlaps(other: LongRange): Boolean {
    if (this == LongRange.EMPTY || other == LongRange.EMPTY) return false
    return this.first <= other.last && other.first <= this.last
}
