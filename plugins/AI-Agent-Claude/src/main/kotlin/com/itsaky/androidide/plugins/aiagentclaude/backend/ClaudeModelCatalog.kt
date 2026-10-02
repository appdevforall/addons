package com.itsaky.androidide.plugins.aiagentclaude.backend

import org.json.JSONObject

/**
 * Reads `GET /v1/models`, and stores what it says about each model.
 *
 * Every model the endpoint lists can chat, so unlike an OpenAI-compatible catalog there is nothing
 * to filter out. Each entry also carries the model's output cap and a capability tree, which is
 * what decides the optional request fields ([ClaudeModelTraits]). Pure, so the parse is
 * unit-testable from a captured body.
 */
object ClaudeModelCatalog {

    /** One page large enough for the whole catalog, so a listing is one request. */
    const val PATH = "/models?limit=1000"

    /** One listed model. */
    data class Entry(val id: String, val capabilities: ModelCapabilities)

    /**
     * The models in [body], newest first as the API orders them.
     *
     * @param body the response body
     * @return the entries; empty when the body lists none or is not the expected shape
     */
    fun entries(body: String): List<Entry> {
        val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull()
            ?: return emptyList()
        val seen = HashSet<String>()
        return (0 until data.length()).mapNotNull { index ->
            val model = data.optJSONObject(index) ?: return@mapNotNull null
            val id = model.optString("id").trim().takeIf { it.isNotEmpty() && seen.add(it) }
                ?: return@mapNotNull null
            Entry(id, capabilitiesOf(model))
        }
    }

    /** The model ids in [body]; see [entries]. */
    fun ids(body: String): List<String> = entries(body).map { it.id }

    /**
     * What one model object says it accepts. A field the listing leaves out stays null, so the
     * static rules decide it rather than a guess made here.
     */
    private fun capabilitiesOf(model: JSONObject): ModelCapabilities {
        val caps = model.optJSONObject("capabilities")
        return ModelCapabilities(
            maxTokens = model.optInt("max_tokens", 0).takeIf { it > 0 },
            adaptiveThinking = caps?.optJSONObject("thinking")
                ?.optJSONObject("types")
                ?.optJSONObject("adaptive")
                ?.supported(),
            effort = caps?.optJSONObject("effort")?.supported(),
        )
    }

    /** A capability leaf's `supported` flag, or null when the leaf carries none. */
    private fun JSONObject.supported(): Boolean? =
        if (has("supported")) optBoolean("supported") else null

    /** Separates fields within one stored line; no model id contains a tab. */
    private const val FIELD = "\t"

    /**
     * Flattens [catalog] for storage, one `id<TAB>maxTokens<TAB>adaptive<TAB>effort` line per
     * model, with an empty field for an unknown value. Newline-delimited rather than JSON, like
     * the remembered model list, so a read can never throw.
     *
     * @return the encoded form, or null when there is nothing to remember
     */
    fun encodeCapabilities(catalog: List<Entry>): String? = catalog
        .takeIf { it.isNotEmpty() }
        ?.joinToString("\n") { (id, c) ->
            listOf(id, c.maxTokens?.toString().orEmpty(), c.adaptiveThinking.flag(), c.effort.flag())
                .joinToString(FIELD)
        }

    /** Restores what [encodeCapabilities] wrote; a malformed line is skipped. */
    fun decodeCapabilities(stored: String?): Map<String, ModelCapabilities> {
        if (stored.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, ModelCapabilities>()
        for (line in stored.split("\n")) {
            val fields = line.split(FIELD)
            val id = fields.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            result[id] = ModelCapabilities(
                maxTokens = fields.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 },
                adaptiveThinking = fields.getOrNull(2).parseFlag(),
                effort = fields.getOrNull(3).parseFlag(),
            )
        }
        return result
    }

    private fun Boolean?.flag(): String = when (this) {
        true -> "1"
        false -> "0"
        null -> ""
    }

    private fun String?.parseFlag(): Boolean? = when (this?.trim()) {
        "1" -> true
        "0" -> false
        else -> null
    }
}
