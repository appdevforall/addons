package org.appdevforall.maps.slicer

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Negative-argument arms of [Hilbert]'s precondition checks.
 * `HilbertCoverageTest` covers the too-LARGE side (z=27, x/y = n); these are
 * the too-SMALL side of the same `in 0..max` range checks.
 */
class HilbertNegativeArgsTest {

  @Test
  fun zxyToTileId_rejects_negative_zoom() {
    val ex = assertThrows(IllegalArgumentException::class.java) {
      Hilbert.zxyToTileId(-1, 0, 0)
    }
    assertTrue(ex.message!!.contains("out of range"))
  }

  @Test
  fun zxyToTileId_rejects_negative_x() {
    val ex = assertThrows(IllegalArgumentException::class.java) {
      Hilbert.zxyToTileId(1, -1, 0)
    }
    assertTrue(ex.message!!.contains("out of range"))
  }

  @Test
  fun zxyToTileId_rejects_negative_y() {
    val ex = assertThrows(IllegalArgumentException::class.java) {
      Hilbert.zxyToTileId(1, 0, -1)
    }
    assertTrue(ex.message!!.contains("out of range"))
  }
}
