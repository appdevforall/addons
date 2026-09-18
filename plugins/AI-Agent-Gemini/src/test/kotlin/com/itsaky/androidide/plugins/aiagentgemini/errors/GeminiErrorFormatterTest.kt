package com.itsaky.androidide.plugins.aiagentgemini.errors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The invariant under test: **no [GeminiFailure] may carry a JSON body**, because whatever it
 * carries is substituted into a translated string and shown in the chat transcript. Assertions are
 * on the classification, not on English wording, which is free to change per locale.
 */
class GeminiErrorFormatterTest {

    private val model = "gemini-2.5-flash"

    /** Verbatim from the bug report: a retired model on a newly created key. */
    private val retiredModelFailure = IOException(
        """
        Gemini HTTP 404: {
          "error": {
            "code": 404,
            "message": "This model models/gemini-2.5-flash is no longer available to new users. Please update your code to use a newer model for the latest features and improvements.",
            "status": "NOT_FOUND"
          }
        }
        """.trimIndent()
    )

    private fun classify(error: Throwable) = GeminiErrorFormatter.classify(error, model)

    /** The reason is the only free text that reaches the UI, so it carries the invariant. */
    private fun assertReasonIsSafe(reason: String?) {
        if (reason == null) return
        assertFalse("leaked a JSON body: $reason", reason.contains('{') || reason.contains('}'))
        assertFalse("spans multiple lines: $reason", reason.contains('\n'))
        assertTrue("too long for a message: ${reason.length}", reason.length <= 160)
    }

    @Test
    fun givenTheReported404_whenClassified_thenReportsModelUnavailableNamingTheModel() {
        assertEquals(GeminiFailure.ModelUnavailable(model), classify(retiredModelFailure))
    }

    @Test
    fun givenTheReported404_whenClassified_thenCarriesNoDeveloperFacingWording() {
        // "Please update your code" is for a developer, not someone typing in a chat box.
        val failure = classify(retiredModelFailure) as GeminiFailure.ModelUnavailable

        assertEquals(model, failure.modelName)
    }

    @Test
    fun givenTheReported404_whenParsed_thenTheBodyIsStillAvailableForDiagnostics() {
        val parsed = GeminiErrorFormatter.parse(retiredModelFailure.message)

        assertEquals(404, parsed.httpStatus)
        assertEquals("NOT_FOUND", parsed.apiStatus)
        val apiMessage = parsed.apiMessage
        assertNotNull(apiMessage)
        assertTrue(apiMessage!!.startsWith("This model models/gemini-2.5-flash"))
        // Collapsed to one line so it can never break a layout if it is ever shown.
        assertFalse(apiMessage.contains('\n'))
    }

    @Test
    fun givenANotFoundStatusWithNoHttpPrefix_whenClassified_thenStillReportsModelUnavailable() {
        val cause = IOException("""{"error":{"status":"NOT_FOUND","message":"nope"}}""")

        assertEquals(GeminiFailure.ModelUnavailable(model), classify(cause))
    }

    @Test
    fun givenHttp429_whenClassified_thenReportsQuotaExceededRatherThanAKeyProblem() {
        val cause = IOException(
            """Gemini HTTP 429: {"error":{"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded"}}"""
        )

        assertEquals(GeminiFailure.QuotaExceeded, classify(cause))
    }

    @Test
    fun givenHttp403_whenClassified_thenReportsKeyRefused() {
        val cause = IOException(
            """Gemini HTTP 403: {"error":{"status":"PERMISSION_DENIED","message":"denied"}}"""
        )

        assertEquals(GeminiFailure.KeyRefused, classify(cause))
    }

    @Test
    fun givenHttp401_whenClassified_thenReportsKeyRefused() {
        assertEquals(GeminiFailure.KeyRefused, classify(IOException("Gemini HTTP 401: {}")))
    }

    @Test
    fun givenHttp400MentioningTheApiKey_whenClassified_thenReportsKeyInvalid() {
        val cause = IOException(
            """Gemini HTTP 400: {"error":{"status":"INVALID_ARGUMENT","message":"API key not valid. Please pass a valid API key."}}"""
        )

        assertEquals(GeminiFailure.KeyInvalid, classify(cause))
    }

