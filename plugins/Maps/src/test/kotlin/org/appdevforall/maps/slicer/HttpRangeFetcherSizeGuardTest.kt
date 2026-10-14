package org.appdevforall.maps.slicer

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [HttpRangeFetcher] size-guard arms `RangeFetcherCoverageTest` leaves open:
 *
 *  - a 206 whose body is SHORTER than the requested range (truncated/buggy
 *    server) → the size `require` must reject it instead of returning a short
 *    array that would corrupt the sliced archive downstream;
 *  - a 200-OK fallback whose full body can't satisfy the requested range
 *    (range end past EOF) → the bounds `require` must reject instead of
 *    slicing out of bounds.
 */
class HttpRangeFetcherSizeGuardTest {

  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    HttpRangeByteCache.clear()
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
    HttpRangeByteCache.clear()
  }

  private fun body(n: Int): Buffer = Buffer().write(ByteArray(n) { it.toByte() })

  @Test
  fun rejects_206_with_truncated_body() {
    // Ask for 10 bytes; server 206s with only 5. Must throw, not hand back a
    // short buffer (a silent short read would corrupt header/dir parsing).
    server.enqueue(MockResponse().setResponseCode(206).setBody(body(5)))
    val fetcher = HttpRangeFetcher(server.url("/a.pmtiles").toString(), RangeFetcher.defaultClient())

    val ex = assertThrows(IllegalArgumentException::class.java) {
      fetcher.readRange(0L, 10)
    }
    assertTrue(ex.message!!.contains("expected 10"))
  }

  @Test
  fun rejects_206_whose_declared_length_exceeds_the_range_before_reading_it() {
    // Without the up-front check the whole body is buffered and only then compared.
    server.enqueue(MockResponse().setResponseCode(206).setBody(body(1 shl 20)))
    val fetcher = HttpRangeFetcher(server.url("/c.pmtiles").toString(), RangeFetcher.defaultClient())

    val ex = assertThrows(IllegalArgumentException::class.java) { fetcher.readRange(0L, 8) }
    assertEquals("Range response returned a 1048576-byte body, expected 8", ex.message)
  }

  @Test
  fun rejects_chunked_206_after_reading_one_byte_past_the_range() {
    // Chunked, so there is no Content-Length; the read itself must stop at length + 1.
    server.enqueue(MockResponse().setResponseCode(206).setChunkedBody(body(1 shl 20), 4096))
    val fetcher = HttpRangeFetcher(server.url("/d.pmtiles").toString(), RangeFetcher.defaultClient())

    val ex = assertThrows(IllegalArgumentException::class.java) { fetcher.readRange(0L, 8) }
    assertEquals("Range response returned more than 8 bytes, expected 8", ex.message)
  }

  @Test
  fun accepts_chunked_206_of_exactly_the_range() {
    server.enqueue(MockResponse().setResponseCode(206).setChunkedBody(body(8), 3))
    val fetcher = HttpRangeFetcher(server.url("/e.pmtiles").toString(), RangeFetcher.defaultClient())

    assertArrayEquals(ByteArray(8) { it.toByte() }, fetcher.readRange(0L, 8))
  }

  @Test
  fun rejects_200_fallback_when_range_extends_past_eof() {
    // Server ignores Range and 200s the whole 8-byte file; the requested range
    // [4, 14) runs past EOF → the fallback bounds require must reject.
    server.enqueue(MockResponse().setResponseCode(200).setBody(body(8)))
    val fetcher = HttpRangeFetcher(server.url("/b.pmtiles").toString(), RangeFetcher.defaultClient())

    val ex = assertThrows(IllegalArgumentException::class.java) {
      fetcher.readRange(4L, 10)
    }
    assertTrue(ex.message!!.contains("can't satisfy range"))
  }
}
