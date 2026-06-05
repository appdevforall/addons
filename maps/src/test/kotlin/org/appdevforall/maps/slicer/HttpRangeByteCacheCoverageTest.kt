package org.appdevforall.maps.slicer

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Covers [HttpRangeByteCache] — the process-wide in-memory LRU keyed by
 * `(url, offset, length)`. Exercises get/put round-trip, the put-of-existing-key
 * no-op, LRU eviction at the 8 MiB bound, and clear(). No network needed; the
 * cache is a pure in-memory object.
 *
 * The cache is a process-wide singleton, so each test clears it in @Before and
 * @After to stay deterministic regardless of run order.
 */
class HttpRangeByteCacheCoverageTest {

  @Before fun reset() = HttpRangeByteCache.clear()

  @After fun tearDown() = HttpRangeByteCache.clear()

  @Test
  fun get_returns_null_when_absent() {
    assertNull(HttpRangeByteCache.get("u", 0L, 16))
  }

  @Test
  fun put_then_get_round_trips() {
    val bytes = byteArrayOf(1, 2, 3, 4)
    HttpRangeByteCache.put("u", 10L, 4, bytes)
    assertArrayEquals(bytes, HttpRangeByteCache.get("u", 10L, 4))
  }

  @Test
  fun key_distinguishes_url_offset_and_length() {
    HttpRangeByteCache.put("a", 0L, 4, byteArrayOf(1))
    // Different url, offset, or length → distinct keys (all absent).
    assertNull(HttpRangeByteCache.get("b", 0L, 4))
    assertNull(HttpRangeByteCache.get("a", 1L, 4))
    assertNull(HttpRangeByteCache.get("a", 0L, 5))
  }

  @Test
  fun put_of_existing_key_is_a_no_op() {
    val first = byteArrayOf(9, 9, 9)
    HttpRangeByteCache.put("u", 0L, 3, first)
    // Second put with the same key must NOT replace the stored value.
    HttpRangeByteCache.put("u", 0L, 3, byteArrayOf(0, 0, 0))
    assertArrayEquals(first, HttpRangeByteCache.get("u", 0L, 3))
  }

  @Test
  fun clear_drops_all_entries() {
    HttpRangeByteCache.put("u", 0L, 2, byteArrayOf(1, 2))
    HttpRangeByteCache.clear()
    assertNull(HttpRangeByteCache.get("u", 0L, 2))
  }

  @Test
  fun eviction_kicks_out_cold_entries_past_8_mib() {
    // MAX_BYTES is 8 MiB. Put nine 1 MiB entries: total 9 MiB forces eviction
    // of the least-recently-used entries until total <= 8 MiB.
    val oneMib = 1024 * 1024
    for (i in 0 until 9) {
      HttpRangeByteCache.put("u", i.toLong(), oneMib, ByteArray(oneMib) { i.toByte() })
    }
    // The first-inserted (coldest) entry should have been evicted.
    assertNull(HttpRangeByteCache.get("u", 0L, oneMib))
    // The most-recently-inserted entry should still be present.
    assertEquals(oneMib, HttpRangeByteCache.get("u", 8L, oneMib)?.size)
  }

  @Test
  fun get_promotes_to_most_recently_used_so_it_survives_eviction() {
    val oneMib = 1024 * 1024
    // Seed entry 0, then fill toward the bound while periodically touching 0.
    HttpRangeByteCache.put("u", 0L, oneMib, ByteArray(oneMib))
    for (i in 1 until 8) {
      HttpRangeByteCache.put("u", i.toLong(), oneMib, ByteArray(oneMib))
      // Touch entry 0 to keep it hot (accessOrder=true promotes it).
      HttpRangeByteCache.get("u", 0L, oneMib)
    }
    // One more insert pushes total past 8 MiB; entry 0, kept hot, should survive
    // while a colder mid entry gets evicted.
    HttpRangeByteCache.put("u", 8L, oneMib, ByteArray(oneMib))
    assertEquals(oneMib, HttpRangeByteCache.get("u", 0L, oneMib)?.size)
    assertNull(HttpRangeByteCache.get("u", 1L, oneMib))
  }
}
