package org.appdevforall.maps.slicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the coalesce + split helpers that drive the parallel
 * region-download path. Keep these pure-Kotlin so they run without a JVM
 * test fixture and stay fast.
 *
 * Each blob is `(offset, length)`. Tests assert that:
 *   1. The coalesced chunks cover exactly the same byte ranges as the input
 *      blobs (no over-fetching beyond the requested gap budget).
 *   2. Every input blob still appears exactly once in some chunk's `blobs`.
 *   3. Chunks honor the `maxBytes` cap after splitting.
 */
class PmtilesRegionSlicerCoalesceTest {

    @Test
    fun coalesceBlobs_empty_input_returns_empty() {
        assertEquals(emptyList<PmtilesRegionSlicer.FetchChunk>(),
            PmtilesRegionSlicer.coalesceBlobs(emptyList(), 64))
    }

    @Test
    fun coalesceBlobs_single_blob_returns_single_chunk() {
        val out = PmtilesRegionSlicer.coalesceBlobs(listOf(100L to 50L), 64)
        assertEquals(1, out.size)
        assertEquals(100L, out[0].offset)
        assertEquals(50L, out[0].length)
        assertEquals(listOf(100L to 50L), out[0].blobs)
    }

    @Test
    fun coalesceBlobs_adjacent_blobs_with_zero_gap_merge() {
        // [0..100) [100..200) — zero gap, must merge into [0..200) holding both.
        val out = PmtilesRegionSlicer.coalesceBlobs(
            listOf(0L to 100L, 100L to 100L),
            64,
        )
        assertEquals(1, out.size)
        assertEquals(0L, out[0].offset)
        assertEquals(200L, out[0].length)
        assertEquals(2, out[0].blobs.size)
    }

    @Test
    fun coalesceBlobs_small_gap_within_threshold_merges() {
        // [0..100) [150..250) — gap=50 ≤ maxGap=64, must merge into [0..250).
        val out = PmtilesRegionSlicer.coalesceBlobs(
            listOf(0L to 100L, 150L to 100L),
            64,
        )
        assertEquals(1, out.size)
        assertEquals(0L, out[0].offset)
        assertEquals(250L, out[0].length)
        assertEquals(2, out[0].blobs.size)
    }

    @Test
    fun coalesceBlobs_gap_above_threshold_splits() {
        // [0..100) [200..300) — gap=100 > maxGap=64, must produce 2 chunks.
        val out = PmtilesRegionSlicer.coalesceBlobs(
            listOf(0L to 100L, 200L to 100L),
            64,
        )
        assertEquals(2, out.size)
        assertEquals(0L, out[0].offset)
        assertEquals(100L, out[0].length)
        assertEquals(200L, out[1].offset)
        assertEquals(100L, out[1].length)
    }

    @Test
    fun coalesceBlobs_real_world_clustered_pattern_merges_aggressively() {
        // A typical PMTiles cluster: ~20 tiles, each ~5 KB, packed back-to-back
        // with occasional 1-2 KB gaps from dedup'd shared blobs.
        val blobs = buildList {
            var off = 1_000_000L
            repeat(20) {
                add(off to 5000L)
                off += 5000L + 1000L  // 1 KB gap between each
            }
        }
        val out = PmtilesRegionSlicer.coalesceBlobs(blobs, 64L * 1024L)
        // Gap (1 KB) well below 64 KB threshold — all should merge.
        assertEquals("all 20 blobs should merge into a single chunk", 1, out.size)
        assertEquals(20, out[0].blobs.size)
    }

    @Test
    fun coalesceBlobs_preserves_total_blob_byte_count() {
        val blobs = listOf(
            10L to 100L, 200L to 50L,  // gap=90, merges (≤64? no)
            10_000L to 200L, 10_300L to 100L,  // gap=100, merges? no with 64KB
        )
        // With maxGap=200: first two merge (gap=90), third+fourth merge (gap=100),
        //   middle gap between groups = 9750, won't merge.
        val out = PmtilesRegionSlicer.coalesceBlobs(blobs, 200L)
        val totalBlobBytes = blobs.sumOf { it.second }
        val totalOutBytes = out.sumOf { chunk -> chunk.blobs.sumOf { it.second } }
        assertEquals("blob-byte sum preserved across coalescing", totalBlobBytes, totalOutBytes)
        val totalOutBlobs = out.sumOf { it.blobs.size }
        assertEquals("each blob appears exactly once", blobs.size, totalOutBlobs)
    }

    @Test
    fun splitOversizedChunk_no_op_when_within_bound() {
        val chunk = PmtilesRegionSlicer.FetchChunk(0L, 1000L, listOf(0L to 1000L))
        val out = PmtilesRegionSlicer.splitOversizedChunk(chunk, 4096L)
        assertEquals(1, out.size)
        assertEquals(chunk, out[0])
    }

    @Test
    fun splitOversizedChunk_splits_large_chunk_into_bounded_parts() {
        // 10 blobs of 1 MB each, packed back-to-back. Total = 10 MB.
        // maxBytes = 4 MB → must produce ≥3 parts, each ≤ 4 MB.
        val blobs = (0 until 10).map { (it * 1_000_000L) to 1_000_000L }
        val chunk = PmtilesRegionSlicer.FetchChunk(0L, 10_000_000L, blobs)
        val parts = PmtilesRegionSlicer.splitOversizedChunk(chunk, 4_000_000L)
        assertTrue("at least 3 parts for 10MB/4MB cap", parts.size >= 3)
        for (p in parts) {
            assertTrue("part length ${p.length} must be ≤ maxBytes", p.length <= 4_000_000L)
        }
        // Every blob should appear in exactly one part.
        val allBlobsInParts = parts.flatMap { it.blobs }
        assertEquals(blobs.size, allBlobsInParts.size)
        assertEquals(blobs.toSet(), allBlobsInParts.toSet())
    }

    @Test
    fun splitOversizedChunk_keeps_oversized_single_blob_intact() {
        // One 10 MB blob (atypical but possible). Must NOT be silently dropped
        // even though it exceeds maxBytes — return it as its own part.
        val blob = 0L to 10_000_000L
        val chunk = PmtilesRegionSlicer.FetchChunk(0L, 10_000_000L, listOf(blob))
        val parts = PmtilesRegionSlicer.splitOversizedChunk(chunk, 4_000_000L)
        assertEquals(1, parts.size)
        assertEquals(blob, parts[0].blobs[0])
    }
}
