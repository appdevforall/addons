package org.appdevforall.maps.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the pure style-JSON construction extracted from BboxPickerFragment. */
class IiabStyleBuilderTest {

    @Test
    fun `null tiles url yields a background-only style`() {
        val style = IiabStyleBuilder.buildStyle(pmtilesHttpUrl = null, fontsRoot = null)
        assertTrue(style.contains("\"background\""))
        assertFalse(style.contains("openmaptiles"))
        assertFalse(style.contains("pmtiles://"))
    }

    @Test
    fun `tiles url is wrapped in the pmtiles scheme and used as the vector source`() {
        val style = IiabStyleBuilder.buildStyle(
            pmtilesHttpUrl = "http://127.0.0.1:8080/tiles.pmtiles",
            fontsRoot = "/data/fonts",
        )
        assertTrue(style.contains("\"url\": \"pmtiles://http://127.0.0.1:8080/tiles.pmtiles\""))
        assertTrue(style.contains("\"openmaptiles\""))
        assertTrue(style.contains("place-city-label"))
    }

    @Test
    fun `fontsRoot present uses a file glyphs url`() {
        val style = IiabStyleBuilder.buildStyle("http://h/t.pmtiles", "/data/fonts")
        assertTrue(style.contains("\"glyphs\": \"file:///data/fonts/{fontstack}/{range}.pbf\""))
    }

    @Test
    fun `fontsRoot null falls back to an unfulfillable asset glyphs url`() {
        val style = IiabStyleBuilder.buildStyle("http://h/t.pmtiles", fontsRoot = null)
        assertTrue(style.contains("\"glyphs\": \"asset://fonts/{fontstack}/{range}.pbf\""))
    }
}
