package com.itsaky.androidide.plugins.aiagentlocal.format

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteSizeTest {

    @Test
    fun givenExactGibibyte_whenFormatted_thenBinaryUnits() {
        // Binary, not SI: 1 GiB must read as "1.0 GB", not "1.1 GB".
        assertEquals("1.0 GB", ByteSize.format(1L shl 30))
    }

    @Test
    fun givenMultipleGibibytes_whenFormatted_thenOneDecimal() {
        assertEquals("4.5 GB", ByteSize.format((4.5 * (1L shl 30)).toLong()))
    }

    @Test
    fun givenZero_whenFormatted_thenZeroMb() {
        // The 0-free-RAM diagnosis renders this, so it must not produce "-0.0" or an empty string.
        assertEquals("0.0 MB", ByteSize.format(0L))
    }

    @Test
    fun givenSubGigabyte_whenFormatted_thenMegabytes() {
        // Below 1 GiB the value switches to MB rather than collapsing to a useless "0.5 GB"/"0.0 GB".
        assertEquals("512.0 MB", ByteSize.format(1L shl 29))
    }

    @Test
    fun givenJustUnderOneGibibyte_whenFormatted_thenStillMegabytes() {
        // Boundary: only >= 1 GiB promotes to GB.
        assertEquals("1023.0 MB", ByteSize.format((1L shl 30) - (1L shl 20)))
    }

    @Test
    fun givenSubMegabyte_whenFormatted_thenFractionalMegabytes() {
        // A near-exhausted device: must still read as a small number, not "0.0 GB".
        assertEquals("0.5 MB", ByteSize.format(512L shl 10))
    }
}
