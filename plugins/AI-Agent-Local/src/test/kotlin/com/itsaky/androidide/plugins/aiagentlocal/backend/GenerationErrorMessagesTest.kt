package com.itsaky.androidide.plugins.aiagentlocal.backend

import android.content.Context
import com.itsaky.androidide.plugins.aiagentlocal.R
import com.itsaky.androidide.plugins.services.LlmInferenceService.FailureKind
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationErrorMessagesTest {

    private val context = mockk<Context> {
        every { getString(R.string.local_error_context_too_long) } returns CONTEXT_TOO_LONG
    }
    private val messages = GenerationErrorMessages(context)

    @Test
    fun givenNativeOverflow_whenDescribed_thenContextTooLongWithoutErrorPrefix() {
        val error = IllegalArgumentException("Prompt is too long for the model's context size.")

        assertEquals(CONTEXT_TOO_LONG, messages.describe(error).message)
    }

    @Test
    fun givenNativeOverflow_whenDescribed_thenKindIsContextTooLong() {
        val error = IllegalArgumentException("Prompt is too long for the model's context size.")

        assertEquals(FailureKind.CONTEXT_TOO_LONG, messages.describe(error).kind)
    }

    @Test
    fun givenOverflowTextOnOtherType_whenDescribed_thenKindIsOther() {
        val error = IllegalStateException("Prompt is too long for the model's context size.")

        assertEquals(GenerationError(error.message!!, FailureKind.OTHER), messages.describe(error))
    }

    @Test
    fun givenOtherFailure_whenDescribed_thenRawMessageWithoutErrorPrefix() {
        assertEquals(
            GenerationError("decode failed", FailureKind.OTHER),
            messages.describe(IllegalStateException("decode failed")),
        )
    }

    @Test
    fun givenFailureWithoutMessage_whenDescribed_thenExceptionName() {
        assertEquals("IllegalStateException", messages.describe(IllegalStateException()).message)
    }

    @Test
    fun givenOverflowTextOnOtherType_whenChecked_thenNotContextOverflow() {
        val error = IllegalStateException("Prompt is too long for the model's context size.")

        assertFalse(GenerationErrorMessages.isContextOverflow(error))
    }

    @Test
    fun givenNativeOverflow_whenChecked_thenContextOverflow() {
        val error = IllegalArgumentException("Prompt is too long for the model's context size.")

        assertTrue(GenerationErrorMessages.isContextOverflow(error))
    }

    private companion object {
        const val CONTEXT_TOO_LONG = "This conversation is longer than the model can read."
    }
}
