package com.itsaky.androidide.plugins.aiagentclaude.backend

/**
 * What one model accepts, as `GET /v1/models` reports it. A null field is one the listing did not
 * carry, which sends the decision back to the static rules in [ClaudeModelTraits].
 *
 * @property maxTokens the model's output cap
 * @property adaptiveThinking whether `thinking: {type: "adaptive"}` is accepted
 * @property effort whether `output_config.effort` is accepted
 */
data class ModelCapabilities(
    val maxTokens: Int? = null,
    val adaptiveThinking: Boolean? = null,
    val effort: Boolean? = null,
)

/**
 * Which optional request fields a Claude model accepts.
 *
 * Each rule is a 400 on the models it is wrong for, so the request builder asks here rather than
 * sending a field and hoping. The live catalog decides when it has an answer ([ModelCapabilities]);
 * the static tables below only cover a model the catalog has not described, such as one typed
 * before the key was ever tested. They are allow-lists, so an unknown model gets the bare request
 * every model accepts rather than a field it may reject. Pure, so every row is unit-testable.
 */
internal object ClaudeModelTraits {

    /** Ceiling on any request's output budget: the largest every 64K-or-larger model accepts. */
    const val MAX_OUTPUT_CEILING = 64_000

    /**
     * Models that take `thinking: {type: "adaptive"}`. Opus 4.5, Sonnet 4.5 and Haiku 4.5 think
     * only with a token budget, which this backend does not send, so they run without thinking.
     */
    private val ADAPTIVE_THINKING_PREFIXES = listOf(
        "claude-fable-5",
        "claude-mythos-5",
        "claude-opus-5",
        "claude-sonnet-5",
        "claude-opus-4-8",
        "claude-opus-4-7",
        "claude-opus-4-6",
        "claude-sonnet-4-6",
    )

    /**
     * Models that think when `thinking` is omitted. On these a request has to leave room for
     * thinking whatever the caller asked for, because it cannot be turned off.
     */
    private val THINKS_BY_DEFAULT_PREFIXES = listOf(
        "claude-fable-5",
        "claude-mythos-5",
        "claude-opus-5",
        "claude-sonnet-5",
    )

    /** Models that take `output_config.effort`; it arrived with Opus 4.5. */
    private val EFFORT_PREFIXES = ADAPTIVE_THINKING_PREFIXES + "claude-opus-4-5"

    /**
     * Models whose output cap is below [MAX_OUTPUT_CEILING]: Opus 4 and 4.1, by alias and by dated
     * id. Every other current model takes 64K or more.
     */
    private val SMALL_OUTPUT_PREFIXES = mapOf(
        "claude-opus-4-0" to 32_000,
        "claude-opus-4-1" to 32_000,
        "claude-opus-4-2025" to 32_000,
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

    /** A dated id is the alias plus `-YYYYMMDD`, e.g. `claude-haiku-4-5-20251001`. */
    private val DATE_SUFFIX = Regex("""-\d{8}""")

    /**
     * Whether [catalogId] names the model [saved] means: the same id, or [saved] as an alias of a
     * dated id. The catalog lists some models only by dated id, so an exact match alone retires a
     * perfectly good alias the user picked.
     */
    fun sameModel(saved: String, catalogId: String): Boolean {
        val a = saved.trim().lowercase()
        val b = catalogId.trim().lowercase()
        if (a == b) return true
        return b.startsWith(a) && DATE_SUFFIX.matches(b.substring(a.length))
    }

    /** The catalog's entry for [model], matching an alias to its dated id; null when not listed. */
    fun lookup(model: String, catalog: Map<String, ModelCapabilities>): ModelCapabilities? =
        catalog[model] ?: catalog.entries.firstOrNull { sameModel(model, it.key) }?.value

    /** Whether [model] accepts `thinking: {type: "adaptive"}`. */
    fun supportsAdaptiveThinking(model: String, known: ModelCapabilities? = null): Boolean =
        known?.adaptiveThinking ?: matchesAny(model, ADAPTIVE_THINKING_PREFIXES)

    /** Whether [model] thinks even when `thinking` is omitted. Static: the catalog does not say. */
    fun thinksByDefault(model: String): Boolean = matchesAny(model, THINKS_BY_DEFAULT_PREFIXES)

    /** Whether [model] accepts `output_config.effort`. */
    fun supportsEffort(model: String, known: ModelCapabilities? = null): Boolean =
        known?.effort ?: matchesAny(model, EFFORT_PREFIXES)

    /** The largest `max_tokens` [model] accepts, held at [MAX_OUTPUT_CEILING]. */
    fun outputCap(model: String, known: ModelCapabilities? = null): Int {
        val id = model.trim().lowercase()
        val cap = known?.maxTokens
            ?: SMALL_OUTPUT_PREFIXES.entries.firstOrNull { id.startsWith(it.key) }?.value
            ?: MAX_OUTPUT_CEILING
        return cap.coerceIn(1, MAX_OUTPUT_CEILING)
    }

    /**
     * Whether [model] takes the server-side `fallbacks` parameter. Exact ids only: the parameter is
     * a 400 on a model that does not list it, so a guess costs every request.
     */
    fun supportsServerFallback(model: String): Boolean =
        model.trim().lowercase() in SERVER_FALLBACK_MODELS

    private fun matchesAny(model: String, prefixes: List<String>): Boolean {
        val id = model.trim().lowercase()
        return prefixes.any { id.startsWith(it) }
    }
}
