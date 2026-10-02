package com.itsaky.androidide.plugins.aiagentclaude.errors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * Failure classification. Each branch is what the user is told, and the class exists so a raw JSON
 * error body never reaches the chat transcript. Bodies are the Messages API's error shape:
 * `{"type":"error","error":{"type":...,"message":...}}`.
 */
class ClaudeErrorFormatterTest {

    private fun body(type: String, message: String) =
        """{"type":"error","error":{"type":"$type","message":"$message"}}"""

    private fun http(status: Int, type: String, message: String) =
        ClaudeHttpException(status, body(type, message))

    private fun classify(
        error: Throwable,
        model: String = "claude-opus-5-5",
        hasApiKey: Boolean = true,
    ): ClaudeFailure = ClaudeErrorFormatter.classify(error, model, hasApiKey)

    @Test
    fun givenA404_whenClassified_thenTheModelIsNamedAsUnavailable() {
        val failure = classify(http(404, "not_found_error", "model: claude-nope"), model = "claude-nope")
        assertEquals(ClaudeFailure.ModelUnavailable("claude-nope"), failure)
    }

    @Test
    fun givenA429_whenClassified_thenItIsQuotaExceeded() {
        assertEquals(
            ClaudeFailure.QuotaExceeded,
            classify(http(429, "rate_limit_error", "Number of request tokens has exceeded your rate limit"))
        )
    }

    @Test
    fun givenA400AboutTheCreditBalance_whenClassified_thenItIsBillingRequired() {
        // The API reports an empty balance as a 400, not a status of its own, and "the request
        // was rejected" would send the user hunting for a fault in their prompt.
        val error = http(
            400,
            "invalid_request_error",
            "Your credit balance is too low to access the Anthropic API.",
        )
        assertEquals(ClaudeFailure.BillingRequired, classify(error))
    }

    @Test
    fun givenA402_whenClassified_thenItIsBillingRequired() {
        assertEquals(ClaudeFailure.BillingRequired, classify(http(402, "billing_error", "payment required")))
    }

    @Test
    fun givenA401WithAKeySent_whenClassified_thenTheKeyWasRefused() {
        val error = http(401, "authentication_error", "invalid x-api-key")
        assertEquals(ClaudeFailure.KeyRefused, classify(error, hasApiKey = true))
    }

    @Test
    fun givenA401WithNoKeySent_whenClassified_thenTheKeyIsReportedMissing() {
        // Telling the user the key is "wrong" would send them off to check a key that does not exist.
        val error = http(401, "authentication_error", "x-api-key header is required")
        assertEquals(ClaudeFailure.KeyMissing, classify(error, hasApiKey = false))
    }

    @Test
    fun givenA403_whenClassified_thenTheKeyIsForbidden() {
        assertEquals(ClaudeFailure.KeyForbidden, classify(http(403, "permission_error", "no access")))
    }

    @Test
    fun givenAKeyWithNoWorkspace_whenClassified_thenTheKeyNeedsAWorkspace() {
        // The API's real wording, captured on device (ADFA-6311). Reported as a bad request it
        // would lose its reason, which is longer than the cap on echoed text.
        val error = http(400, "invalid_request_error", "This API key is not scoped to a workspace, so this request must include the anthropic-workspace-id header with the ID of the workspace to use. Add the header, or use an API key that is scoped to a workspace.")
        assertEquals(ClaudeFailure.KeyNeedsWorkspace, classify(error))
    }

    @Test
    fun givenA413_whenClassified_thenTheRequestIsTooLarge() {
        assertEquals(ClaudeFailure.RequestTooLarge, classify(http(413, "request_too_large", "too big")))
    }

    @Test
    fun givenA400_whenClassified_thenTheRequestWasRejectedWithTheApisReason() {
        val failure = classify(http(400, "invalid_request_error", "messages: text content blocks must be non-empty"))
        assertEquals(
            ClaudeFailure.RequestRejected("messages: text content blocks must be non-empty"),
            failure
        )
    }

    @Test
    fun givenAnOverload_whenClassified_thenTheServiceIsUnavailable() {
        assertEquals(
            ClaudeFailure.ServiceUnavailable(529),
            classify(http(529, "overloaded_error", "Overloaded"))
        )
    }

    @Test
    fun givenAStreamErrorEvent_whenItsTypeIsMappedToAStatus_thenItClassifiesLikeTheHttpFailure() {
        // An error that arrives inside a 200 stream has no status of its own; it has to land in
        // the same branch as the same failure arriving on the status line.
        val status = ClaudeHttpException.statusForStreamError("overloaded_error")
        assertEquals(
            ClaudeFailure.ServiceUnavailable(529),
            classify(ClaudeHttpException(status, body("overloaded_error", "Overloaded")))
        )
    }

    @Test
    fun givenAnUnhandledStatus_whenClassified_thenItIsUnexpected() {
        assertEquals(ClaudeFailure.Unexpected(418, "teapot"), classify(http(418, "api_error", "teapot")))
    }

    @Test
    fun givenAStreamThatWentSilent_whenClassified_thenItIsStalledNotUnreachable() {
        // The status line said 2xx, so "check your internet connection" would be wrong advice.
        val error = ClaudeStreamStalledException(java.net.SocketTimeoutException("Read timed out"))
        assertEquals(ClaudeFailure.Stalled, classify(error))
    }

    @Test
    fun givenNoAnswer_whenClassified_thenItIsUnreachable() {
        assertEquals(ClaudeFailure.Unreachable, classify(IOException("Unable to resolve host")))
    }

    @Test
    fun givenANonIoFailure_whenClassified_thenItIsAGenericFailure() {
        assertEquals(
            ClaudeFailure.Failed("backend closed"),
            classify(IllegalStateException("backend closed"))
        )
    }

    @Test
    fun givenAStatusOnlyInTheMessage_whenClassified_thenItIsStillRead() {
        // A failure that reached the handler without the transport's field still carries its text.
        assertEquals(ClaudeFailure.KeyForbidden, classify(IOException("Claude HTTP 403: {}")))
    }

    @Test
    fun givenAJsonBodyWithNoErrorObject_whenAReasonIsEchoed_thenNoBraceIsCarriedOnward() {
        // The whole point of this class: a raw body must never reach the transcript.
        val failure = classify(ClaudeHttpException(400, """{"unexpected":"shape"}""")) as ClaudeFailure.RequestRejected
        assertNull(failure.reason)
    }

    @Test
    fun givenAnOverlongApiMessage_whenAReasonIsEchoed_thenItIsDropped() {
        val failure = classify(http(400, "invalid_request_error", "x".repeat(400))) as ClaudeFailure.RequestRejected
        assertNull(failure.reason)
    }

    @Test
    fun givenAMultilineApiMessage_whenParsed_thenItIsCollapsedToOneLine() {
        val message = "Claude HTTP 400: {\"error\":{\"message\":\"first\\n\\n  second\"}}"
        assertEquals("first second", ClaudeErrorFormatter.parse(message).apiMessage)
    }

    @Test
    fun givenNoJsonBody_whenParsed_thenOnlyTheStatusIsRead() {
        val parsed = ClaudeErrorFormatter.parse("Claude HTTP 502: <html>Bad Gateway</html>")
        assertEquals(502, parsed.httpStatus)
        assertNull(parsed.apiMessage)
    }

    @Test
    fun givenNoMessageAtAll_whenParsed_thenEveryFieldIsNull() {
        val parsed = ClaudeErrorFormatter.parse(null)
        assertNull(parsed.httpStatus)
        assertNull(parsed.apiType)
        assertNull(parsed.apiMessage)
    }
}
