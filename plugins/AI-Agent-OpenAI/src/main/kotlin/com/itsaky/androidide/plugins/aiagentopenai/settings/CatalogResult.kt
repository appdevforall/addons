package com.itsaky.androidide.plugins.aiagentopenai.settings

/**
 * Outcome of one model-catalog lookup against the OpenAI-compatible backend.
 *
 * A closed hierarchy, so callers cannot treat "the backend isn't installed" and "the server refused
 * the key" alike.
 */
sealed interface CatalogResult {

    /** The server answered. [models] may be empty, which many compatible servers do. */
    data class Success(val models: List<String>) : CatalogResult

    /** No backend was resolvable — this plugin is not active, or was disposed. */
    data object NoBackend : CatalogResult

    /**
     * The lookup failed. [cause] is the *unwrapped* failure — the backend's [java.io.IOException]
     * for an HTTP error, or a [java.util.concurrent.TimeoutException].
     */
    data class Failed(val cause: Throwable) : CatalogResult
}
