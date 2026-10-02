package com.itsaky.androidide.plugins.aiagentclaude.settings

/**
 * Decides whether the saved model still applies once the catalog changed.
 *
 * Pure, so the rule that retires a model the API no longer offers — which would otherwise 404 on
 * the first message, long after the settings pane was closed — is testable.
 */
internal object ModelSelection {

    /**
     * The model to switch to, or null to keep the one already saved.
     *
     * @param current the model saved right now
     * @param models the catalog to choose from; empty means nothing was discovered
     * @param isLive true when [models] came from a live fetch, so an absent model is real; a
     *   remembered list can be months stale, and never retires anything
     * @param preferred the model to favour when [current] has to go, if the catalog offers it
     * @return the replacement model, or null when [current] still applies
     */
    fun adopt(
        current: String,
        models: List<String>,
        isLive: Boolean,
        preferred: String,
    ): String? {
        if (!isLive || models.isEmpty() || models.contains(current)) return null
        return models.firstOrNull { it == preferred } ?: models.first()
    }
}
