package org.appdevforall.maps.slicer

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers [HttpRangeFetcher] responses the size guards must refuse: a chunked 206 that ends
 * short of the range, and a Range-ignoring 200 asked for an offset beyond what it can slice.
 */
class HttpRangeFetcherShortBodyTest {

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

    @Test
    fun `a chunked 206 shorter than the range is refused and not cached`() {
        server.enqueue(MockResponse().setResponseCode(206).setChunkedBody(Buffer().write(ByteArray(3)), 2))
        val url = server.url("/a.pmtiles").toString()

        val thrown = runCatching { HttpRangeFetcher(url, OkHttpClient()).readRange(0L, 8) }.exceptionOrNull()

        assertTrue("got $thrown", thrown is IllegalArgumentException)
        assertEquals("Range response returned 3 bytes, expected 8", thrown!!.message)
        assertNull(HttpRangeByteCache.get(url, 0L, 8))
    }

    @Test
    fun `a 200 fallback cannot serve an offset beyond the int range`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(ByteArray(16))))
        val url = server.url("/b.pmtiles").toString()
        val offset = Int.MAX_VALUE.toLong() + 1

        val thrown = runCatching { HttpRangeFetcher(url, OkHttpClient()).readRange(offset, 4) }.exceptionOrNull()

        assertTrue("got $thrown", thrown is IllegalArgumentException)
        assertTrue(thrown!!.message!!.startsWith("200-OK fallback can't satisfy range $offset-"))
    }
}
