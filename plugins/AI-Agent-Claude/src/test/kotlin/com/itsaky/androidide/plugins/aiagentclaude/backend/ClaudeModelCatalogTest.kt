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
    fun givenABodyThatIsNotAListing_whenRead_thenNothingIsOffered() {
        assertTrue(ClaudeModelCatalog.ids("<html>captive portal</html>").isEmpty())
        assertTrue(ClaudeModelCatalog.ids("""{"error":{}}""").isEmpty())
    }
}
