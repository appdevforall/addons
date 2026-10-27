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
    private val oldArchive = "openstreetmap-openmaptiles.2026-04-01.z00-z14.pmtiles"
    private val server = MockWebServer()

    /** Answers a one-byte range for each name in [held], 404 for anything else. */
    private fun serve(vararg held: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (held.any { request.path == "/maps/$it" }) {
                    MockResponse().setResponseCode(206).setBody("P")
                } else {
                    MockResponse().setResponseCode(404)
                }
        }
        server.start()
    }

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
            assertTrue(message, message.contains("no known vector archive"))
            assertTrue(message, message.contains("$newArchive: 404"))
            assertTrue(message, message.contains("$oldArchive: 404"))
        }
    }
}
