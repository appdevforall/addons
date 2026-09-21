package com.itsaky.androidide.plugins.aiagentopenai.errors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Failure classification. Each branch is what the user is told, and the class exists so a raw JSON
 * error body never reaches the chat transcript.
 */
class OpenAiErrorFormatterTest {

    private fun classify(
        message: String?,
        model: String = "gpt-5",
        hasApiKey: Boolean = true,
        isOpenAiHost: Boolean = true,
        error: Throwable = IOException(message),
    ): OpenAiFailure = OpenAiErrorFormatter.classify(error, model, hasApiKey, isOpenAiHost)

    @Test
    fun givenA404_whenClassified_thenTheModelIsNamedAsUnavailable() {
        val failure = classify("OpenAI HTTP 404: {\"error\":{\"message\":\"no such model\"}}")
        assertEquals(OpenAiFailure.ModelUnavailable("gpt-5"), failure)
    }

    @Test
    fun givenAModelNotFoundCode_whenClassified_thenTheModelIsNamedAsUnavailable() {
        val body = """OpenAI HTTP 400: {"error":{"code":"model_not_found","message":"nope"}}"""
        assertTrue(classify(body) is OpenAiFailure.ModelUnavailable)
    }

    @Test
    fun givenA429AboutRate_whenClassified_thenItIsQuotaExceeded() {
        val body = """OpenAI HTTP 429: {"error":{"message":"Rate limit reached for requests"}}"""
        assertEquals(OpenAiFailure.QuotaExceeded, classify(body))
    }

    @Test
    fun givenA429AboutMoney_whenClassified_thenItIsBillingRequired() {
        // The distinction matters: "wait a moment" is useless advice for a spent balance.
        val body = """OpenAI HTTP 429: {"error":{"message":"You exceeded your current quota,""" +
            """ please check your plan and billing details.","code":"insufficient_quota"}}"""
        assertEquals(OpenAiFailure.BillingRequired, classify(body))
    }

    @Test
    fun givenA401WithAKeySent_whenClassified_thenTheKeyWasRefused() {
        val body = """OpenAI HTTP 401: {"error":{"code":"invalid_api_key","message":"bad key"}}"""
        assertEquals(OpenAiFailure.KeyRefused, classify(body, hasApiKey = true))
    }

    @Test
    fun givenA401WithNoKeySent_whenClassified_thenTheKeyIsReportedMissing() {
        // A server that needs a credential the user did not configure: telling them the key is
        // "wrong" would send them off to check a key that does not exist.
        val body = """OpenAI HTTP 401: {"error":{"message":"missing bearer token"}}"""
        assertEquals(OpenAiFailure.KeyMissing, classify(body, hasApiKey = false))
    }

    @Test
    fun givenA403_whenClassified_thenTheKeyIsForbidden() {
        assertEquals(OpenAiFailure.KeyForbidden, classify("OpenAI HTTP 403: {}"))
    }

    @Test
    fun givenA400_whenClassified_thenTheRequestWasRejectedWithTheServersReason() {
        val body = """OpenAI HTTP 400: {"error":{"message":"messages must not be empty"}}"""
        val failure = classify(body)
        assertEquals(OpenAiFailure.RequestRejected("messages must not be empty"), failure)
    }

    @Test
    fun givenA500_whenClassified_thenTheServiceIsUnavailable() {
        assertEquals(
            OpenAiFailure.ServiceUnavailable(503),
            classify("OpenAI HTTP 503: {\"error\":{\"message\":\"overloaded\"}}")
        )
    }

    @Test
    fun givenAnUnhandledStatus_whenClassified_thenItIsUnexpected() {
        val failure = classify("OpenAI HTTP 418: {\"error\":{\"message\":\"teapot\"}}")
        assertEquals(OpenAiFailure.Unexpected(418, "teapot"), failure)
    }

    @Test
    fun givenNoAnswerFromALocalServer_whenClassified_thenTheServerIsReportedNotRunning() {
        // The most likely failure for a LAN server, and "check your internet" is wrong advice.
        val failure = classify("Connection refused", isOpenAiHost = false)
        assertEquals(OpenAiFailure.ServerNotRunning, failure)
    }

    @Test
    fun givenNoAnswerFromOpenAi_whenClassified_thenItIsUnreachable() {
        assertEquals(OpenAiFailure.Unreachable, classify("Unable to resolve host", isOpenAiHost = true))
    }

    @Test
    fun givenANonIoFailure_whenClassified_thenItIsAGenericFailure() {
        val failure = OpenAiErrorFormatter.classify(
            IllegalStateException("backend closed"), "gpt-5", true, true
        )
        assertEquals(OpenAiFailure.Failed("backend closed"), failure)
    }

    @Test
    fun givenAJsonBodyInTheMessage_whenAReasonIsEchoed_thenNoBraceIsCarriedOnward() {
        // The whole point of this class: a raw body must never reach the transcript.
        val body = """OpenAI HTTP 400: {"unexpected":"shape","with":{"nesting":true}}"""
        val failure = classify(body) as OpenAiFailure.RequestRejected
        assertNull(failure.reason)
    }

    @Test
    fun givenAnOverlongServerMessage_whenAReasonIsEchoed_thenItIsDropped() {
        val long = "x".repeat(400)
        val body = """OpenAI HTTP 400: {"error":{"message":"$long"}}"""
        val failure = classify(body) as OpenAiFailure.RequestRejected
        assertNull(failure.reason)
    }

    @Test
    fun givenAMultilineServerMessage_whenParsed_thenItIsCollapsedToOneLine() {
        val body = "OpenAI HTTP 400: {\"error\":{\"message\":\"first\\n\\n  second\"}}"
        assertEquals("first second", OpenAiErrorFormatter.parse(body).apiMessage)
    }

    @Test
    fun givenNoJsonBody_whenParsed_thenOnlyTheStatusIsRead() {
        val parsed = OpenAiErrorFormatter.parse("OpenAI HTTP 502: <html>Bad Gateway</html>")
        assertEquals(502, parsed.httpStatus)
        assertNull(parsed.apiMessage)
    }

    @Test
    fun givenNoMessageAtAll_whenParsed_thenEveryFieldIsNull() {
        val parsed = OpenAiErrorFormatter.parse(null)
        assertNull(parsed.httpStatus)
        assertNull(parsed.apiCode)
        assertNull(parsed.apiType)
        assertNull(parsed.apiMessage)
    }
}
