package com.itsaky.androidide.plugins.aiagentopenai.errors

import com.itsaky.androidide.plugins.services.LlmInferenceService.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** The kind AI Core is told with each failure, which is what lets it compact rather than give up. */
class FailureKindTest {

    @Test
    fun givenAContextOverflow_whenMapped_thenTheKindIsContextTooLong() {
        assertEquals(FailureKind.CONTEXT_TOO_LONG, OpenAiFailure.ContextTooLong.failureKind)
    }

    @Test
    fun givenAFailureFixedInSettings_whenMapped_thenTheKindIsConfiguration() {
        val settings = listOf(
            OpenAiFailure.ModelUnavailable("gpt-5"),
            OpenAiFailure.BillingRequired,
            OpenAiFailure.KeyRefused,
            OpenAiFailure.KeyMissing,
            OpenAiFailure.KeyForbidden,
        )
        settings.forEach { assertEquals(it.toString(), FailureKind.CONFIGURATION, it.failureKind) }
    }

    @Test
    fun givenAnyOtherFailure_whenMapped_thenTheKindIsOther() {
        val others = listOf(
            OpenAiFailure.QuotaExceeded,
            OpenAiFailure.RequestRejected("bad"),
            OpenAiFailure.ServiceUnavailable(503),
            OpenAiFailure.Unexpected(418, null),
            OpenAiFailure.ServerNotRunning,
            OpenAiFailure.Unreachable,
            OpenAiFailure.TimedOut(60),
            OpenAiFailure.EmptyReply(0),
            OpenAiFailure.ReasoningOnly,
            OpenAiFailure.TruncatedBeforeReply,
            OpenAiFailure.Failed(null),
        )
        others.forEach { assertEquals(it.toString(), FailureKind.OTHER, it.failureKind) }
    }

    @Test
    fun givenAnOverflowReportedMidStream_whenClassified_thenTheKindIsContextTooLong() {
        val error = IOException(
            "OpenAI stream error: This model's maximum context length is 8192 tokens."
        )
        val failure = OpenAiErrorFormatter.classify(error, "qwen", hasApiKey = false, isOpenAiHost = false)
        assertEquals(FailureKind.CONTEXT_TOO_LONG, failure.failureKind)
    }

    @Test
    fun givenAnUnrelatedStreamError_whenClassified_thenTheKindIsOther() {
        val error = IOException("OpenAI stream error: upstream provider failed")
        val failure = OpenAiErrorFormatter.classify(error, "qwen", hasApiKey = false, isOpenAiHost = false)
        assertEquals(FailureKind.OTHER, failure.failureKind)
    }
}
