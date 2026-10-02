package com.itsaky.androidide.plugins.aiagentclaude.settings

/**
 * Outcome of one model-catalog lookup against the Claude backend.
 *
 * A closed hierarchy, so callers cannot treat "the backend isn't installed" and "the API refused
 * the key" alike.
 */
sealed interface CatalogResult {

    /**
     * The API answered.
     *
     * @param models the models the key can use, possibly none
     */
    data class Success(val models: List<String>) : CatalogResult

    /** No backend was resolvable — this plugin is not active, or was disposed. */
    data object NoBackend : CatalogResult

    /**
     * The lookup failed. [cause] is the *unwrapped* failure — the backend's [java.io.IOException]
     * for an HTTP error, or a [java.util.concurrent.TimeoutException].
     */
    data class Failed(val cause: Throwable) : CatalogResult
}
