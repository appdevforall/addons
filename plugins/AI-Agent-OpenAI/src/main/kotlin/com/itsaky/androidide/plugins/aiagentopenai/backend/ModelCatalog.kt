package com.itsaky.androidide.plugins.aiagentopenai.backend

/**
 * One server's catalog: chat models for this plugin's settings pane, embedding models for Vector
 * Search's. One value because both come from one `GET /v1/models`, so they describe one snapshot.
 *
 * @param chat models the chat picker may offer
 * @param embedding models Vector Search's embedding picker may offer
 */
internal data class ModelCatalog(
    val chat: List<String>,
    val embedding: List<String>,
) {
    companion object {
        /** What a server that listed nothing offers. */
        val EMPTY = ModelCatalog(emptyList(), emptyList())
    }
}
