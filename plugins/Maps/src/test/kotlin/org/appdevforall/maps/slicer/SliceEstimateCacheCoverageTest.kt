package org.appdevforall.maps.slicer

import org.appdevforall.maps.domain.Bbox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Covers [SliceEstimateCache] — the process-wide LRU of slicer estimates keyed
 * by `(sourceUrl, rounded bbox, zoom range)`. Exercises get/put round-trip, the
 * 4-decimal bbox rounding that collapses sub-tile jitter into one key, key
 * distinctness across url / zoom, and LRU eviction past the 16-entry bound.
 *
 * Pure in-memory singleton, so each test clears it in @Before / @After.
 */
class SliceEstimateCacheCoverageTest {

  @Before fun reset() = SliceEstimateCache.clear()

  @After fun tearDown() = SliceEstimateCache.clear()

  private fun entry(z: Int = 0) = TileEntry(z, 0, 0, 0L, 0L, 0L)

  @Test
  fun get_returns_null_when_absent() {
    assertNull(SliceEstimateCache.get("u", Bbox(0.0, 0.0, 1.0, 1.0), 0, 4))
  }

  @Test
  fun put_then_get_round_trips_same_list() {
    val box = Bbox(0.0, 0.0, 1.0, 1.0)
    val value = listOf(entry(1), entry(2))
    SliceEstimateCache.put("u", box, 0, 4, value)
    assertSame(value, SliceEstimateCache.get("u", box, 0, 4))
  }

  @Test
  fun bbox_rounding_collapses_sub_decimal_jitter_to_one_key() {
    val value = listOf(entry())
    // Two bboxes differing only below the 4th decimal (~1 m) must hit the same key.
    SliceEstimateCache.put("u", Bbox(10.000001, 20.000002, 30.000003, 40.000004), 0, 4, value)
    val jittered = Bbox(10.000009, 20.000008, 30.000007, 40.000006)
    assertSame(value, SliceEstimateCache.get("u", jittered, 0, 4))
  }

  @Test
  fun bbox_difference_above_rounding_is_a_distinct_key() {
    SliceEstimateCache.put("u", Bbox(10.0, 20.0, 30.0, 40.0), 0, 4, listOf(entry()))
    // A 0.001-degree shift exceeds the 4-decimal rounding → different key.
    assertNull(SliceEstimateCache.get("u", Bbox(10.001, 20.0, 30.0, 40.0), 0, 4))
  }

  @Test
  fun url_and_zoom_range_are_part_of_the_key() {
    val box = Bbox(0.0, 0.0, 1.0, 1.0)
    SliceEstimateCache.put("a", box, 0, 4, listOf(entry()))
    assertNull(SliceEstimateCache.get("b", box, 0, 4))
    assertNull(SliceEstimateCache.get("a", box, 1, 4))
    assertNull(SliceEstimateCache.get("a", box, 0, 5))
  }

  @Test
  fun clear_drops_all_entries() {
    val box = Bbox(0.0, 0.0, 1.0, 1.0)
    SliceEstimateCache.put("u", box, 0, 4, listOf(entry()))
    SliceEstimateCache.clear()
    assertNull(SliceEstimateCache.get("u", box, 0, 4))
  }

  @Test
  fun eviction_drops_eldest_past_sixteen_entries() {
    // Insert 17 distinct entries (distinct by latitude); the 16-entry LRU bound
    // evicts the eldest (first-inserted) when the 17th lands.
    for (i in 0 until 17) {
      SliceEstimateCache.put("u", Bbox(i.toDouble(), 0.0, i + 0.5, 1.0), 0, 4, listOf(entry(i)))
    }
    assertNull(SliceEstimateCache.get("u", Bbox(0.0, 0.0, 0.5, 1.0), 0, 4))
    // The most-recently inserted is still present.
    val newest = SliceEstimateCache.get("u", Bbox(16.0, 0.0, 16.5, 1.0), 0, 4)
    assertEquals(16, newest?.first()?.z)
  }
}
