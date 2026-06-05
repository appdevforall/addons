package org.appdevforall.maps.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [formatByteSize]. The Region Manager cache footer and
 * each region-row size label flow through this helper; a mis-formatted
 * "0.0 MB" for a small-but-nonzero file was a real bug, so the unit-cap
 * behaviour is load-bearing.
 */
class ByteSizeTest {

    @Test
    fun smallByteCountsUseBytes() {
        assertEquals("0 B", formatByteSize(0))
        assertEquals("8 B", formatByteSize(8))
        assertEquals("1023 B", formatByteSize(1023))
    }

    @Test
    fun kilobyteRange() {
        assertEquals("1.0 KB", formatByteSize(1024))
        assertEquals("9.8 KB", formatByteSize(10_000))
        // Just under 1 MB.
        assertEquals("1023.9 KB", formatByteSize(1024L * 1024L - 100))
    }

    @Test
    fun megabyteRange() {
        assertEquals("1.0 MB", formatByteSize(1024L * 1024L))
        assertEquals("12.4 MB", formatByteSize(13_000_000))
        // Just under 1 GB.
        assertEquals("1023.9 MB", formatByteSize(1024L * 1024L * 1024L - 100_000))
    }

    @Test
    fun gigabyteRange() {
        assertEquals("1.00 GB", formatByteSize(1024L * 1024L * 1024L))
        assertEquals("12.40 GB", formatByteSize(13_314_572_288L))
    }

    @Test
    fun b8RegressionStubsDoNotRoundToZeroMb() {
        // The B8 symptom: a 6-byte stub being labelled "0.0 MB". This
        // helper must surface the actual byte count instead.
        val stubBytes = 6L
        assertEquals("6 B", formatByteSize(stubBytes))
    }
}
