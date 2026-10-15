package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.services.LlmInferenceService.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RunFailure]. Reported on Gemini: a failed run read "Error: Empty response from
 * Gemini API", and a failure with no message read "Error: null".
 */
class RunFailureTest {

    @Test
    fun givenBackendFailure_whenDescribed_thenShowsTheBackendSentenceAsWritten() {
        val text = RunFailure.describe(BackendFailure("Gemini returned an empty reply. Try again."), FALLBACK)

        assertEquals("Gemini returned an empty reply. Try again.", text)
    }

    @Test
    fun givenBlankBackendFailure_whenDescribed_thenShowsTheFallback() {
        assertEquals(FALLBACK, RunFailure.describe(BackendFailure("  "), FALLBACK))
    }

    @Test
    fun givenExceptionWithoutMessage_whenDescribed_thenShowsTheFallbackNotNull() {
        assertEquals(FALLBACK, RunFailure.describe(IllegalStateException(), FALLBACK))
    }

    @Test
    fun givenInternalException_whenDescribed_thenHidesItsTechnicalMessage() {
        val text = RunFailure.describe(IllegalArgumentException("Unknown ViewModel class"), FALLBACK)

        assertEquals(FALLBACK, text)
    }

    @Test
    fun givenConfigurationFailure_whenAskedWhatFixesIt_thenSettings() {
        assertTrue(RunFailure.isFixedInSettings(BackendFailure("Claude refused your API key.", FailureKind.CONFIGURATION)))
    }

    @Test
    fun givenUntypedBackendFailure_whenAskedWhatFixesIt_thenNotSettings() {
        assertFalse(RunFailure.isFixedInSettings(BackendFailure("Gemini returned an empty reply.")))
    }

    @Test
    fun givenInternalException_whenAskedWhatFixesIt_thenNotSettings() {
        assertFalse(RunFailure.isFixedInSettings(IllegalStateException()))
    }

    private companion object {
        const val FALLBACK = "Something went wrong while talking to the model. Tap Retry to try again."
    }
}
