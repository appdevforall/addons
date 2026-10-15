package com.itsaky.androidide.plugins.aiagentclaude.backend

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for how the context window is read from the catalog and resolved per model. */
class ClaudeContextWindowTest {

    @Test
    fun givenAListingWithMaxInputTokens_whenRead_thenEachModelKeepsItsWindow() {
        val body = """
            {"data":[
              {"id":"claude-opus-5-5","max_input_tokens":1000000,"max_tokens":128000},
              {"id":"claude-haiku-4-5-20251001","max_input_tokens":200000,"max_tokens":64000},
              {"id":"claude-bare"}
            ]}
        """.trimIndent()

        val entries = ClaudeModelCatalog.entries(body).associate { it.id to it.capabilities }

        assertEquals(1_000_000, entries["claude-opus-5-5"]?.maxInputTokens)
        assertEquals(200_000, entries["claude-haiku-4-5-20251001"]?.maxInputTokens)
        assertEquals(null, entries["claude-bare"]?.maxInputTokens)
    }

    @Test
    fun givenStoredWindows_whenRoundTripped_thenTheyComeBackUnchanged() {
        val entries = listOf(
            ClaudeModelCatalog.Entry("claude-opus-5-5", ModelCapabilities(128_000, true, true, 1_000_000)),
            ClaudeModelCatalog.Entry("claude-bare", ModelCapabilities()),
        )

        val restored = ClaudeModelCatalog.decodeCapabilities(ClaudeModelCatalog.encodeCapabilities(entries))

        assertEquals(entries.associate { it.id to it.capabilities }, restored)
    }

    @Test
    fun givenALineStoredBeforeWindowsWereKept_whenDecoded_thenTheWindowIsUnknown() {
        val restored = ClaudeModelCatalog.decodeCapabilities("claude-opus-4-8\t128000\t1\t1")

        assertEquals(ModelCapabilities(128_000, true, true, null), restored["claude-opus-4-8"])
    }

    @Test
    fun givenACatalogWindow_whenResolved_thenTheCatalogDecides() {
        val known = ModelCapabilities(maxInputTokens = 1_000_000)

        assertEquals(1_000_000, ClaudeModelTraits.contextWindow("claude-opus-5-5", known))
    }

    @Test
    fun givenAModelTheCatalogHasNotDescribed_whenResolved_thenTheConservativeDefaultIsUsed() {
        assertEquals(ClaudeModelTraits.DEFAULT_CONTEXT_WINDOW, ClaudeModelTraits.contextWindow("claude-new", null))
        assertEquals(ClaudeModelTraits.DEFAULT_CONTEXT_WINDOW, ClaudeModelTraits.contextWindow("claude-new", ModelCapabilities(64_000)))
        assertEquals(200_000, ClaudeModelTraits.DEFAULT_CONTEXT_WINDOW)
    }

    @Test
    fun givenADatedIdInTheCatalog_whenTheAliasIsResolved_thenItsWindowIsUsed() {
        val catalog = mapOf("claude-haiku-4-5-20251001" to ModelCapabilities(maxInputTokens = 200_000))

        val known = ClaudeModelTraits.lookup("claude-haiku-4-5", catalog)

        assertEquals(200_000, ClaudeModelTraits.contextWindow("claude-haiku-4-5", known))
    }
}
