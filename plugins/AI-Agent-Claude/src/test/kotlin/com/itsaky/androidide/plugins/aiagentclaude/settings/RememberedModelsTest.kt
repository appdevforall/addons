package com.itsaky.androidide.plugins.aiagentclaude.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remembered model list. This is what makes the dropdown work on a second visit to the pane,
 * which it did not before: the ViewModel is rebuilt each time the pane opens, so an in-memory list
 * did not survive.
 */
class RememberedModelsTest {

    @Test
    fun givenModels_whenRoundTripped_thenTheyComeBackInOrder() {
        val models = listOf("gemma-4-12b", "qwen3.5-0.8b", "llama3.2")
        assertEquals(models, RememberedModels.decode(RememberedModels.encode(models)))
    }

    @Test
    fun givenBlanksAndDuplicates_whenEncoded_thenTheyAreDropped() {
        val encoded = RememberedModels.encode(listOf("claude-opus-5-5", " claude-opus-5-5 ", "", "   ", "claude-haiku-4-5"))
        assertEquals(listOf("claude-opus-5-5", "claude-haiku-4-5"), RememberedModels.decode(encoded))
    }

    @Test
    fun givenNoModels_whenEncoded_thenNothingIsWorthStoring() {
        assertNull(RememberedModels.encode(emptyList()))
        assertNull(RememberedModels.encode(listOf("", "  ")))
    }

    @Test
    fun givenNothingStored_whenDecoded_thenTheListIsEmpty() {
        assertTrue(RememberedModels.decode(null).isEmpty())
        assertTrue(RememberedModels.decode("").isEmpty())
    }

    @Test
    fun givenAHugeCatalog_whenEncoded_thenItIsCapped() {
        // SharedPreferences is the wrong place for an unbounded list, whatever the API returns.
        val many = (1..500).map { "model-$it" }
        val decoded = RememberedModels.decode(RememberedModels.encode(many))
        assertEquals(RememberedModels.MAX_REMEMBERED, decoded.size)
        assertEquals("model-1", decoded.first())
    }

    @Test
    fun givenAnIdWithSurroundingWhitespace_whenDecoded_thenItIsTrimmed() {
        assertEquals(listOf("claude-opus-5-5", "claude-haiku-4-5"), RememberedModels.decode(" claude-opus-5-5 \n claude-haiku-4-5 "))
    }
}
