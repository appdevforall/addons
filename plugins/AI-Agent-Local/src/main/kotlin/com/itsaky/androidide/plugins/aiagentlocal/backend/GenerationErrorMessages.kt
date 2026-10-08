package com.itsaky.androidide.plugins.aiagentlocal.backend

import android.content.Context
import com.itsaky.androidide.plugins.aiagentlocal.R

/**
 * Renders a generation failure as the text sent through `onError`, without an "Error: " prefix
 * because AI Core adds its own. A context overflow gets its own actionable message.
 */
internal class GenerationErrorMessages(private val context: Context) {

    /**
     * @param error the failure thrown while prefilling or decoding
     * @return display-ready text for the chat's error row
     */
    fun describe(error: Throwable): String = when {
        isContextOverflow(error) -> context.getString(R.string.local_error_context_too_long)
        else -> error.message ?: error.javaClass.simpleName
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
