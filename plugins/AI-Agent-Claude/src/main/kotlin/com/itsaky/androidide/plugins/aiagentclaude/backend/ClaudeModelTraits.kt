package com.itsaky.androidide.plugins.aiagentclaude.backend

/**
 * Which optional request fields a Claude model accepts, decided from its id.
 *
 * Each rule is a 400 on the models it excludes, so the request builder asks here rather than
 * sending a field and hoping. Pure, so every row is unit-testable.
 *
 * `thinking` and the sampling parameters are not here because this backend never sends them: an
 * omitted `thinking` runs each model's own default (adaptive on Claude Opus 5.5, which rejects any
 * other setting), and `temperature` is a 400 on every current Opus and Sonnet.
 */
internal object ClaudeModelTraits {

    /**
     * Models that reject `output_config.effort`. Effort arrived with Opus 4.5, so these are the
     * older and smaller lines; an id matching none of them is assumed current.
     */
    private val NO_EFFORT_PREFIXES = listOf(
        "claude-haiku-",
        "claude-sonnet-4-5",
        "claude-opus-4-1",
        "claude-opus-4-0",
        "claude-sonnet-4-0",
        "claude-3",
    )

    /**
     * Models whose safety classifiers can decline a request with `stop_reason: "refusal"`, and
     * which accept the server-side `fallbacks: "default"` that re-runs a declined request on a
     * model chosen by the refusal category.
     */
    private val SERVER_FALLBACK_MODELS = setOf(
        "claude-fable-5-1",
        "claude-opus-5-5",
        "claude-opus-5",
        "claude-sonnet-5-5",
    )

    /** Beta header that enables `fallbacks: "default"`; the array form uses a different one. */
    const val SERVER_FALLBACK_BETA = "server-side-fallback-2026-07-01"

    /**
     * Whether [model] accepts `output_config.effort`.
     *
     * @param model the model id as configured, possibly with a date suffix
     */
    fun supportsEffort(model: String): Boolean {
        val id = model.trim().lowercase()
        return NO_EFFORT_PREFIXES.none { id.startsWith(it) }
    }

    /**
     * Whether [model] takes the server-side `fallbacks` parameter. Exact ids only: the parameter is
     * a 400 on a model that does not list it, so a guess costs every request.
     *
     * @param model the model id as configured
     */
    fun supportsServerFallback(model: String): Boolean =
        model.trim().lowercase() in SERVER_FALLBACK_MODELS
}
