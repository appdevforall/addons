package com.itsaky.androidide.plugins.aiagentclaude.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each row is a 400 on the models it is wrong for, so the table is pinned model by model, by alias
 * and by the dated id the catalog actually lists.
 */
class ClaudeModelTraitsTest {

    @Test
    fun givenModelsThatTakeIt_whenAskedAboutAdaptiveThinking_thenTheyDo() {
        listOf(
            "claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1", "claude-opus-5",
            "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6", "claude-sonnet-4-6",
        ).forEach { assertTrue("$it takes adaptive thinking", ClaudeModelTraits.supportsAdaptiveThinking(it)) }
    }

    @Test
    fun givenModelsThatThinkOnlyWithABudget_whenAskedAboutAdaptiveThinking_thenTheyDoNot() {
        listOf(
            "claude-haiku-4-5", "claude-haiku-4-5-20251001", "claude-opus-4-5-20251101",
            "claude-sonnet-4-5", "claude-opus-4-20250514", "claude-new-model",
        ).forEach { assertFalse("$it rejects adaptive thinking", ClaudeModelTraits.supportsAdaptiveThinking(it)) }
    }

    @Test
    fun givenEffortModels_whenAsked_thenTheyTakeItByAliasAndByDatedId() {
        listOf(
            "claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1",
            "claude-opus-4-8", "claude-sonnet-4-6", "claude-opus-4-5", "claude-opus-4-5-20251101",
        ).forEach { assertTrue("$it takes effort", ClaudeModelTraits.supportsEffort(it)) }
    }

    @Test
    fun givenOlderOrSmallerModels_whenAskedAboutEffort_thenTheyDoNot() {
        // The dated Opus 4 and Sonnet 4 ids match none of the aliases, which is how they got
        // effort before, and a 400 on every request.
        listOf(
            "claude-haiku-4-5-20251001", "claude-sonnet-4-5", "claude-opus-4-1",
            "claude-opus-4-20250514", "claude-sonnet-4-20250514", "claude-3-7-sonnet-latest",
            "  Claude-Haiku-4-5  ", "claude-new-model",
        ).forEach { assertFalse("$it rejects effort", ClaudeModelTraits.supportsEffort(it)) }
    }

    @Test
    fun givenWhatTheCatalogSaid_whenAsked_thenItWinsOverTheTables() {
        val listed = ModelCapabilities(maxTokens = 32_000, adaptiveThinking = true, effort = true)

        assertTrue(ClaudeModelTraits.supportsEffort("claude-new-model", listed))
        assertTrue(ClaudeModelTraits.supportsAdaptiveThinking("claude-new-model", listed))
        assertEquals(32_000, ClaudeModelTraits.outputCap("claude-new-model", listed))
        assertFalse(ClaudeModelTraits.supportsEffort("claude-opus-5-5", ModelCapabilities(effort = false)))
    }

    @Test
    fun givenOutputCaps_whenAsked_thenOpus4IsHeldTo32KAndTheRestToTheCeiling() {
        assertEquals(32_000, ClaudeModelTraits.outputCap("claude-opus-4-20250514"))
        assertEquals(32_000, ClaudeModelTraits.outputCap("claude-opus-4-1-20250805"))
        assertEquals(ClaudeModelTraits.MAX_OUTPUT_CEILING, ClaudeModelTraits.outputCap("claude-haiku-4-5"))
        // A larger listed cap is still held at the ceiling every request is sized for.
        assertEquals(
            ClaudeModelTraits.MAX_OUTPUT_CEILING,
            ClaudeModelTraits.outputCap("claude-opus-5-5", ModelCapabilities(maxTokens = 128_000))
        )
    }

    @Test
    fun givenTheModelsThatAlwaysThink_whenAsked_thenOnlyThe5xLineSaysSo() {
        assertTrue(ClaudeModelTraits.thinksByDefault("claude-opus-5-5"))
        assertTrue(ClaudeModelTraits.thinksByDefault("claude-sonnet-5"))
        assertFalse(ClaudeModelTraits.thinksByDefault("claude-opus-4-8"))
        assertFalse(ClaudeModelTraits.thinksByDefault("claude-haiku-4-5"))
    }

    @Test
    fun givenAnAliasAndItsDatedId_whenCompared_thenTheyAreTheSameModel() {
        assertTrue(ClaudeModelTraits.sameModel("claude-haiku-4-5", "claude-haiku-4-5-20251001"))
        assertTrue(ClaudeModelTraits.sameModel("claude-opus-5-5", "claude-opus-5-5"))
        // A longer alias is not a dated id: Opus 4.5 is not Opus 4.
        assertFalse(ClaudeModelTraits.sameModel("claude-opus-4", "claude-opus-4-5"))
        assertFalse(ClaudeModelTraits.sameModel("claude-opus-5", "claude-opus-5-5"))
    }

    @Test
    fun givenACatalogListingOnlyTheDatedId_whenLookingUpTheAlias_thenItsEntryIsFound() {
        val catalog = mapOf("claude-haiku-4-5-20251001" to ModelCapabilities(maxTokens = 64_000))

        assertEquals(64_000, ClaudeModelTraits.lookup("claude-haiku-4-5", catalog)?.maxTokens)
        assertNull(ClaudeModelTraits.lookup("claude-opus-5-5", catalog))
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
