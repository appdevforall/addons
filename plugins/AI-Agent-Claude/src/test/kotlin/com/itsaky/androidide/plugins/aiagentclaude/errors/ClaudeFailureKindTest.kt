package com.itsaky.androidide.plugins.aiagentclaude.errors

import com.itsaky.androidide.plugins.services.LlmInferenceService.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [failureKind], which is how AI Core learns a turn overflowed the context window
 * without matching the sentence it is shown.
 */
class ClaudeFailureKindTest {

    @Test
    fun givenPromptTooLong400_whenClassifiedAndMapped_thenKindIsContextTooLong() {
        val error = ClaudeHttpException(
            400,
            """{"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 1357094 tokens > 1000000 maximum"}}""",
        )

        val failure = ClaudeErrorFormatter.classify(error, "claude-opus-5-5", hasApiKey = true)

        assertEquals(FailureKind.CONTEXT_TOO_LONG, failure.failureKind)
    }

    @Test
    fun givenAnyOtherFailure_whenMapped_thenKindIsOther() {
        val others = listOf(
            ClaudeFailure.ModelUnavailable("claude-opus-5-5"),
            ClaudeFailure.QuotaExceeded,
            ClaudeFailure.BillingRequired,
            ClaudeFailure.KeyRefused,
            ClaudeFailure.KeyMissing,
            ClaudeFailure.KeyForbidden,
            ClaudeFailure.KeyNeedsWorkspace,
            // A 413 is the request's bytes, not its tokens: compacting the history is not the cure.
            ClaudeFailure.RequestTooLarge,
            ClaudeFailure.RequestRejected("bad field"),
            ClaudeFailure.ServiceUnavailable(529),
            ClaudeFailure.Unexpected(418, null),
            ClaudeFailure.Unreachable,
            ClaudeFailure.Stalled,
            ClaudeFailure.Refused,
            ClaudeFailure.EmptyReply(0),
            ClaudeFailure.ReasoningOnly,
            ClaudeFailure.TruncatedBeforeReply,
            ClaudeFailure.Failed(null),
        )

        others.forEach { failure -> assertEquals(failure.toString(), FailureKind.OTHER, failure.failureKind) }
    }
}
