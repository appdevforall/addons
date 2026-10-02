package com.itsaky.androidide.plugins.aiagentclaude.backend

import org.json.JSONObject

/**
 * Reads `GET /v1/models`.
 *
 * Every model the endpoint lists can chat, so unlike an OpenAI-compatible catalog there is nothing
 * to filter out. Pure, so the parse is unit-testable from a captured body.
 */
internal object ClaudeModelCatalog {

    /** One page large enough for the whole catalog, so a listing is one request. */
    const val PATH = "/models?limit=1000"

    /**
     * The model ids in [body], newest first as the API orders them.
     *
     * @param body the response body
     * @return the ids; empty when the body lists none or is not the expected shape
     */
    fun ids(body: String): List<String> {
        val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull()
            ?: return emptyList()
        return (0 until data.length()).mapNotNull { index ->
            data.optJSONObject(index)?.optString("id")?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct()
    }
}
