package org.appdevforall.maps.data

import org.appdevforall.maps.domain.RegionId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RegionCache.isValidRegionId] — the security-critical allowlist
 * that gates a regionId becoming a cache-directory name. Covers the Unicode
 * (non-Latin) support added 2026-06-04 alongside the path-traversal, casing and
 * length guards that must keep holding.
 *
 * Control/format chars are built from code points (not embedded raw) so this
 * source stays free of null bytes and bidi-control "Trojan Source" sequences; the
 * script literals are ordinary UTF-8 letters and safe to embed verbatim.
 */
class RegionIdValidationTest {

    @Test
    fun accepts_ascii_kebab() {
        assertTrue(RegionCache.isValidRegionId("san-francisco"))
        assertTrue(RegionCache.isValidRegionId("region1"))
        assertTrue(RegionCache.isValidRegionId("a"))
    }

    @Test
    fun accepts_non_latin_scripts() {
        // The point of the change: a user names a region in their own script.
        assertTrue("CJK", RegionCache.isValidRegionId("北京"))
        assertTrue("Arabic", RegionCache.isValidRegionId("القاهرة"))
        assertTrue("Cyrillic", RegionCache.isValidRegionId("москва"))
        assertTrue("accented Latin", RegionCache.isValidRegionId("córdoba"))
        assertTrue("Devanagari", RegionCache.isValidRegionId("मुंबई"))
        assertTrue("script + ascii + hyphen", RegionCache.isValidRegionId("tokyo-東京"))
    }

    @Test
    fun rejects_uppercase() {
        // Lowercase-only keeps case-variants from spawning colliding directories.
        assertFalse(RegionCache.isValidRegionId("Café"))
        assertFalse(RegionCache.isValidRegionId("SF"))
        assertFalse(RegionCache.isValidRegionId("Москва"))
    }

    @Test
    fun rejects_path_traversal_and_separators() {
        assertFalse(RegionCache.isValidRegionId(".."))
        assertFalse(RegionCache.isValidRegionId("../etc"))
        assertFalse(RegionCache.isValidRegionId("a/b"))
        assertFalse(RegionCache.isValidRegionId("a\\b"))
        assertFalse(RegionCache.isValidRegionId("a.b"))
        assertFalse(RegionCache.isValidRegionId("/abs"))
    }

    @Test
    fun rejects_leading_hyphen_blank_and_whitespace() {
        assertFalse(RegionCache.isValidRegionId("-foo"))
        assertFalse(RegionCache.isValidRegionId(""))
        assertFalse(RegionCache.isValidRegionId("a b"))
        assertFalse(RegionCache.isValidRegionId("北京 ")) // trailing space
    }

    @Test
    fun rejects_control_format_and_separator_codepoints() {
        // null, tab, RTL override, zero-width joiner, fullwidth solidus.
        for (cp in listOf(0x0000, 0x0009, 0x202E, 0x200D, 0xFF0F)) {
            val s = "a" + Char(cp) + "b"
            assertFalse("U+%04X must be rejected".format(cp), RegionCache.isValidRegionId(s))
        }
    }

    @Test
    fun length_is_capped() {
        assertTrue(RegionCache.isValidRegionId("a".repeat(RegionId.MAX_LEN)))
        assertFalse(RegionCache.isValidRegionId("a".repeat(RegionId.MAX_LEN + 1)))
    }
}
