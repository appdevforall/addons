package org.appdevforall.maps.data

import org.appdevforall.maps.domain.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * URL-builder smoke tests for [RegionDownloader].
 *
 * The class is `internal` so we reach into it via reflection on the
 * package-private `base` function rather than re-exposing it for tests
 * alone. Tests cover:
 *  - INTERNET source → iiab.switnet.org/maps/2/ regardless of host
 *  - IIAB_LAN with bare hostname → http://<host>/maps/
 *  - IIAB_LAN with scheme'd URL → preserves scheme
 *  - IIAB_LAN with blank host → throws (Phase 2 guard)
 */
class RegionDownloaderUrlTest {

    private fun invokeBase(sourceKind: SourceKind, sourceHost: String?): String {
        val method = RegionDownloader::class.java.getDeclaredMethod(
            "base", SourceKind::class.java, String::class.java,
        ).apply { isAccessible = true }
        return method.invoke(RegionDownloader, sourceKind, sourceHost) as String
    }

    @Test
    fun `internet source ignores host`() {
        assertEquals(
            "https://iiab.switnet.org/maps/2/",
            invokeBase(SourceKind.INTERNET, null),
        )
        assertEquals(
            "https://iiab.switnet.org/maps/2/",
            invokeBase(SourceKind.INTERNET, "ignored.example.com"),
        )
    }

    @Test
    fun `lan bare hostname gets http scheme + maps suffix`() {
        assertEquals(
            "http://box.adfa.lan/maps/",
            invokeBase(SourceKind.IIAB_LAN, "box.adfa.lan"),
        )
    }

    @Test
    fun `lan host with scheme preserves scheme`() {
        assertEquals(
            "https://secure.box.local/maps/",
            invokeBase(SourceKind.IIAB_LAN, "https://secure.box.local"),
        )
    }

    @Test
    fun `lan host with trailing slashes is normalised`() {
        assertEquals(
            "http://box.adfa.lan/maps/",
            invokeBase(SourceKind.IIAB_LAN, "http://box.adfa.lan///"),
        )
    }

    @Test
    fun `lan with blank host rejects`() {
        try {
            invokeBase(SourceKind.IIAB_LAN, null)
            fail("Expected IllegalArgumentException for null host")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(
                "Expected IllegalArgumentException, got ${e.targetException.javaClass}",
                e.targetException is IllegalArgumentException,
            )
        }
        try {
            invokeBase(SourceKind.IIAB_LAN, "   ")
            fail("Expected IllegalArgumentException for blank host")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(
                "Expected IllegalArgumentException, got ${e.targetException.javaClass}",
                e.targetException is IllegalArgumentException,
            )
        }
    }

    @Test
    fun `source kind wire values match meta-json contract`() {
        // Catches accidental rename — the meta.json schema depends on these.
        assertEquals("iiab-lan", SourceKind.IIAB_LAN.wireValue)
        assertEquals("internet", SourceKind.INTERNET.wireValue)
        assertEquals("unknown", SourceKind.UNKNOWN.wireValue)
    }
}
