package org.appdevforall.maps.slicer

import org.appdevforall.maps.domain.Bbox
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TileMath.tileIntersectsBbox] disjointness arms — one test per failing
 * conjunct of `tileEast > west && tileWest < east && tileNorth > south &&
 * tileSouth < north`, plus the boundary-touch semantics (strict inequalities:
 * an edge-touching tile does NOT intersect).
 *
 * Fixture: bbox = (0°S..20°N, 0°E..20°E); z=4 tiles are 22.5° of longitude
 * wide, so x=8 spans lon [0, 22.5), y=7 spans lat (0, 21.94], etc.
 */
class TileMathDisjointBboxTest {

  private val bbox = Bbox(south = 0.0, west = 0.0, north = 20.0, east = 20.0)
  private val z = 4

  @Test
  fun overlappingTileIntersects() {
    // x=8 → lon 0..22.5, y=7 → lat 0..21.94 — clearly overlaps the box.
    assertTrue(TileMath.tileIntersectsBbox(z, x = 8, y = 7, bbox = bbox))
  }

  @Test
  fun tileEntirelyWestDoesNotIntersect() {
    // x=6 → lon -45..-22.5, east edge < bbox.west → first conjunct false.
    assertFalse(TileMath.tileIntersectsBbox(z, x = 6, y = 7, bbox = bbox))
  }

  @Test
  fun tileEntirelyEastDoesNotIntersect() {
    // x=9 → lon 22.5..45, west edge > bbox.east → second conjunct false.
    assertFalse(TileMath.tileIntersectsBbox(z, x = 9, y = 7, bbox = bbox))
  }

  @Test
  fun tileEntirelySouthDoesNotIntersect() {
    // y=9 → lat -40.98..-21.94, north edge < bbox.south → third conjunct false.
    assertFalse(TileMath.tileIntersectsBbox(z, x = 8, y = 9, bbox = bbox))
  }

  @Test
  fun tileEntirelyNorthDoesNotIntersect() {
    // y=6 → lat 21.94..40.98, south edge > bbox.north → fourth conjunct false.
    assertFalse(TileMath.tileIntersectsBbox(z, x = 8, y = 6, bbox = bbox))
  }

  @Test
  fun edgeTouchingTileDoesNotIntersect() {
    // y=8's NORTH edge is exactly lat 0 = bbox.south; the comparison is strict
    // (`tileNorth > south`), so a tile that only touches the boundary is out.
    // This is the semantics the slicer relies on to avoid double-counting
    // tiles along a shared edge.
    assertFalse(TileMath.tileIntersectsBbox(z, x = 8, y = 8, bbox = bbox))
  }
}
