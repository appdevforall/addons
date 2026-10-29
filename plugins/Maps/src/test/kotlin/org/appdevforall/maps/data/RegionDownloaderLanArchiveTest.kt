package org.appdevforall.maps.data

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.appdevforall.maps.domain.SourceKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [RegionDownloader.resolveTilesUrl] picks the newest archive name a LAN box actually serves. */
class RegionDownloaderLanArchiveTest {

    private val newArchive = "openstreetmap-openmaptiles.2026-10-01.z00-z14.pmtiles"
    private val julyArchive = "openstreetmap-openmaptiles.2026-07-01.z00-z14.pmtiles"
    private val oldArchive = "openstreetmap-openmaptiles.2026-04-01.z00-z14.pmtiles"
    private val server = MockWebServer()

    /**
     * Answers [code] (a one-byte range by default) for each name in [held], [listing] at
     * `/maps/data/`, and 404 for anything else.
     */
    private fun serve(
        vararg held: String,
        code: Int = 206,
        listing: MockResponse = MockResponse().setResponseCode(404),
    ) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when {
                    request.path == "/maps/data/" -> listing
                    held.any { request.path == "/maps/$it" } ->
                        MockResponse().setResponseCode(code).setBody("P")
                    else -> MockResponse().setResponseCode(404)
                }
        }
        server.start()
    }

    /** An nginx JSON autoindex entry, in the shape `autoindex_format json` emits. */
    private fun entry(name: String, type: String = "file") =
        """{"name":"$name","type":"$type","mtime":"Wed, 01 Oct 2026 00:00:00 GMT","size":1024}"""

    private fun listing(vararg entries: String) =
        MockResponse().setResponseCode(200).setBody(entries.joinToString(",", "[", "]"))

    private fun requestedPaths(): List<String?> = List(server.requestCount) { server.takeRequest().path }

    private val host get() = server.url("/").toString()

    private fun resolve(resolver: IiabArchiveResolver = IiabArchiveResolver { null }) =
        runBlocking { RegionDownloader.resolveTilesUrl(SourceKind.IIAB_LAN, host, resolver) }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a box with the current build gets the current archive and the catalog is never read`() {
        serve(newArchive, oldArchive)
        var catalogRead = false
        val url = resolve(IiabArchiveResolver { catalogRead = true; null })
        assertEquals(server.url("/maps/$newArchive").toString(), url)
        assertFalse(catalogRead)
    }

    @Test
    fun `a box installed before the 2026-10 update gets its 2026-04 archive`() {
        serve(oldArchive)
        assertEquals(server.url("/maps/$oldArchive").toString(), resolve())
        // The size estimate builds its URL without I/O and must read the same file.
        assertEquals(
            server.url("/maps/$oldArchive").toString(),
            RegionDownloader.buildTilesUrl(SourceKind.IIAB_LAN, host),
        )
    }

    @Test
    fun `a box with neither archive fails with every name it was asked for`() {
        serve()
        try {
            resolve()
            fail("expected IOException for a box with no known archive")
        } catch (e: IOException) {
            val message = e.message.orEmpty()
            assertTrue(message, message.contains("no zoom 0-14 OpenStreetMap archive"))
            assertTrue(message, message.contains("internet source"))
            assertTrue(message, message.contains("/maps/data/ listing: 404"))
            assertTrue(message, message.contains("$newArchive: 404"))
            assertTrue(message, message.contains("$oldArchive: 404"))
        }
    }

    @Test
    fun `a box that ignores the Range header and sends the whole file still counts as holding it`() {
        serve(newArchive, code = 200)
        assertEquals(server.url("/maps/$newArchive").toString(), resolve())
        assertEquals("/maps/data/", server.takeRequest().path)
        assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `a host that refuses connections fails naming each archive as unanswered`() {
        serve()
        val deadHost = host
        server.shutdown()
        try {
            runBlocking { RegionDownloader.resolveTilesUrl(SourceKind.IIAB_LAN, deadHost, IiabArchiveResolver { null }) }
            fail("expected IOException for a host that answers no probe")
        } catch (e: IOException) {
            val message = e.message.orEmpty()
            assertTrue(message, message.contains("$newArchive: no answer"))
            assertTrue(message, message.contains("$oldArchive: no answer"))
        }
    }

    @Test
    fun `a listing with two archives picks the newer one without probing`() {
        serve(newArchive, julyArchive, listing = listing(entry(julyArchive), entry(newArchive)))
        assertEquals(server.url("/maps/$newArchive").toString(), resolve())
        assertEquals(listOf("/maps/data/"), requestedPaths())
    }

    @Test
    fun `a retired archive renamed to pmtiles-old is ignored`() {
        serve(listing = listing(entry(julyArchive), entry("$newArchive.old")))
        assertEquals(server.url("/maps/$julyArchive").toString(), resolve())
    }

    @Test
    fun `directory entries are ignored`() {
        serve(listing = listing(entry(newArchive, type = "directory"), entry(oldArchive)))
        assertEquals(server.url("/maps/$oldArchive").toString(), resolve())
    }

    @Test
    fun `a missing listing falls back to probing newest first`() {
        serve(julyArchive)
        assertEquals(server.url("/maps/$julyArchive").toString(), resolve())
        assertEquals(
            listOf(
                "/maps/data/",
                "/maps/$newArchive",
                "/maps/openstreetmap-openmaptiles.2026-09-01.z00-z14.pmtiles",
                "/maps/$julyArchive",
            ),
            requestedPaths(),
        )
    }

    @Test
    fun `a listing that is not JSON falls back to probing`() {
        serve(oldArchive, listing = MockResponse().setResponseCode(200).setBody("<html>Index of /maps/</html>"))
        assertEquals(server.url("/maps/$oldArchive").toString(), resolve())
    }

    @Test
    fun `a listing with no z00-z14 archive and no probe hit says to use the internet source`() {
        serve(listing = listing(entry("osm-zoom-0-8.pmtiles"), entry("$newArchive.old")))
        try {
            resolve()
            fail("expected IOException for a box with only a zoom 0-8 archive")
        } catch (e: IOException) {
            val message = e.message.orEmpty()
            assertTrue(message, message.contains("no zoom 0-14 OpenStreetMap archive"))
            assertTrue(message, message.contains("IIAB installs zoom 0-8 by default"))
            assertTrue(message, message.contains("internet source"))
            assertTrue(message, message.contains("/maps/data/ listing: no z00-z14 archive"))
            assertTrue(message, message.contains("$oldArchive: 404"))
        }
    }

    @Test
    fun `an oversized listing is not read and falls back to probing`() {
        val padded = "[" + entry(newArchive) + " ".repeat(1024 * 1024) + "]"
        serve(julyArchive, listing = MockResponse().setResponseCode(200).setBody(padded))
        assertEquals(server.url("/maps/$julyArchive").toString(), resolve())
    }
}
