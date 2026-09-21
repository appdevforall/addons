package com.itsaky.androidide.plugins.aiagentopenai.backend

/**
 * Keeps the chat-capable ids out of a `GET /v1/models` listing.
 *
 * Unlike Gemini's catalog, OpenAI's returns `{id, created, owned_by}` with **no capability flag**,
 * so a raw listing mixes in embedding, audio and image models. This is therefore a heuristic, and
 * deliberately a denylist: an unknown id is kept, because a wrongly hidden model cannot be selected
 * at all while a wrongly offered one merely fails once with a clear server error.
 */
internal object ChatModelFilter {

    /**
     * Substrings that mark a non-chat model.
     *
     * Matched on the whole id, so vendor-prefixed OpenRouter ids are covered too.
     */
    private val NON_CHAT_MARKERS = listOf(
        "embed", "embedding",
        "whisper", "tts", "audio", "transcribe", "realtime",
        "dall-e", "dalle", "image", "stable-diffusion", "sdxl", "flux",
        "moderation", "guard",
        "rerank",
        "clip", "vit",
    )

    /**
     * Filters and orders a raw model listing.
     *
     * @param ids model ids exactly as the server returned them
     * @return the plausible chat models, de-duplicated and sorted for a stable picker
     */
    fun chatModels(ids: List<String>): List<String> = ids
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filter(::isPlausibleChatModel)
        .distinct()
        .sorted()

    /**
     * True when [id] could be a chat model.
     *
     * @param id one model id from the listing
     */
    fun isPlausibleChatModel(id: String): Boolean {
        val normalized = id.lowercase()
        return NON_CHAT_MARKERS.none { normalized.contains(it) }
    }
}
