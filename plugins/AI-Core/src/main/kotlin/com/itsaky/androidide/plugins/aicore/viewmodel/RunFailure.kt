package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.services.LlmInferenceService.FailureKind

/**
 * A model turn the backend reported as failed, carrying the backend's own sentence: each backend
 * already words its failures for the user, in the IDE's language, so the chat shows them as written.
 *
 * @property kind what the backend says the failure is, which decides the button its row offers.
 */
internal open class BackendFailure(
    message: String,
    val kind: FailureKind = FailureKind.OTHER,
) : RuntimeException(message)

/** Decides what the chat shows for a run that ended in an exception. */
internal object RunFailure {

    /**
     * The sentence for the failed run's bubble.
     *
     * Anything other than a [BackendFailure] is our own bug or a transport surprise, whose message
     * ("null", a class name) means nothing to the user; it goes to the log, and [fallback] to the chat.
     *
     * @param error what ended the run.
     * @param fallback the generic sentence for a failure no backend described.
     * @return the text to show.
     */
    fun describe(error: Throwable, fallback: String): String =
        (error as? BackendFailure)?.message?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    /** Whether the backend said only its settings fix [error], so Retry would fail the same way. */
    fun isFixedInSettings(error: Throwable): Boolean =
        (error as? BackendFailure)?.kind == FailureKind.CONFIGURATION
}
