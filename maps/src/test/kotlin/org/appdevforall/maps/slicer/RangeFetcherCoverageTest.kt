package org.appdevforall.maps.slicer

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Covers [RangeFetcher.forUrl] + companion, [HttpRangeFetcher] (via MockWebServer),
 * and [FileRangeFetcher] (via a temp file).
 *
 * The HTTP cases exercise the spec-compliant 206-partial path, the 200-OK
 * local-slice fallback, the 200-fallback refusals (unknown-length and
 * over-cap), and the non-2xx error. The file cases exercise normal reads and
 * the short-read/EOF branch. `HttpRangeByteCache` is cleared per-test so a
 * cached value never short-circuits the network call under assertion.
 */
class RangeFetcherCoverageTest {

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

  // ---- forUrl scheme routing -------------------------------------------------

  @Test
  fun forUrl_http_and_https_yield_http_fetcher() {
    assertTrue(RangeFetcher.forUrl("http://example.com/a.pmtiles") is HttpRangeFetcher)
    assertTrue(RangeFetcher.forUrl("https://example.com/a.pmtiles") is HttpRangeFetcher)
  }

  @Test
  fun forUrl_file_yields_file_fetcher() {
    val f = File.createTempFile("range", ".bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    try {
      val fetcher = RangeFetcher.forUrl("file://${f.absolutePath}")
      assertTrue(fetcher is FileRangeFetcher)
      fetcher.close()
    } finally {
      f.delete()
    }
  }

  @Test
  fun forUrl_unsupported_scheme_throws() {
    val ex = assertThrows(IllegalArgumentException::class.java) {
      RangeFetcher.forUrl("ftp://example.com/a.pmtiles")
    }
    assertTrue(ex.message!!.contains("Unsupported URL scheme"))
  }

  @Test
  fun defaultClient_is_non_null() {
    // Smoke the companion factory so it isn't an uncovered line.
    RangeFetcher.defaultClient()
  }

  @Test
  fun http_fetcher_close_is_the_noop_interface_default() {
    // HttpRangeFetcher does not override close(); it inherits the interface's
    // `close() = Unit` default. Exercise it (and confirm it's idempotent) so the
    // default body is covered and a closed fetcher can still be GC'd safely.
    val fetcher = HttpRangeFetcher(server.url("/x").toString(), RangeFetcher.defaultClient())
    fetcher.close()
    fetcher.close()
  }

  // ---- HttpRangeFetcher: 206 partial -----------------------------------------

  @Test
  fun http_206_returns_range_bytes_and_sends_range_header() {
    val payload = ByteArray(8) { (it + 1).toByte() }
    server.enqueue(MockResponse().setResponseCode(206).setBody(okio.Buffer().write(payload)))
    val url = server.url("/a.pmtiles").toString()

    val fetcher = HttpRangeFetcher(url, RangeFetcher.defaultClient())
    val got = fetcher.readRange(4L, 8)

    assertArrayEquals(payload, got)
    val recorded = server.takeRequest()
    assertEquals("bytes=4-11", recorded.getHeader("Range"))
  }

  @Test
  fun http_206_second_read_is_served_from_cache() {
    val payload = ByteArray(4) { it.toByte() }
    server.enqueue(MockResponse().setResponseCode(206).setBody(okio.Buffer().write(payload)))
    val url = server.url("/a.pmtiles").toString()
    val fetcher = HttpRangeFetcher(url, RangeFetcher.defaultClient())

    fetcher.readRange(0L, 4)
    // Second identical read must hit the cache — no second response enqueued,
    // so if it tried the network the request would hang/fail.
    val second = fetcher.readRange(0L, 4)
    assertArrayEquals(payload, second)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun http_readRange_rejects_negative_offset_and_nonpositive_length() {
    val fetcher = HttpRangeFetcher(server.url("/x").toString(), RangeFetcher.defaultClient())
    assertThrows(IllegalArgumentException::class.java) { fetcher.readRange(-1L, 4) }
    assertThrows(IllegalArgumentException::class.java) { fetcher.readRange(0L, 0) }
  }

  // ---- HttpRangeFetcher: 200 fallback ----------------------------------------

  @Test
  fun http_200_with_known_length_slices_locally() {
    val whole = ByteArray(16) { it.toByte() }
    // Content-Length is set automatically from the body by MockWebServer.
    server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(whole)))
    val url = server.url("/a.pmtiles").toString()

    val fetcher = HttpRangeFetcher(url, RangeFetcher.defaultClient())
    val got = fetcher.readRange(4L, 5)

