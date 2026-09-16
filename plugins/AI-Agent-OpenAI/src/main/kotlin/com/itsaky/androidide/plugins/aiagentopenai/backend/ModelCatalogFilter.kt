package com.itsaky.androidide.plugins.aiagentopenai.backend

/**
 * Splits a raw `GET /v1/models` listing into the models each picker may offer.
 *
 * Unlike Gemini's catalog, OpenAI's returns `{id, created, owned_by}` with **no capability flag**,
 * so a raw listing mixes in embedding, audio and image models. This is therefore a heuristic over
 * the id, and the two pickers are derived from one marker list on purpose: the embedding picker
 * offers exactly what the chat picker hides, so the two cannot drift into offering the same model
 * for both jobs or into hiding a model from both.
 */
internal object ModelCatalogFilter {

    /**
     * Substrings that mark an embedding model.
     *
     * The embedding picker is an allowlist over these — a model it wrongly omits can still be
     * typed in, and one it wrongly offers fails once with a clear server error.
     */
    private val EMBEDDING_MARKERS = listOf("embed", "embedding")

    /**
     * Substrings that mark a non-chat model, embedding markers included.
     *
     * Matched on the whole id, so vendor-prefixed OpenRouter ids are covered too. The chat picker
     * is a denylist over these: an unknown id is kept, because a wrongly hidden model cannot be
     * selected at all while a wrongly offered one merely fails once.
     */
    private val NON_CHAT_MARKERS = EMBEDDING_MARKERS + listOf(
        "whisper", "tts", "audio", "transcribe", "realtime",
        "dall-e", "dalle", "image", "stable-diffusion", "sdxl", "flux",
        "moderation", "guard",
        "rerank",
        "clip", "vit",
    )

    /**
     * Filters and orders a raw model listing down to the chat models.
     *
     * @param ids model ids exactly as the server returned them
     * @return the plausible chat models, de-duplicated and sorted for a stable picker
     */
    fun chatModels(ids: List<String>): List<String> = normalize(ids).filter(::isPlausibleChatModel)

    /**
     * Filters and orders a raw model listing down to the embedding models.
     *
     * @param ids model ids exactly as the server returned them
     * @return the plausible embedding models, de-duplicated and sorted for a stable picker
     */
    fun embeddingModels(ids: List<String>): List<String> =
        normalize(ids).filter(::isPlausibleEmbeddingModel)

    /**
     * True when [id] could be a chat model.
     *
     * @param id one model id from the listing
     */
    fun isPlausibleChatModel(id: String): Boolean = NON_CHAT_MARKERS.none { marker ->
        id.lowercase().contains(marker)
    }

    /**
     * True when [id] could be an embedding model.
     *
     * @param id one model id from the listing
     */
    fun isPlausibleEmbeddingModel(id: String): Boolean = EMBEDDING_MARKERS.any { marker ->
        id.lowercase().contains(marker)
    }

    /** Trims, drops blanks and duplicates, and sorts, so a picker's order is stable. */
    private fun normalize(ids: List<String>): List<String> = ids
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()
}
