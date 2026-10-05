package com.itsaky.androidide.plugins.aiagentgemini.backend

import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [GeminiWebSearch], the Google Search grounding behind ai-core's web_search tool. */
class GeminiWebSearchTest {

    @Test
    fun givenTheWebSearchExtraParam_whenChecking_thenASearchIsRequested() {
        val config = LlmConfig("gemini").apply { extraParams = mapOf("web_search" to true) }

        assertTrue(GeminiWebSearch.isRequested(config))
    }

    @Test
    fun givenOnlyTheGrammarExtraParam_whenChecking_thenNoSearchIsRequested() {
        val config = LlmConfig("gemini").apply { extraParams = mapOf("grammar" to "root ::= x") }

        assertFalse(GeminiWebSearch.isRequested(config))
        assertFalse(GeminiWebSearch.isRequested(LlmConfig("gemini")))
    }

    @Test
    fun givenARequestBody_whenDeclaringSearch_thenGoogleSearchIsTheOnlyTool() {
        // Alone, because Gemini 2.x refuses Google Search beside function declarations.
        val body = GeminiWebSearch.declareSearch(JSONObject().put("contents", "x"))

        val tools = body.getJSONArray("tools")
        assertEquals(1, tools.length())
        assertEquals(listOf("google_search"), tools.getJSONObject(0).keys().asSequence().toList())
    }

    @Test
    fun givenGroundingChunks_whenAddingSources_thenEachWebSourceIsListedOnce() {
        val response = JSONObject(
            """
            {"candidates":[{"groundingMetadata":{"groundingChunks":[
              {"web":{"uri":"https://a.example/1","title":"a.example"}},
              {"web":{"uri":"https://a.example/1","title":"a.example"}},
              {"web":{"uri":"https://b.example/2"}}
            ]}}]}
            """.trimIndent()
        )

        val text = GeminiWebSearch.withSources("Kotlin 2.3 is current.", response)

        assertEquals(
            "Kotlin 2.3 is current.\n\nSources:\n- a.example: https://a.example/1\n- https://b.example/2",
            text,
        )
    }

    @Test
    fun givenNoGroundingMetadata_whenAddingSources_thenTheTextIsUnchanged() {
        val response = JSONObject("""{"candidates":[{"content":{"parts":[{"text":"hi"}]}}]}""")

        assertEquals("hi", GeminiWebSearch.withSources("hi", response))
    }

    @Test
    fun givenResolvedLinks_whenAddingSources_thenEachIsListedByItsTarget() {
        val response = JSONObject(
            """
            {"candidates":[{"groundingMetadata":{"groundingChunks":[
              {"web":{"uri":"https://redirect.example/r1","title":"firebase.google.com"}},
              {"web":{"uri":"https://redirect.example/r2","title":"developer.android.com"}}
            ]}}]}
            """.trimIndent()
        )
        val resolved = mapOf("https://redirect.example/r1" to "https://firebase.google.com/docs/ai-logic")

        val text = GeminiWebSearch.withSources("Found.", response, resolved)

        assertEquals(
            "Found.\n\nSources:\n- firebase.google.com: https://firebase.google.com/docs/ai-logic\n" +
                "- developer.android.com: https://redirect.example/r2",
            text,
        )
        assertEquals(
            listOf("https://redirect.example/r1", "https://redirect.example/r2"),
            GeminiWebSearch.sourceUris(response),
        )
    }

    @Test
    fun givenAReportTheCapCutShort_whenAddingSources_thenItIsMarkedIncompleteBeforeTheSources() {
        val response = JSONObject(
            """{"candidates":[{"finishReason":"MAX_TOKENS","groundingMetadata":{"groundingChunks":[""" +
                """{"web":{"uri":"https://firebase.google.com/support/release-notes/android","title":"Notes"}}]}}]}""",
        )

        val text = GeminiWebSearch.withSources("The latest BoM is **v", response)

        assertTrue(text.startsWith("The latest BoM is **v\n\n${GeminiWebSearch.CUT_OFF_NOTE}\n\nSources:"))
    }

    @Test
    fun givenAReportThatEndedOnItsOwn_whenAddingSources_thenNoCutOffIsClaimed() {
        val response = JSONObject("""{"candidates":[{"finishReason":"STOP"}]}""")

        assertEquals("BoM 34.3.0.", GeminiWebSearch.withSources("BoM 34.3.0.", response))
    }
}
