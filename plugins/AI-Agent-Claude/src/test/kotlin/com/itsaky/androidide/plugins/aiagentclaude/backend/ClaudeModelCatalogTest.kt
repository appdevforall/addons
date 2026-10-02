package com.itsaky.androidide.plugins.aiagentclaude.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeModelCatalogTest {

    @Test
    fun givenAListing_whenRead_thenTheIdsComeBackInTheApisOrder() {
        val body = """
            {"data":[
              {"type":"model","id":"claude-opus-5-5","display_name":"Claude Opus 5.5"},
              {"type":"model","id":"claude-haiku-4-5","display_name":"Claude Haiku 4.5"}
            ],"has_more":false}
        """.trimIndent()

        assertEquals(listOf("claude-opus-5-5", "claude-haiku-4-5"), ClaudeModelCatalog.ids(body))
    }

    @Test
    fun givenBlankAndDuplicateIds_whenRead_thenTheyAreDropped() {
        val body = """{"data":[{"id":" "},{"id":"claude-opus-5-5"},{"id":"claude-opus-5-5"},{}]}"""

        assertEquals(listOf("claude-opus-5-5"), ClaudeModelCatalog.ids(body))
    }

    @Test
    fun givenCapabilities_whenRead_thenTheCapAndFlagsAreKept() {
        val body = """
            {"data":[
              {"id":"claude-opus-4-8","max_tokens":128000,"capabilities":{
                "thinking":{"supported":true,"types":{"enabled":{"supported":false},"adaptive":{"supported":true}}},
                "effort":{"supported":true}}},
              {"id":"claude-haiku-4-5-20251001","max_tokens":64000,"capabilities":{
                "thinking":{"supported":true,"types":{"enabled":{"supported":true},"adaptive":{"supported":false}}},
                "effort":{"supported":false}}},
              {"id":"claude-bare"}
            ]}
        """.trimIndent()

        val entries = ClaudeModelCatalog.entries(body).associate { it.id to it.capabilities }
        assertEquals(ModelCapabilities(128_000, adaptiveThinking = true, effort = true), entries["claude-opus-4-8"])
        assertEquals(ModelCapabilities(64_000, adaptiveThinking = false, effort = false), entries["claude-haiku-4-5-20251001"])
        // Nothing listed means nothing known, so the static rules decide, not a guessed false.
        assertEquals(ModelCapabilities(), entries["claude-bare"])
    }

    @Test
    fun givenStoredCapabilities_whenRoundTripped_thenTheyComeBackUnchanged() {
        val entries = listOf(
            ClaudeModelCatalog.Entry("claude-opus-4-8", ModelCapabilities(128_000, true, true)),
            ClaudeModelCatalog.Entry("claude-haiku-4-5-20251001", ModelCapabilities(64_000, false, null)),
        )

        val restored = ClaudeModelCatalog.decodeCapabilities(ClaudeModelCatalog.encodeCapabilities(entries))
        assertEquals(entries.associate { it.id to it.capabilities }, restored)
        assertTrue(ClaudeModelCatalog.decodeCapabilities(null).isEmpty())
        assertTrue(ClaudeModelCatalog.decodeCapabilities("garbage\twith\tno\tsense\n\t\t").let { it.size <= 1 })
    }

    @Test
    fun givenABodyThatIsNotAListing_whenRead_thenNothingIsOffered() {
        assertTrue(ClaudeModelCatalog.ids("<html>captive portal</html>").isEmpty())
        assertTrue(ClaudeModelCatalog.ids("""{"error":{}}""").isEmpty())
    }
}
