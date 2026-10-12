package com.itsaky.androidide.plugins.aiagentlocal.backend

import android.content.Context
import com.itsaky.androidide.plugins.aiagentlocal.R
import com.itsaky.androidide.plugins.services.LlmInferenceService.FailureKind

/**
 * A generation failure as reported to AI Core: the chat's error text and the typed reason
 * it compacts history on.
 */
internal data class GenerationError(val message: String, val kind: FailureKind)

/**
 * Renders a generation failure as the text sent through `onError`, without an "Error: " prefix
 * because AI Core adds its own. A context overflow gets its own actionable message.
 */
internal class GenerationErrorMessages(private val context: Context) {

    /**
     * @param error the failure thrown while prefilling or decoding
     * @return display-ready text for the chat's error row, with [FailureKind.CONTEXT_TOO_LONG] for an overflow
     */
    fun describe(error: Throwable): GenerationError = when {
        isContextOverflow(error) ->
            GenerationError(context.getString(R.string.local_error_context_too_long), FailureKind.CONTEXT_TOO_LONG)
        else -> GenerationError(error.message ?: error.javaClass.simpleName, FailureKind.OTHER)
    }

    companion object {
        // Native prefill's text (llama-android.cpp); it carries no token counts to parse.
        private const val CONTEXT_OVERFLOW_MESSAGE = "Prompt is too long for the model's context size"

        /** True when native prefill rejected the prompt because it exceeds the effective `n_ctx`. */
        fun isContextOverflow(error: Throwable): Boolean =
            error is IllegalArgumentException &&
                error.message?.startsWith(CONTEXT_OVERFLOW_MESSAGE) == true
    }
}
