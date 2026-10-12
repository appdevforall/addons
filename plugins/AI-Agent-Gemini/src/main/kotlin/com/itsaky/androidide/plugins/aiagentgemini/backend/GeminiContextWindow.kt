package com.itsaky.androidide.plugins.aiagentgemini.backend

/**
 * How many tokens one request to a Gemini model may hold. The live catalog's `inputTokenLimit`
 * decides when it listed the model; a documented default covers a known family it has not. Pure,
 * so the resolution and the stored form are unit-testable.
 */
internal object GeminiContextWindow {

    /** Input limit of the gemini-1.5, 2.x and 2.5 models, per Google's Gemini model docs. */
    const val DEFAULT_TOKENS = 1_048_576

    /** The families [DEFAULT_TOKENS] is documented for; `gemini-2.` covers 2.0 and 2.5. */
    private val DEFAULT_FAMILIES = listOf("gemini-1.5", "gemini-2.")

    /** Separates the id from its limit within one stored line; no model id contains a tab. */
    private const val FIELD = "\t"

    /**
     * The window of [model]: the catalog's figure, else [DEFAULT_TOKENS] for a documented family.
     *
     * @param known each listed model's `inputTokenLimit`, by id without the `models/` prefix
     * @return the window in tokens, or 0 when neither the catalog nor the docs say
     */
    fun resolve(model: String, known: Map<String, Int>): Int {
        val id = model.trim().removePrefix("models/")
        known[id]?.let { return it }
        val lower = id.lowercase()
        return if (DEFAULT_FAMILIES.any { lower.startsWith(it) }) DEFAULT_TOKENS else 0
    }

    /**
     * Flattens [windows] for storage, one `id<TAB>tokens` line per model.
     *
     * @return the encoded form, or null when there is nothing to remember
     */
    fun encode(windows: Map<String, Int>): String? = windows
        .takeIf { it.isNotEmpty() }
        ?.entries
        ?.joinToString("\n") { (id, tokens) -> id + FIELD + tokens }

    /** Restores what [encode] wrote; a malformed line is skipped, so a read can never throw. */
    fun decode(stored: String?): Map<String, Int> {
        if (stored.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, Int>()
        for (line in stored.split("\n")) {
            val id = line.substringBefore(FIELD).trim().takeIf { it.isNotEmpty() } ?: continue
            val tokens = line.substringAfter(FIELD, "").trim().toIntOrNull()?.takeIf { it > 0 }
                ?: continue
            result[id] = tokens
        }
        return result
    }
}
