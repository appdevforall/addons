package org.appdevforall.maps.data

import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [IiabCatalog] parsing and [IiabArchiveResolver] resolution, fallback and size cap. */
class IiabCatalogTest {

    /** The upstream catalog, fetched from IIAB's `maps_catalog_url` on 2026-10-14. */
    private val realCatalog: String =
        javaClass.getResource("/iiab/maps-catalog-2026-10-14.json")!!.readText()

    private fun catalog(vector14: String) =
        """{"data":{"vector":{"14":"$vector14","11":"x"}}}"""

    @Test
    fun `reads the z00-z14 archive from the real upstream catalog`() {
        assertEquals(
            "openstreetmap-openmaptiles.2026-10-01.z00-z14.pmtiles",
            IiabCatalog.vectorArchive(realCatalog),
        )
    }

    @Test
    fun `rejects an entry that is off the mirror, the wrong zoom range or not a dated name`() {
        val base = IiabCatalog.MIRROR_BASE
        assertNull(IiabCatalog.vectorArchive(catalog("https://evil.example/maps/2/openstreetmap-openmaptiles.2026-10-01.z00-z14.pmtiles")))
        assertNull(IiabCatalog.vectorArchive(catalog("${base}openstreetmap-openmaptiles.2026-10-01.z00-z11.pmtiles")))
        assertNull(IiabCatalog.vectorArchive(catalog("${base}openstreetmap-openmaptiles.latest.z00-z14.pmtiles")))
        assertNull(IiabCatalog.vectorArchive(catalog("${base}../openstreetmap-openmaptiles.2026-10-01.z00-z14.pmtiles")))
    }

    @Test
    fun `returns null for malformed json or a missing key`() {
        assertNull(IiabCatalog.vectorArchive("not json"))
        assertNull(IiabCatalog.vectorArchive("""{"data":{"vector":{"11":"x"}}}"""))
        assertNull(IiabCatalog.vectorArchive("""{"README":[]}"""))
    }

    @Test
    fun `dateOf extracts the archive date and rejects other names`() {
        assertEquals("2026-10-01", IiabCatalog.dateOf(IiabCatalog.FALLBACK_VECTOR_ARCHIVE))
        assertNull(IiabCatalog.dateOf("naturalearth-openmaptiles.2025-12-10.z00-z08.pmtiles"))
    }

    @Test
    fun `resolver falls back when the fetch fails, returns null or yields an unusable catalog`() = runBlocking {
        val fallback = IiabCatalog.FALLBACK_VECTOR_ARCHIVE
        assertEquals(fallback, IiabArchiveResolver { throw java.io.IOException("offline") }.resolve())
        assertEquals(fallback, IiabArchiveResolver { null }.resolve())
        assertEquals(fallback, IiabArchiveResolver { "{}" }.resolve())
    }

    @Test
    fun `resolver fetches once after success and retries after a failure`() = runBlocking {
        var calls = 0
        var answer: String? = null
        val resolver = IiabArchiveResolver { calls++; answer }

        assertEquals(IiabCatalog.FALLBACK_VECTOR_ARCHIVE, resolver.resolve())
        assertEquals(IiabCatalog.FALLBACK_VECTOR_ARCHIVE, resolver.current())
        // A date the fallback does not carry, so a resolver that ignored the catalog fails here.
        val newer = "openstreetmap-openmaptiles.2027-04-01.z00-z14.pmtiles"
        answer = catalog("${IiabCatalog.MIRROR_BASE}$newer")
        assertEquals(newer, resolver.resolve())
        assertEquals(newer, resolver.current())
        resolver.resolve()
        assertEquals("a failed fetch is retried; a successful one is not", 2, calls)
    }

    @Test
    fun `resolver does not swallow cancellation`() = runBlocking {
        try {
            IiabArchiveResolver { throw CancellationException("cancelled") }.resolve()
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }

    @Test
    fun `the catalog client gives up on a server that trickles its body`() = runBlocking {
        val server = MockWebServer()
        // One byte a second never trips the 5 s read timeout, so only a whole-call limit ends it.
        server.enqueue(MockResponse().setResponseCode(200).setBody("z".repeat(30)).throttleBody(1, 1, TimeUnit.SECONDS))
        server.start()
        try {
            val started = System.nanoTime()
            try {
                IiabArchiveResolver.fetchText(IiabArchiveResolver.catalogClient, server.url("/slow").toString())
                fail("a 30 s trickle must not complete")
            } catch (e: InterruptedIOException) {
                val secs = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
                assertTrue("gave up after $secs s", secs < 10)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `fetchText returns a 200 body and null for errors and oversized bodies`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(realCatalog))
        server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(101)))
        // Chunked, so there is no Content-Length to refuse up front.
        server.enqueue(MockResponse().setResponseCode(200).setChunkedBody(Buffer().writeUtf8("y".repeat(101)), 10))
        server.start()
        try {
            val client = OkHttpClient()
            val url = server.url("/maps-catalog.json").toString()
            assertEquals(realCatalog, IiabArchiveResolver.fetchText(client, url))
            assertNull(IiabArchiveResolver.fetchText(client, url))
            assertNull("declared length over the cap", IiabArchiveResolver.fetchText(client, url, maxBytes = 100))
            assertNull("streamed length over the cap", IiabArchiveResolver.fetchText(client, url, maxBytes = 100))
        } finally {
            server.shutdown()
        }
    }
}
