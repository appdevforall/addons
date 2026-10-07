package com.itsaky.androidide.plugins.vectorsearch.settings

import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.ActiveModelReportingBackend
import com.itsaky.androidide.plugins.services.LlmInferenceService.EmbeddingModelSelectable

/**
 * What the settings screen can say about the backend selected for chat, which Vector Search embeds
 * with. Closed because each case names a different fix, and the screen draws empty fields for none.
 */
sealed interface BackendCompatibility {

    /** AI Core is not installed, or has not published its service yet. */
    data object NoService : BackendCompatibility

    /** No backend has been chosen in AI settings. */
    data object NoSelection : BackendCompatibility

    /**
     * A backend was chosen but nothing is registered under its id: its plugin is disabled or gone.
     *
     * @param backendId the stored selection
     */
    data class NotInstalled(val backendId: String) : BackendCompatibility

    /**
     * The selected backend, which is registered.
     *
     * @param backendId its id
     * @param backendName its display name
     * @param chatModel the model a chat turn uses, or null when the backend does not report one
     * @param support whether, and how far, it serves Vector Search
     */
    data class Selected(
        val backendId: String,
        val backendName: String,
        val chatModel: String?,
        val support: EmbeddingSupport,
    ) : BackendCompatibility
}

/** Whether a registered backend serves Vector Search. */
sealed interface EmbeddingSupport {

    /**
     * Not supported: it does not embed, or its plugin is too old to list and set its embedding
     * model here (not [EmbeddingModelSelectable]). Local never embeds; an old plugin is updated.
     */
    data object None : EmbeddingSupport

    /**
     * It embeds and lets this screen list and change its model.
     *
     * @param backend the backend to list models from and to set the choice on
     * @param modelId the embedding model it uses now
     */
    data class Selectable(
        val backend: EmbeddingModelSelectable,
        val modelId: String,
    ) : EmbeddingSupport
}

/**
 * Reads [BackendCompatibility] from AI Core's inference service, without Android, so each case is
 * testable. Every call into the backend is guarded: it is another plugin's code, and a throwing
 * accessor there must cost this screen one line rather than the whole Preferences screen.
 */
object BackendCompatibilityResolver {

    /**
     * @param service AI Core's inference service, or null when it is not published
     * @return the case that holds now
     */
    fun resolve(service: LlmInferenceService?): BackendCompatibility {
        if (service == null) return BackendCompatibility.NoService

        val backendId = runCatching { service.preferredBackendId }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return BackendCompatibility.NoSelection

        val backend = runCatching { service.getBackend(backendId) }.getOrNull()
            ?: return BackendCompatibility.NotInstalled(backendId)

        val name = runCatching { backend.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: backendId
        val chatModel = (backend as? ActiveModelReportingBackend)
            ?.let { runCatching { it.activeModelName }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

        return BackendCompatibility.Selected(backendId, name, chatModel, supportOf(backend))
    }

    /** Asks with `instanceof`, as the contract says a consumer should. */
    private fun supportOf(backend: LlmInferenceService.LlmBackend): EmbeddingSupport {
        if (backend !is EmbeddingModelSelectable) return EmbeddingSupport.None
        // A backend that names no model cannot embed anything, whatever interfaces it carries.
        val modelId = runCatching { backend.embeddingModelId }.getOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return EmbeddingSupport.None
        return EmbeddingSupport.Selectable(backend, modelId)
    }
}
