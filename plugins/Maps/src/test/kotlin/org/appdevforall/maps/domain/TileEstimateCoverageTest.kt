package org.appdevforall.maps.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage tests for [TileEstimate] — a pure value type. Exercises the data-class
 * fields, the [TileEstimate.sizeMb] byte→MB conversion, and the
 * [TileEstimate.displayString] formatting (tile count · MB · zoom range).
 */
class TileEstimateCoverageTest {

    @Test
    fun `fields are carried verbatim`() {
        val est = TileEstimate(
            tileCount = 1234,
            sizeBytesEstimate = 5_242_880, // 5 MB exactly
            zoomMin = 6,
            zoomMax = 14,
        )
        assertEquals(1234L, est.tileCount)
        assertEquals(5_242_880L, est.sizeBytesEstimate)
        assertEquals(6, est.zoomMin)
        assertEquals(14, est.zoomMax)
    }

    @Test
    fun `sizeMb converts bytes to mebibytes`() {
        // 5 MiB exactly.
        val est = TileEstimate(0, 5L * 1024 * 1024, 6, 14)
        assertEquals(5.0, est.sizeMb(), 1e-9)
    }

    @Test
    fun `sizeMb of zero bytes is zero`() {
        val est = TileEstimate(0, 0, 6, 14)
        assertEquals(0.0, est.sizeMb(), 1e-9)
    }

    @Test
    fun `displayString renders count MB and zoom range`() {
        // 2.5 MiB so the %.1f formatting shows a fractional value.
        val bytes = (2.5 * 1024 * 1024).toLong()
        val est = TileEstimate(42, bytes, 8, 13)
        val s = est.displayString()
        assertTrue("should include tile count: $s", s.contains("42 tiles"))
        // Locale-robust: the %.1f decimal separator varies by default locale
        // ("2.5" vs "2,5"); assert the digits + unit + the · separators instead.
        assertTrue("should include the MB value and unit: $s", s.contains("2") && s.contains("5 MB"))
        assertTrue("should include the middot separators: $s", s.contains("·"))
        assertTrue("should include zoom range: $s", s.contains("zoom 8–13"))
    }

    @Test
    fun `data class equality and copy`() {
        val a = TileEstimate(10, 100, 6, 12)
        val b = TileEstimate(10, 100, 6, 12)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val c = a.copy(tileCount = 11)
        assertEquals(11L, c.tileCount)
        assertEquals(a.sizeBytesEstimate, c.sizeBytesEstimate)
    }
}
