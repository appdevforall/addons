package com.itsaky.androidide.plugins.aiagentopenai.backend

import com.itsaky.androidide.plugins.aiagentopenai.errors.OpenAiReplyException
import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService.WebSearchBackend.EXTRA_PARAM_WEB_SEARCH
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI's hosted web search for the agent's `web_search` tool, which asks for it through
 * [LlmConfig.extraParams]. Spoken over the Responses API: `chat/completions` searches only with the
 * dedicated `*-search-api` models, and no compatible server (Ollama, LM Studio) searches at all.
 */
internal object OpenAiWebSearch {

    /** The Responses API endpoint, under the same base URL as `chat/completions`. */
    const val RESPONSES_PATH = "/responses"

    /** @return whether [config] asks for an answer grounded in a web search. */
    fun isRequested(config: LlmConfig): Boolean =
        config.extraParams?.get(EXTRA_PARAM_WEB_SEARCH) == true

    /**
     * A Responses request that searches for [query].
     *
     * No temperature and no output cap: reasoning models refuse the first, and spend the second
     * thinking, which ends a search with no answer at all.
     *
     * @param model the model id to search with
     * @param query what to look up; the request's input
     * @param instructions how to report, or null for the model's default
     * @return the request JSON
     */
    fun body(model: String, query: String, instructions: String?): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("input", query)
            .put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
        instructions?.takeIf { it.isNotBlank() }?.let { body.put("instructions", it) }
        return body
    }

    /**
     * The answer text with its cited sources listed under it.
     *
     * @param response the parsed Responses API reply
     * @return the text of every `output_text` part, followed by a "Sources:" list of its
     *   `url_citation` annotations; "" when the reply carried no text
     * @throws OpenAiReplyException when a 2xx reply reports an `error`, as a `failed` search does
     */
    fun answer(response: JSONObject): String {
        // Thrown rather than read as "no text", which would hide a refused key or a spent quota.
        response.optJSONObject("error")?.let { throw OpenAiReplyException(response.toString()) }
        val text = StringBuilder()
        val sources = LinkedHashSet<String>()
        val output = response.optJSONArray("output") ?: JSONArray()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i)?.takeIf { it.optString("type") == "message" } ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j)?.takeIf { it.optString("type") == "output_text" } ?: continue
                text.append(part.optString("text"))
                val annotations = part.optJSONArray("annotations") ?: continue
                for (k in 0 until annotations.length()) {
                    val note = annotations.optJSONObject(k)?.takeIf { it.optString("type") == "url_citation" } ?: continue
                    val url = note.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val title = note.optString("title").takeIf { it.isNotBlank() }
                    sources += if (title == null) "- $url" else "- $title: $url"
                }
            }
        }
        val answer = text.toString().trim()
        if (answer.isEmpty() || sources.isEmpty()) return answer
        return answer + "\n\nSources:\n" + sources.joinToString("\n")
    }
}
