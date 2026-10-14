package org.appdevforall.maps.data

import org.appdevforall.maps.domain.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests the pure URL logic extracted from SourcePickerFragment. (The HEAD probe itself is I/O.) */
class ReachabilityProbeTest {

    private val probe = ReachabilityProbe(internetProbeUrl = "https://example.org/maps/")

    @Test
    fun `LAN url appends the extracts inventory path`() {
        assertEquals("http://192.168.1.5/maps/extracts.json", probe.buildLanProbeUrl("192.168.1.5"))
    }

    @Test
    fun `LAN url honours an explicit scheme and trims a trailing slash`() {
        assertEquals("https://box.local/maps/extracts.json", probe.buildLanProbeUrl("https://box.local/"))
    }

    @Test
    fun `probeUrlFor returns the internet url for INTERNET`() {
        assertEquals("https://example.org/maps/", probe.probeUrlFor(SourceKind.INTERNET, host = ""))
    }

    @Test
    fun `probeUrlFor is null for LAN with a blank host`() {
        assertNull(probe.probeUrlFor(SourceKind.IIAB_LAN, host = "  "))
    }

    @Test
    fun `probeUrlFor builds the LAN url when a host is present`() {
        assertEquals(
            "http://10.0.0.2/maps/extracts.json",
            probe.probeUrlFor(SourceKind.IIAB_LAN, host = "10.0.0.2"),
        )
    }
}
