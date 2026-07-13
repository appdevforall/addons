package org.appdevforall.maps.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.appdevforall.maps.domain.SourceKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the HEAD-probe I/O path of [ReachabilityProbe.isReachable] against a
 * local [MockWebServer] — the arms `ReachabilityProbeTest` (pure URL logic only)
 * leaves untouched:
 *
 *  - 2xx HEAD response → reachable (the `responseCode in 200..399` true arm)
 *  - non-2xx (404 / 500) → not reachable (the range-check false arm)
 *  - malformed probe URL → `runCatching` failure arm → `getOrDefault(false)`
 *  - LAN source with a blank host → the `probeUrlFor(...) ?: return false`
 *    null arm inside `isReachable` itself
 *  - explicit `http://` scheme host honoured by [ReachabilityProbe.buildLanProbeUrl]
 *
 * Deliberately NOT covered: the `withTimeoutOrNull` timeout arm — PROBE_TIMEOUT_MS
 * is a private 6 s constant with no injection point, and a 6-second sleep per run
 * isn't worth one defensive branch (production code untouchable per task rules).
 */
class ReachabilityProbeHeadCoverageTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun isReachable_true_on_200_head_response() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val probe = ReachabilityProbe(internetProbeUrl = server.url("/maps/2/").toString())

        assertTrue(probe.isReachable(SourceKind.INTERNET, host = ""))
        // The probe really issued a HEAD against the configured path.
        val recorded = server.takeRequest()
        assertEquals("HEAD", recorded.method)
        assertEquals("/maps/2/", recorded.path)
    }

    @Test
    fun isReachable_false_on_404() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val probe = ReachabilityProbe(internetProbeUrl = server.url("/maps/2/").toString())

        assertFalse("404 must read as unreachable", probe.isReachable(SourceKind.INTERNET, host = ""))
    }

    @Test
    fun isReachable_false_on_500() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val probe = ReachabilityProbe(internetProbeUrl = server.url("/maps/2/").toString())

        assertFalse("5xx must read as unreachable", probe.isReachable(SourceKind.INTERNET, host = ""))
    }

    @Test
    fun isReachable_probes_the_lan_inventory_url_for_iiab() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val probe = ReachabilityProbe(internetProbeUrl = "https://unused.example.org/")
        // Point the "LAN host" at the mock server (host:port form, no scheme).
        val host = "${server.hostName}:${server.port}"

        assertTrue(probe.isReachable(SourceKind.IIAB_LAN, host = host))
        assertEquals("/maps/extracts.json", server.takeRequest().path)
    }

    @Test
    fun isReachable_false_for_lan_with_blank_host() = runBlocking {
        // probeUrlFor returns null → isReachable's elvis short-circuits to false
        // without any network I/O (no request must reach the server).
        val probe = ReachabilityProbe(internetProbeUrl = server.url("/").toString())

        assertFalse(probe.isReachable(SourceKind.IIAB_LAN, host = "   "))
        assertEquals("no request may be issued for a blank LAN host", 0, server.requestCount)
    }

    @Test
    fun isReachable_false_when_probe_url_is_malformed() = runBlocking {
        // URL(...) throws MalformedURLException inside the runCatching →
        // getOrDefault(false). Never touches the network.
        val probe = ReachabilityProbe(internetProbeUrl = "::definitely not a url::")

        assertFalse(probe.isReachable(SourceKind.INTERNET, host = ""))
    }

    @Test
    fun buildLanProbeUrl_honours_explicit_plain_http_scheme() {
        // ReachabilityProbeTest covers https + schemeless; this is the http:// arm.
        val probe = ReachabilityProbe()
        assertEquals(
            "http://box.lan/maps/extracts.json",
            probe.buildLanProbeUrl("http://box.lan"),
        )
    }
}
