package com.itsaky.androidide.plugins.aiagentopenai.backend

import com.itsaky.androidide.plugins.aiagentopenai.settings.BaseUrlPolicy
import org.json.JSONObject

/**
 * How many tokens one request to the configured model may hold, from the most to the least
 * trustworthy source. Pure, so the order is unit-testable without a server or a Context.
 */
internal object ContextWindow {

    /** What [resolve] answers when no source knows: AI Core then applies its own fallback. */
    const val UNKNOWN = 0

    /** Window of an OpenAI model id no entry in [OPENAI_CONTEXT_WINDOWS] matches. */
    private const val OPENAI_DEFAULT_WINDOW = 128_000

    // Source: platform.openai.com/docs/models, the "Context window" of each model's page.
    private val OPENAI_CONTEXT_WINDOWS = mapOf(
        "gpt-4o" to 128_000,
        "gpt-4.1" to 1_047_576,
        "gpt-5" to 400_000,
        "o1" to 200_000,
        "o3" to 200_000,
        "o4" to 200_000,
    )

    /**
     * The `/models` entry fields that carry a context length, best first. LM Studio's loaded
     * length is the context actually allocated, so it wins over the model's own maximum.
     */
    private val LISTED_LENGTH_FIELDS = listOf(
        "loaded_context_length",
        "context_length",
        "context_window",
        "max_context_length",
    )

    /**
     * The window for [model] on [baseUrl]: the user's setting, else what the server listed for the
     * model, else OpenAI's published figure on OpenAI's own API, else [UNKNOWN].
     *
     * @param userTokens the window the user entered for this server, or null for automatic
     * @param listedTokens what the server's model listing reported for [model], or null
     */
    fun resolve(userTokens: Int?, listedTokens: Int?, baseUrl: String, model: String): Int =
        userTokens?.takeIf { it > 0 }
            ?: listedTokens?.takeIf { it > 0 }
            ?: openAiWindow(baseUrl, model)
            ?: UNKNOWN

    /**
     * OpenAI's published window for [model] by longest matching id prefix, or null when [baseUrl]
     * is not OpenAI's own API, where a model id says nothing about the server's allocation.
     */
    fun openAiWindow(baseUrl: String, model: String): Int? {
        if (!BaseUrlPolicy.isOpenAiApi(baseUrl)) return null
        val id = model.trim().lowercase()
        return OPENAI_CONTEXT_WINDOWS.entries
            .filter { id.startsWith(it.key) }
            .maxByOrNull { it.key.length }
            ?.value
            ?: OPENAI_DEFAULT_WINDOW
    }

    /**
     * The context length one `/models` entry carries, under any field a known server uses.
     *
     * @param entry one element of the listing's `data` array
     * @return the length in tokens, or null when the entry names none
     */
    fun listedIn(entry: JSONObject): Int? {
        val fields = LISTED_LENGTH_FIELDS.asSequence().map { entry.optInt(it, 0) }
        // OpenRouter repeats the length per provider; the top-level one is checked first.
        val provider = entry.optJSONObject("top_provider")?.optInt("context_length", 0) ?: 0
        return (fields + provider).firstOrNull { it > 0 }
    }
}
