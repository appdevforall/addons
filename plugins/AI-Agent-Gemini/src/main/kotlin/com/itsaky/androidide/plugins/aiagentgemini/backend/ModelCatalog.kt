package com.itsaky.androidide.plugins.aiagentgemini.backend

/**
 * One key's catalog, split by declared capability: chat models for this plugin's settings pane,
 * embedding models for Vector Search's. One value because both come from one `ListModels` walk.
 *
 * @param chat models that advertise `generateContent`
 * @param embedding models that advertise `embedContent`
 * @param contextWindows each chat model's `inputTokenLimit`, where the listing gave one
 */
internal data class ModelCatalog(
    val chat: List<String>,
    val embedding: List<String>,
    val contextWindows: Map<String, Int> = emptyMap(),
) {
    companion object {
        /** What a key that lists nothing offers. */
        val EMPTY = ModelCatalog(emptyList(), emptyList())
    }
}