    @Test
    fun givenHttp400NotAboutTheKey_whenClassified_thenReportsRequestRejectedWithAShortReason() {
        val cause = IOException(
            """Gemini HTTP 400: {"error":{"status":"INVALID_ARGUMENT","message":"Request contains an invalid argument."}}"""
        )

        val failure = classify(cause) as GeminiFailure.RequestRejected
        assertEquals("Request contains an invalid argument.", failure.reason)
        assertReasonIsSafe(failure.reason)
    }

    @Test
    fun givenAnOverlongApiReason_whenClassified_thenTheReasonIsDroppedInsteadOfShown() {
        val cause = IOException("""Gemini HTTP 400: {"error":{"message":"${"x".repeat(500)}"}}""")

        assertEquals(GeminiFailure.RequestRejected(null), classify(cause))
    }

    @Test
    fun givenHttp503_whenClassified_thenReportsServiceUnavailableCarryingTheStatus() {
        assertEquals(
            GeminiFailure.ServiceUnavailable(503),
            classify(IOException("""Gemini HTTP 503: {"error":{}}"""))
        )
    }

    @Test
    fun givenAnUnmappedHttpStatus_whenClassified_thenKeepsTheStatusAndASafeReason() {
        val cause = IOException("""Gemini HTTP 418: {"error":{"message":"I am a teapot"}}""")

        val failure = classify(cause) as GeminiFailure.Unexpected
        assertEquals(418, failure.httpStatus)
        assertEquals("I am a teapot", failure.reason)
        assertReasonIsSafe(failure.reason)
    }

    @Test
    fun givenAnIoFailureWithNoHttpStatus_whenClassified_thenReportsUnreachable() {
        assertEquals(
            GeminiFailure.Unreachable,
            classify(UnknownHostException("generativelanguage.googleapis.com"))
        )
        assertEquals(GeminiFailure.Unreachable, classify(SocketTimeoutException("timeout")))
    }

    @Test
    fun givenANonIoFailureWithAShortMessage_whenClassified_thenKeepsItAsTheReason() {
        val failure = classify(IllegalStateException("backend was closed")) as GeminiFailure.Failed

        assertEquals("backend was closed", failure.reason)
        assertReasonIsSafe(failure.reason)
    }

    @Test
    fun givenANonIoFailureWrappingAnErrorBody_whenClassified_thenKeepsOnlyTheParsedReason() {
        val cause = RuntimeException("""weird {"error":{"message":"stream closed"}}""")

        val failure = classify(cause) as GeminiFailure.Failed
        assertEquals("stream closed", failure.reason)
        assertReasonIsSafe(failure.reason)
    }

    @Test
    fun givenAnUnparseableBody_whenClassified_thenNoReasonIsCarried() {
        // The fallback branch must not become a new JSON leak: the raw text has a brace.
        val cause = RuntimeException("""broke at {"unexpected": [1, 2""")

        assertEquals(GeminiFailure.Failed(null), classify(cause))
    }

    @Test
    fun givenAFailureWithNoMessage_whenParsedAndClassified_thenNothingIsInferred() {
        val parsed = GeminiErrorFormatter.parse(null)

        assertNull(parsed.httpStatus)
        assertNull(parsed.apiStatus)
        assertNull(parsed.apiMessage)
        assertEquals(GeminiFailure.Failed(null), classify(RuntimeException()))
    }

    @Test
    fun givenATruncatedOrNonJsonBody_whenClassified_thenTheHttpStatusStillDecides() {
        val truncated = IOException("""Gemini HTTP 500: {"error":{"message":"cut off""")
        val html = IOException("Gemini HTTP 502: <html><body>Bad Gateway</body></html>")

        assertEquals(GeminiFailure.ServiceUnavailable(500), classify(truncated))
        assertEquals(GeminiFailure.ServiceUnavailable(502), classify(html))
    }
}
