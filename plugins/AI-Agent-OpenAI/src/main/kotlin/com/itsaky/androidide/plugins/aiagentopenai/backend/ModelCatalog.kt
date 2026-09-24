package com.itsaky.androidide.plugins.aiagentopenai.backend

/**
 * One server's catalog, split into the models each picker may offer.
 *
 * Carried as one value because both halves come from one `GET /v1/models`: fetching them
 * separately would let the two pickers describe different snapshots of the same server.
 *
 * @param chat models the chat picker may offer
 * @param embedding models the embedding picker may offer
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
