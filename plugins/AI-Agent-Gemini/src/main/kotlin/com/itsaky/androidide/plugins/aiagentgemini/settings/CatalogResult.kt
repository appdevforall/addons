package com.itsaky.androidide.plugins.aiagentgemini.settings

/**
 * Outcome of one model-catalog lookup against the Gemini backend (ai-agent-gemini).
 *
 * A closed hierarchy, so callers cannot treat "the backend isn't installed" and "Google refused the
 * key" alike — which the old `emptyList()`-on-every-failure bridge forced them to do.
 */
sealed interface CatalogResult {

    /**
     * The backend answered. Either list may be empty, which for [models] is itself suspicious for
     * a valid key.
     *
     * Both halves come from one paginated walk, split by the method each model declares, so they
     * describe the same snapshot of the same key.
     *
     * @param models the chat-capable models the key can reach
     * @param embeddingModels the embedding-capable models the key can reach
     */
    data class Success(
        val models: List<String>,
        val embeddingModels: List<String>,
    ) : CatalogResult

    /** No "gemini" backend was resolvable — ai-core or ai-agent-gemini is missing, disabled,
     * or not yet active. */
    data object NoBackend : CatalogResult

    /**
     * The lookup failed. [cause] is the *unwrapped* failure — the API's [java.io.IOException] for
     * an HTTP error, a [java.util.concurrent.TimeoutException], or a reflection failure when the
     * cross-plugin contract has changed.
     */
    data class Failed(val cause: Throwable) : CatalogResult
}
