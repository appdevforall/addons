package com.itsaky.androidide.plugins.aiagentopenai.backend

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where the context window comes from. A wrong answer here is either a conversation cut far shorter
 * than it needs to be, or one sent to a 4k local model as if it had 200k.
 */
class ContextWindowTest {

    private val openAi = "https://api.openai.com/v1"
    private val ollama = "http://localhost:11434/v1"

    @Test
    fun givenAUserSetting_whenResolved_thenItWinsOverEverything() {
        assertEquals(8_192, ContextWindow.resolve(8_192, 32_768, openAi, "gpt-5"))
    }

    @Test
    fun givenNoUserSetting_whenTheListingReportedOne_thenTheListedWindowIsUsed() {
        assertEquals(32_768, ContextWindow.resolve(null, 32_768, openAi, "gpt-5"))
    }

    @Test
    fun givenNothingListedOnOpenAi_whenResolved_thenThePublishedFigureIsUsed() {
        assertEquals(400_000, ContextWindow.resolve(null, null, openAi, "gpt-5"))
    }

    @Test
    fun givenNothingListedOnALocalServer_whenResolved_thenItIsUnknown() {
        assertEquals(ContextWindow.UNKNOWN, ContextWindow.resolve(null, null, ollama, "gpt-4o"))
    }

    @Test
    fun givenNonPositiveSources_whenResolved_thenTheyAreSkipped() {
        assertEquals(128_000, ContextWindow.resolve(0, -1, openAi, "gpt-4o"))
    }

    @Test
    fun givenOpenAiModelIds_whenLookedUp_thenTheLongestPrefixDecides() {
        assertEquals(128_000, ContextWindow.openAiWindow(openAi, "gpt-4o"))
        assertEquals(128_000, ContextWindow.openAiWindow(openAi, "gpt-4o-mini"))
        assertEquals(1_047_576, ContextWindow.openAiWindow(openAi, "gpt-4.1-mini"))
        assertEquals(200_000, ContextWindow.openAiWindow(openAi, "o3-mini"))
        assertEquals(200_000, ContextWindow.openAiWindow(openAi, "o4-mini"))
        assertEquals(200_000, ContextWindow.openAiWindow(openAi, "o1"))
        assertEquals(400_000, ContextWindow.openAiWindow(openAi, "GPT-5-mini"))
    }

    @Test
    fun givenAnUnknownOpenAiModel_whenLookedUp_thenTheDefaultIsUsed() {
        assertEquals(128_000, ContextWindow.openAiWindow(openAi, "gpt-3.5-turbo"))
    }

    @Test
    fun givenAnotherServer_whenLookedUp_thenTheOpenAiTableDoesNotApply() {
        assertNull(ContextWindow.openAiWindow("https://openrouter.ai/api/v1", "gpt-4o"))
    }

    @Test
    fun givenAnOpenRouterEntry_whenRead_thenItsContextLengthIsListed() {
        val entry = JSONObject("""{"id":"openai/gpt-4o","context_length":128000}""")
        assertEquals(128_000, ContextWindow.listedIn(entry))
    }

    @Test
    fun givenAnLmStudioEntry_whenRead_thenTheLoadedLengthWinsOverTheMaximum() {
        val entry = JSONObject(
            """{"id":"qwen","max_context_length":131072,"loaded_context_length":4096}"""
        )
        assertEquals(4_096, ContextWindow.listedIn(entry))
    }

    @Test
    fun givenOnlyAProviderLength_whenRead_thenItIsListed() {
        val entry = JSONObject("""{"id":"x","top_provider":{"context_length":65536}}""")
        assertEquals(65_536, ContextWindow.listedIn(entry))
    }

    @Test
    fun givenAPlainOpenAiEntry_whenRead_thenNothingIsListed() {
        assertNull(ContextWindow.listedIn(JSONObject("""{"id":"gpt-4o","object":"model"}""")))
    }

    @Test
    fun givenAListing_whenParsed_thenWindowsAreKeptPerModel() {
        val body = """{"data":[
            {"id":"a/chat","context_length":32768},
            {"id":"b/chat"},
            {"id":"text-embedding-3-small","context_length":8191}
        ]}"""
        val catalog = ModelCatalog.parse(body)
        assertEquals(mapOf("a/chat" to 32_768, "text-embedding-3-small" to 8_191), catalog.contextWindows)
        assertEquals(listOf("a/chat", "b/chat"), catalog.chat)
    }
}
