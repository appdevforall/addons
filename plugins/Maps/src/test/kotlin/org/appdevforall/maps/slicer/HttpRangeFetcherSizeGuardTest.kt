package org.appdevforall.maps.slicer

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
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
