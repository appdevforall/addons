package com.itsaky.androidide.plugins.aiagentgemini.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [GeminiContextWindow]: which figure wins, and that the stored form survives. */
class GeminiContextWindowTest {

    @Test
    fun givenACatalogLimit_whenResolved_thenTheCatalogDecides() {
        val known = mapOf("gemini-2.5-pro" to 1_048_576, "gemini-2.0-flash-lite" to 32_768)

        assertEquals(32_768, GeminiContextWindow.resolve("gemini-2.0-flash-lite", known))
        assertEquals(1_048_576, GeminiContextWindow.resolve("models/gemini-2.5-pro", known))
    }

    @Test
    fun givenADocumentedFamilyNotInTheCatalog_whenResolved_thenTheDefaultIsUsed() {
        listOf("gemini-1.5-flash", "gemini-2.0-flash", "gemini-2.5-flash", "Gemini-2.5-Pro").forEach {
            assertEquals(it, GeminiContextWindow.DEFAULT_TOKENS, GeminiContextWindow.resolve(it, emptyMap()))
        }
        assertEquals(1_048_576, GeminiContextWindow.DEFAULT_TOKENS)
    }

    @Test
    fun givenAnUndocumentedModelNotInTheCatalog_whenResolved_thenItIsUnknown() {
        assertEquals(0, GeminiContextWindow.resolve("gemma-3-27b-it", emptyMap()))
        assertEquals(0, GeminiContextWindow.resolve("gemini-1.0-pro", emptyMap()))
    }

    @Test
    fun givenLimits_whenRoundTripped_thenTheyComeBackUnchanged() {
        val windows = mapOf("gemini-2.5-flash" to 1_048_576, "gemini-2.0-flash-lite" to 32_768)

        assertEquals(windows, GeminiContextWindow.decode(GeminiContextWindow.encode(windows)))
    }

    @Test
    fun givenNothingOrGarbageStored_whenDecoded_thenNothingIsKnown() {
        assertNull(GeminiContextWindow.encode(emptyMap()))
        assertTrue(GeminiContextWindow.decode(null).isEmpty())
        assertTrue(GeminiContextWindow.decode("no-tab-here\nid\tnot-a-number\n\t5\nok\t-3").isEmpty())
    }
}
