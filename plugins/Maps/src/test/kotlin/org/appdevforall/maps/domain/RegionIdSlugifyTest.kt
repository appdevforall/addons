package org.appdevforall.maps.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RegionId.slugify] — the function that turns a user-typed region
 * name into a cache-safe id. This is the half the old tests didn't cover (only the
 * validator was tested). The NFC test builds its strings from code points so the
 * composed-vs-decomposed distinction is unambiguous in source; the other accented
 * cases use ordinary (precomposed) UTF-8 letters, and the script literals
 * (CJK / Arabic / Cyrillic) are safe to embed verbatim.
 */
class RegionIdSlugifyTest {

    @Test
    fun keeps_non_latin_letters() {
        assertEquals("北京", RegionId.slugify("北京"))
        assertEquals("القاهرة", RegionId.slugify("القاهرة"))
        assertEquals("москва", RegionId.slugify("Москва")) // Cyrillic lowercased
    }

    @Test
    fun lowercases_accented_latin() {
        assertEquals("córdoba", RegionId.slugify("Córdoba"))
    }

    @Test
    fun nfc_normalises_so_decomposed_and_composed_match() {
        // Built from code points so composed vs decomposed is unambiguous in source.
        val composed = "Caf" + Char(0x00E9)     // precomposed é
        val decomposed = "Cafe" + Char(0x0301)  // e + combining acute
        val expected = "caf" + Char(0x00E9)     // lowercase precomposed é
        assertEquals(expected, RegionId.slugify(composed))
        assertEquals(expected, RegionId.slugify(decomposed))
        assertEquals(RegionId.slugify(composed), RegionId.slugify(decomposed))
    }

    @Test
    fun collapses_separators_punctuation_and_whitespace_to_hyphen() {
        assertEquals("san-francisco", RegionId.slugify("San Francisco!"))
        assertEquals("a-b", RegionId.slugify("a / b"))
        assertEquals("foo-bar", RegionId.slugify("foo___bar"))
        assertEquals("são-paulo", RegionId.slugify("São Paulo"))
    }

    @Test
    fun trims_leading_and_trailing_hyphens() {
        assertEquals("hi", RegionId.slugify("  hi  "))
        assertEquals("x", RegionId.slugify("--x--"))
    }

    @Test
    fun blank_when_no_usable_characters() {
        assertEquals("", RegionId.slugify("!!!"))
        assertEquals("", RegionId.slugify("   "))
        assertEquals("", RegionId.slugify("///"))
        assertEquals("", RegionId.slugify(""))
    }

    @Test
    fun caps_length() {
        val s = RegionId.slugify("a".repeat(100))
        assertEquals(RegionId.MAX_LEN, s.length)
        assertTrue(RegionId.isValid(s))
    }

    @Test
    fun output_is_always_blank_or_valid() {
        val names = listOf(
            "San Francisco", "北京 (Beijing)", "القاهرة!", "Café",
            "São Paulo", "  --  ", "123", "Zürich", "tokyo/東京",
            "a".repeat(200), "", "!@#\$%",
        )
        for (n in names) {
            val s = RegionId.slugify(n)
            assertTrue(
                "slugify('$n') = '$s' must be blank or pass isValid",
                s.isEmpty() || RegionId.isValid(s),
            )
        }
    }
}
