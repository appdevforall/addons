package com.itsaky.androidide.plugins.aiagentopenai.settings

/**
 * Decides whether the saved model still applies once the server or its catalog changed.
 *
 * Pure, so the rule that keeps a `gpt-5` selection from following the user to an Ollama server —
 * where it would 404 on the first message, long after the settings pane was closed — is testable.
 */
internal object ModelSelection {

    /**
     * The model to switch to, or null to keep the one already saved.
     *
     * @param current the model saved right now
     * @param models the catalog to choose from; empty means nothing was discovered
     * @param isLive true when [models] came from a live fetch, so an absent model is real
     * @param savedForThisServer true when [current] was chosen for the server now configured
     * @param preferred the model to favour when [current] has to go, if the catalog offers it
     * @return the replacement model, or null when [current] still applies
     */
    fun adopt(
        current: String,
        models: List<String>,
        isLive: Boolean,
        savedForThisServer: Boolean,
        preferred: String,
    ): String? {
        if (models.isEmpty() || models.contains(current)) return null
        // A remembered list can be months stale, so it only overrides a model from another server.
        if (!isLive && savedForThisServer) return null
        return models.firstOrNull { it == preferred } ?: models.first()
    }
}
