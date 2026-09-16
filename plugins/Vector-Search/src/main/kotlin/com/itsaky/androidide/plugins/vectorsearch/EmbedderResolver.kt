package com.itsaky.androidide.plugins.vectorsearch

import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.EmbeddingBackend

/**
 * Which embedder this plugin may use, and why it may not when it may not.
 *
 * A closed hierarchy because the cases need different words: "install an agent plugin", "choose one
 * in AI settings" and "the one you chose cannot embed" are three different things for the user to
 * do, and collapsing them into "no results" is what made the old lexical fallback invisible.
 */
sealed interface EmbedderResolution {

    /**
     * An embedding-capable backend the user selected.
     *
     * @param backend the backend to embed with
     * @param key its identity, short of the vector width, which only a vector can report
     */
    data class Ready(val backend: EmbeddingBackend, val key: EmbedderKey) : EmbedderResolution

    /** AI Core is not installed, or has not published its service yet. */
    data object NoService : EmbedderResolution

    /**
     * No backend has been chosen in AI settings.
     *
     * Deliberately not "pick one for them": embedding a project ships its source to whichever
     * provider is picked, so a guess is a disclosure the user never made. AI Core's own rule is the
     * same — a stored selection is honoured, or nothing is.
     */
    data object NoSelection : EmbedderResolution

    /**
     * The selected backend does not embed. The on-device backend's permanent answer.
     *
     * @param backendId the backend the user selected
     */
    data class NotEmbeddingCapable(val backendId: String) : EmbedderResolution

    /**
     * The selected backend is registered but not configured — no key, no server.
     *
     * @param backendId the backend the user selected
     */
    data class Unavailable(val backendId: String) : EmbedderResolution

    /**
     * The selected backend answered, but not with anything usable.
     *
     * @param backendId the backend the user selected
     * @param reason what was wrong, for the log only
     */
    data class Unusable(val backendId: String, val reason: String) : EmbedderResolution
}

/**
 * Resolves the embedder to use from the backend the user selected for chat.
 *
 * Pure with respect to Android: it reads the inference service and nothing else, so every branch is
 * testable without a device. It deliberately owns no fallback — the caller decides what an
 * unresolved embedder means, and for an index build the answer is "do not build one".
 */
object EmbedderResolver {

    /**
     * Resolves the embedder [service] should be asked for.
     *
     * Every call across the plugin boundary is guarded: the backend object is another `.cgp`'s
     * code, and a throwing accessor there must cost this plugin a search result rather than the
     * whole IDE's project-search request.
     *
     * @param service AI Core's inference service, or null when it is not published
     * @return which of the [EmbedderResolution] cases holds now
     */
    fun resolve(service: LlmInferenceService?): EmbedderResolution {
        if (service == null) return EmbedderResolution.NoService

        val backendId = runCatching { service.preferredBackendId }.getOrNull()
            ?: return EmbedderResolution.NoSelection

        val backend = runCatching { service.getBackend(backendId) }.getOrNull()
            ?: return EmbedderResolution.Unavailable(backendId)

        if (backend !is EmbeddingBackend) {
            return EmbedderResolution.NotEmbeddingCapable(backendId)
        }
        if (!runCatching { backend.isAvailable }.getOrDefault(false)) {
            return EmbedderResolution.Unavailable(backendId)
        }

        val modelId = runCatching { backend.embeddingModelId }.getOrNull()?.trim()
        if (modelId.isNullOrEmpty()) {
            return EmbedderResolution.Unusable(backendId, "the backend named no embedding model")
        }

        return EmbedderResolution.Ready(backend, EmbedderKey(backendId, modelId))
    }
}
