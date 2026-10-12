package com.itsaky.androidide.plugins.aiagentgemini.errors

import com.itsaky.androidide.plugins.services.LlmInferenceService.FailureKind
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [failureKind], which is how AI Core learns a turn overflowed the context window
 * without matching the sentence it is shown.
 */
class GeminiFailureKindTest {

    @Test
    fun givenGeminiOverflowBody_whenClassifiedAndMapped_thenKindIsContextTooLong() {
        val cause = IOException(
            """Gemini HTTP 400: {"error":{"code":400,"status":"INVALID_ARGUMENT","message":"The input token count (1200000) exceeds the maximum number of tokens allowed (1048576)."}}"""
        )

        val failure = GeminiErrorFormatter.classify(cause, "gemini-2.5-flash")

        assertEquals(FailureKind.CONTEXT_TOO_LONG, failure.failureKind)
    }

    @Test
    fun givenAnyOtherFailure_whenMapped_thenKindIsOther() {
        val others = listOf(
            GeminiFailure.ModelUnavailable("gemini-2.5-flash"),
            GeminiFailure.QuotaExceeded,
            GeminiFailure.KeyRefused,
            GeminiFailure.KeyInvalid,
            GeminiFailure.RequestRejected("too long"),
            GeminiFailure.ServiceUnavailable(503),
            GeminiFailure.Unexpected(418, null),
            GeminiFailure.Unreachable,
            // The output cap, not the input window: a shorter history would not have helped.
            GeminiFailure.ReplyTruncated,
            GeminiFailure.NoReply(null),
            GeminiFailure.Failed(null),
        )

        others.forEach { failure -> assertEquals(failure.toString(), FailureKind.OTHER, failure.failureKind) }
    }
}
