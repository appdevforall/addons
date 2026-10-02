package com.itsaky.androidide.plugins.aiagentclaude.backend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each row is a 400 on the models it is wrong for, so the table is pinned model by model. */
class ClaudeModelTraitsTest {

    @Test
    fun givenCurrentModels_whenAskedAboutEffort_thenTheyTakeIt() {
        listOf(
            "claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1",
            "claude-opus-4-8", "claude-sonnet-4-6", "claude-opus-4-5",
        ).forEach { assertTrue("$it takes effort", ClaudeModelTraits.supportsEffort(it)) }
    }

    @Test
    fun givenOlderOrSmallerModels_whenAskedAboutEffort_thenTheyDoNot() {
        listOf(
            "claude-haiku-4-5", "claude-sonnet-4-5", "claude-opus-4-1",
            "claude-3-7-sonnet-latest", "  Claude-Haiku-4-5  ",
        ).forEach { assertFalse("$it rejects effort", ClaudeModelTraits.supportsEffort(it)) }
    }

    @Test
    fun givenTheModelsWithClassifiers_whenAskedAboutFallbacks_thenTheyTakeThem() {
        listOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")
            .forEach { assertTrue("$it takes fallbacks", ClaudeModelTraits.supportsServerFallback(it)) }
    }

    @Test
    fun givenAnyOtherModel_whenAskedAboutFallbacks_thenItDoesNot() {
        // Exact ids only: a prefix match would send the parameter to a dated or future id that
        // may not list it, and the 400 would land on every request.
        listOf("claude-haiku-4-5", "claude-opus-4-8", "claude-sonnet-5", "claude-opus-5-5-preview")
            .forEach { assertFalse("$it does not take fallbacks", ClaudeModelTraits.supportsServerFallback(it)) }
    }
}
