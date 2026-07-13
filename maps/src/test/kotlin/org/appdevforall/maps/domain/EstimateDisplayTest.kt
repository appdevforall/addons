package org.appdevforall.maps.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exhaustive tests for the estimate-line decision logic extracted from BboxPickerFragment. */
class EstimateDisplayTest {

    private val allowance = 5L * 1024 * 1024 // 5 MB non-vector headroom
    private val cap = 1024L * 1024 * 1024 // 1 GB

    private fun compute(realBytes: Long?, failed: Boolean = false, hasSource: Boolean = true) =
        EstimateDisplay.compute(realBytes, failed, hasSource, allowance, cap)

    @Test
    fun `no source - blank line, Next disabled`() {
        val s = compute(realBytes = 123L, hasSource = false)
        assertEquals("", s.text)
        assertFalse(s.isError)
        assertFalse(s.overBudget)
        assertFalse(s.nextEnabled)
    }

    @Test
    fun `calculating - allows Next`() {
        val s = compute(realBytes = null, failed = false)
        assertEquals("Calculating download size…", s.text)
        assertFalse(s.isError)
        assertTrue(s.nextEnabled)
    }

    @Test
    fun `failed - error text, Next disabled`() {
        val s = compute(realBytes = null, failed = true)
        assertEquals("Couldn't calculate size — check connection", s.text)
        assertTrue(s.isError)
        assertFalse(s.nextEnabled)
    }

    @Test
    fun `under cap - shows MB and enables Next`() {
        val tenMb = 10L * 1024 * 1024
        val s = compute(realBytes = tenMb)
        assertEquals("10.0 MB download size", s.text)
        assertFalse(s.isError)
        assertFalse(s.overBudget)
        assertTrue(s.nextEnabled)
    }

    @Test
    fun `over cap including allowance - over-budget, Next disabled`() {
        // 1020 MB vector + 5 MB allowance = 1025 MB > 1 GB (1024 MB)
        val s = compute(realBytes = 1020L * 1024 * 1024)
        assertTrue(s.text.contains("over 1 GB limit"))
        assertTrue(s.isError)
        assertTrue(s.overBudget)
        assertFalse(s.nextEnabled)
    }

    @Test
    fun `the non-vector allowance is what pushes a near-cap region over`() {
        // 1022 MB vector alone is under 1 GB, but +5 MB allowance crosses it.
        val s = compute(realBytes = 1022L * 1024 * 1024)
        assertTrue(s.overBudget)
        assertFalse(s.nextEnabled)
    }
}
