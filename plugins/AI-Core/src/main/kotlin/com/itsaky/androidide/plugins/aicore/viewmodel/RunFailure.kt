package com.itsaky.androidide.plugins.aicore.viewmodel

/**
 * A model turn the backend reported as failed, carrying the backend's own sentence: each backend
 * already words its failures for the user, in the IDE's language, so the chat shows them as written.
 */
internal open class BackendFailure(message: String) : RuntimeException(message)

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
}
