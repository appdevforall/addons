package com.itsaky.androidide.plugins.aiagentgemini.backend

/**
 * One key's catalog, split into the models each picker may offer.
 *
 * Carried as one value because both halves come from one paginated `ListModels` walk, filtered on
 * the capability each model declares: fetching them separately would pay for the walk twice and
 * let the two pickers describe different snapshots.
 *
 * @param chat models that advertise `generateContent`
 * @param embedding models that advertise `embedContent`
 */
internal data class ModelCatalog(
    val chat: List<String>,
    val embedding: List<String>,
) {
    companion object {
        /** What a key that lists nothing offers. */
        val EMPTY = ModelCatalog(emptyList(), emptyList())
    }
}
