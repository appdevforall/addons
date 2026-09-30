package com.itsaky.androidide.plugins.aiagentopenai.backend

import com.itsaky.androidide.plugins.aiagentopenai.errors.OpenAiErrorFormatter
import com.itsaky.androidide.plugins.aiagentopenai.errors.OpenAiFailure
import com.itsaky.androidide.plugins.aiagentopenai.errors.OpenAiReplyException
import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [OpenAiWebSearch], the Responses API search behind ai-core's web_search tool. */
class OpenAiWebSearchTest {

    @Test
    fun givenTheWebSearchExtraParam_whenChecking_thenASearchIsRequested() {
        val config = LlmConfig("openai").apply { extraParams = mapOf("web_search" to true) }

        assertTrue(OpenAiWebSearch.isRequested(config))
        assertFalse(OpenAiWebSearch.isRequested(LlmConfig("openai")))
    }

    @Test
    fun givenAQuery_whenBuildingTheBody_thenItDeclaresWebSearchAndSendsNoSamplingParameters() {
        // Reasoning models refuse temperature and can spend a token cap before answering.
        val body = OpenAiWebSearch.body("gpt-5", "latest kotlin version", "Report concisely.")

        assertEquals("gpt-5", body.getString("model"))
        assertEquals("latest kotlin version", body.getString("input"))
        assertEquals("Report concisely.", body.getString("instructions"))
        assertEquals("web_search", body.getJSONArray("tools").getJSONObject(0).getString("type"))
        assertFalse(body.has("temperature"))
        assertFalse(body.has("max_output_tokens"))
    }

    @Test
    fun givenNoInstructions_whenBuildingTheBody_thenNoneAreSent() {
        assertFalse(OpenAiWebSearch.body("gpt-5", "q", null).has("instructions"))
    }

    @Test
    fun givenAReplyWithCitations_whenReadingTheAnswer_thenTheTextIsFollowedByItsSources() {
        val response = JSONObject(
            """
            {"output":[
              {"type":"web_search_call","status":"completed"},
              {"type":"message","content":[{"type":"output_text","text":"Kotlin 2.3 is current.",
                "annotations":[
                  {"type":"url_citation","url":"https://kotlinlang.org/docs/whatsnew23.html","title":"What's new"},
                  {"type":"url_citation","url":"https://kotlinlang.org/docs/whatsnew23.html","title":"What's new"}
                ]}]}
            ]}
            """.trimIndent()
        )

        assertEquals(
            "Kotlin 2.3 is current.\n\nSources:\n- What's new: https://kotlinlang.org/docs/whatsnew23.html",
            OpenAiWebSearch.answer(response),
        )
    }

    @Test
    fun givenAReplyWithNoMessage_whenReadingTheAnswer_thenItIsEmpty() {
        assertEquals("", OpenAiWebSearch.answer(JSONObject("""{"output":[{"type":"reasoning"}]}""")))
    }

    @Test
    fun givenAFailedReplyWithAnErrorObject_whenReadingTheAnswer_thenItThrowsRatherThanReturningEmpty() {
        val response = JSONObject(
            """{"status":"failed","error":{"code":"server_error","message":"Search backend down."},"output":[]}"""
        )

        assertThrows(OpenAiReplyException::class.java) { OpenAiWebSearch.answer(response) }
    }

    @Test
    fun givenANullErrorField_whenReadingTheAnswer_thenTheAnswerIsReturned() {
        // A successful Responses reply carries `"error": null`, which is not a failure.
        val response = JSONObject(
            """{"error":null,"output":[{"type":"message","content":[{"type":"output_text","text":"Hi."}]}]}"""
        )

        assertEquals("Hi.", OpenAiWebSearch.answer(response))
    }

    @Test
    fun givenAnErrorReplyWithAnInvalidKeyCode_whenClassified_thenTheKeyIsReportedAsRefused() {
        val error = replyError("""{"error":{"code":"invalid_api_key","message":"Incorrect API key."}}""")

        assertEquals(OpenAiFailure.KeyRefused, classify(error))
    }

    @Test
    fun givenAnErrorReplyWithAnInsufficientQuotaCode_whenClassified_thenBillingIsReported() {
        val error = replyError("""{"error":{"code":"insufficient_quota","message":"You exceeded your quota."}}""")

        assertEquals(OpenAiFailure.BillingRequired, classify(error))
    }

    @Test
    fun givenAnErrorReplyWithAnUnknownCode_whenClassified_thenTheServersReasonIsKept() {
        val error = replyError("""{"error":{"code":"server_error","message":"Search backend down."}}""")

        assertEquals(OpenAiFailure.Failed("Search backend down."), classify(error))
    }

    private fun replyError(body: String): OpenAiReplyException =
        assertThrows(OpenAiReplyException::class.java) { OpenAiWebSearch.answer(JSONObject(body)) }

    private fun classify(error: Throwable): OpenAiFailure =
        OpenAiErrorFormatter.classify(error, modelName = "gpt-5", hasApiKey = true, isOpenAiHost = true)
}