    assertArrayEquals(whole.copyOfRange(4, 9), got)
  }

  @Test
  fun http_200_unknown_length_chunked_is_refused() {
    // Chunked transfer → contentLength() == -1 → refuse rather than buffer.
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setChunkedBody(okio.Buffer().write(ByteArray(8)), 4),
    )
    val url = server.url("/a.pmtiles").toString()
    val fetcher = HttpRangeFetcher(url, RangeFetcher.defaultClient())

    val ex = assertThrows(IOException::class.java) { fetcher.readRange(0L, 4) }
    assertTrue(ex.message!!.contains("refusing to buffer"))
  }

  @Test
  fun http_206_with_wrong_size_body_fails_length_check() {
    // A spec-compliant 206 must return exactly `length` bytes. A server that
    // returns 206 but a body of the wrong size trips the
    // `require(bytes.size == length || ...)` guard. (Both operands of the `||`
    // reduce to the same length equality, so a wrong size fails both arms.)
    val short = ByteArray(3) // requested 8, server returns only 3
    server.enqueue(MockResponse().setResponseCode(206).setBody(okio.Buffer().write(short)))
    val url = server.url("/a.pmtiles").toString()
    val fetcher = HttpRangeFetcher(url, RangeFetcher.defaultClient())

    val ex = assertThrows(IllegalArgumentException::class.java) { fetcher.readRange(0L, 8) }
    assertTrue(ex.message!!.contains("Range response returned"))
  }

  @Test
  fun http_200_over_cap_known_length_is_refused() {
    // A 200 whose declared Content-Length exceeds MAX_200_FALLBACK_BYTES
    // (32 MiB) must be refused rather than buffered — the `declared > cap` arm
    // of the refusal guard. Send a body just over the cap so contentLength()
    // is a real, large positive value (distinct from the chunked/-1 case).
    val overCap = ByteArray(32 * 1024 * 1024 + 16)
    server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(overCap)))
    val url = server.url("/big.pmtiles").toString()
    val fetcher = HttpRangeFetcher(url, RangeFetcher.defaultClient())

    val ex = assertThrows(IOException::class.java) { fetcher.readRange(0L, 4) }
    assertTrue(ex.message!!.contains("refusing to buffer"))
  }

  @Test
  fun http_200_fallback_cannot_satisfy_out_of_range_request() {
    // A 200 (Range-ignored) returns the whole file. If the requested range
    // extends past the returned body, the local slice can't be cut — the
    // `require(... (offset.toInt() + length) <= whole.size)` guard fails.
    val whole = ByteArray(8) { it.toByte() } // 8-byte "whole file", well under the cap
    server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(whole)))
    val url = server.url("/a.pmtiles").toString()
    val fetcher = HttpRangeFetcher(url, RangeFetcher.defaultClient())

    // Request 10 bytes from offset 4 → 4+10=14 > 8 → unsatisfiable.
    val ex = assertThrows(IllegalArgumentException::class.java) { fetcher.readRange(4L, 10) }
    assertTrue(ex.message!!.contains("200-OK fallback can't satisfy range"))
  }

  @Test
  fun http_non_2xx_throws_io_exception() {
    server.enqueue(MockResponse().setResponseCode(404))
    val url = server.url("/missing.pmtiles").toString()
    val fetcher = HttpRangeFetcher(url, RangeFetcher.defaultClient())

    val ex = assertThrows(IOException::class.java) { fetcher.readRange(0L, 4) }
    assertTrue(ex.message!!.contains("HTTP 404"))
  }

  // ---- FileRangeFetcher ------------------------------------------------------

  @Test
  fun file_reads_requested_range() {
    val f = File.createTempFile("range", ".bin")
    try {
      val data = ByteArray(32) { it.toByte() }
      f.writeBytes(data)
      val fetcher = FileRangeFetcher(f)
      val got = fetcher.readRange(8L, 10)
      assertArrayEquals(data.copyOfRange(8, 18), got)
      fetcher.close()
    } finally {
      f.delete()
    }
  }

  @Test
  fun file_read_past_eof_throws() {
    val f = File.createTempFile("range", ".bin")
    try {
      f.writeBytes(ByteArray(4))
      val fetcher = FileRangeFetcher(f)
      // Request 8 bytes starting at offset 2 of a 4-byte file → EOF mid-read.
      val ex = assertThrows(IOException::class.java) { fetcher.readRange(2L, 8) }
      assertTrue(ex.message!!.contains("EOF"))
      fetcher.close()
    } finally {
      f.delete()
    }
  }
}
