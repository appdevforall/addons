package com.itsaky.androidide.plugins.aiagentopenai.backend

import org.json.JSONObject

/**
 * One server's catalog: chat models for this plugin's settings pane, embedding models for Vector
 * Search's. One value because both come from one `GET /v1/models`, so they describe one snapshot.
 *
 * @param chat models the chat picker may offer
 * @param embedding models Vector Search's embedding picker may offer
 * @param contextWindows the context length the listing reported per model id, where it reported one
 */
internal data class ModelCatalog(
    val chat: List<String>,
    val embedding: List<String>,
    val contextWindows: Map<String, Int> = emptyMap(),
) {
    companion object {
        /** What a server that listed nothing offers. */
        val EMPTY = ModelCatalog(emptyList(), emptyList())

        /**
         * Splits one `GET /models` body into both pickers and the windows it reported.
         *
         * @param body the response body; a body without a `data` array lists nothing
         */
        fun parse(body: String): ModelCatalog {
            val data = JSONObject(body).optJSONArray("data") ?: return EMPTY
            val entries = (0 until data.length()).mapNotNull { index ->
                val entry = data.optJSONObject(index) ?: return@mapNotNull null
                entry.optString("id").takeIf { it.isNotBlank() }?.let { it to entry }
            }
            val ids = entries.map { it.first }
            return ModelCatalog(
                chat = ModelCatalogFilter.chatModels(ids),
                embedding = ModelCatalogFilter.embeddingModels(ids),
                contextWindows = entries.mapNotNull { (id, entry) ->
                    ContextWindow.listedIn(entry)?.let { id to it }
                }.toMap(),
            )
        }
    }
}
