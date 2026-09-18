package com.itsaky.androidide.plugins.aicore.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [respondMessageOf] — the answer-key tolerance `respond` never had, because it is
 * the one "tool" with no handler and so no `ToolHandler.argAliases`.
 */
class RespondArgsTest {

    @Test
    fun givenTheDocumentedKey_whenRead_thenTheMessageComesBack() {
        assertEquals("All done.", respondMessageOf(mapOf("message" to "All done.")))
    }

    @Test
    fun givenAnAlternateKey_whenRead_thenTheAnswerStillComesBack() {
        listOf("text", "response", "answer", "content").forEach { key ->
            assertEquals("'$key'", "All done.", respondMessageOf(mapOf(key to "All done.")))
        }
    }

    @Test
    fun givenSeveralKeys_whenRead_thenTheDocumentedOneWins() {
        val args = mapOf("content" to "fourth", "text" to "second", "message" to "first")

        assertEquals("first", respondMessageOf(args))
    }

    @Test
    fun givenABlankDocumentedKeyAndAFilledAlternate_whenRead_thenTheAlternateIsUsed() {
        // A blank "message" is what the reported Gemini failure looked like.
        assertEquals("real answer", respondMessageOf(mapOf("message" to "  ", "text" to "real answer")))
    }

    @Test
    fun givenNoUsableKey_whenRead_thenItIsNull() {
        assertNull(respondMessageOf(emptyMap()))
        assertNull(respondMessageOf(mapOf("message" to "")))
        assertNull(respondMessageOf(mapOf("message" to null)))
        assertNull(respondMessageOf(mapOf("summary" to "under an unknown key")))
    }

    @Test
    fun givenANonStringValue_whenRead_thenItIsRendered() {
        // The extractor hands back whatever org.json parsed, which is not always a String.
        assertEquals("42", respondMessageOf(mapOf("message" to 42)))
    }
}
