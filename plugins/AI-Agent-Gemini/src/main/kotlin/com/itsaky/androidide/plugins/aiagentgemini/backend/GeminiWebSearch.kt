package com.itsaky.androidide.plugins.aiagentgemini.backend

import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Search grounding for the agent's `web_search` tool, which asks for it through
 * [LlmConfig.extraParams]. Sent alone, never beside function declarations: Gemini 2.x rejects
 * Google Search mixed with function calling, and only Gemini 3 accepts the two together.
 */
internal object GeminiWebSearch {

    /** The key ai-core's `WebAccess.EXTRA_PARAM_WEB_SEARCH` sets; the same literal on both sides. */
    const val EXTRA_PARAM_WEB_SEARCH = "web_search"

    /**
     * Put after a report the output cap cut short. Without it the agent read a report ending
     * mid-sentence as complete, and wrote a placeholder for the version the cut had dropped.
     */
    const val CUT_OFF_NOTE =
        "[This report was cut off at the output limit; whatever came after this point is missing.]"

    /** @return whether [config] asks for an answer grounded in a web search. */
    fun isRequested(config: LlmConfig): Boolean =
        config.extraParams?.get(EXTRA_PARAM_WEB_SEARCH) == true

    /**
     * Declares Google Search as the request's only tool.
     *
     * @param body a request built by `buildRequestJson` with no tools.
     * @return [body], for chaining.
     */
    fun declareSearch(body: JSONObject): JSONObject =
        body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))

    /**
     * Every web source link the search returned, once each and in the order given.
     *
     * @param response the whole generateContent response, holding `groundingMetadata`.
     */
    fun sourceUris(response: JSONObject): List<String> =
        webSources(response).map { it.first }.distinct()

    /**
     * The grounded answer with its sources listed under it, so the agent can cite them or fetch one.
     *
     * @param text the reply text of the first candidate.
     * @param response the whole generateContent response, holding `groundingMetadata`.
     * @param resolved each source link's real target (see [GroundingRedirect]); a link missing
     *   from it is listed as given.
     * @return [text], then [CUT_OFF_NOTE] when the cap cut it short, then a "Sources:" list when
     *   the search returned any.
     */
    fun withSources(
        text: String,
        response: JSONObject,
        resolved: Map<String, String> = emptyMap(),
    ): String {
        val report = if (wasCutOff(response)) text.trimEnd() + "\n\n" + CUT_OFF_NOTE else text
        val sources = webSources(response).map { (uri, title) ->
            val link = resolved[uri] ?: uri
            if (title == null) "- $link" else "- $title: $link"
        }.distinct()
        if (sources.isEmpty()) return report
        return report.trimEnd() + "\n\nSources:\n" + sources.joinToString("\n")
    }

    /** Whether the first candidate stopped at the output cap rather than at its end. */
    private fun wasCutOff(response: JSONObject): Boolean =
        response.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason") == "MAX_TOKENS"

    /** Each `web` grounding chunk as its link and title, skipping one with no link. */
    private fun webSources(response: JSONObject): List<Pair<String, String?>> {
        val chunks = response.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("groundingMetadata")
            ?.optJSONArray("groundingChunks")
            ?: return emptyList()
        return (0 until chunks.length()).mapNotNull { index ->
            val web = chunks.optJSONObject(index)?.optJSONObject("web") ?: return@mapNotNull null
            val uri = web.optString("uri").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            uri to web.optString("title").takeIf { it.isNotBlank() }
        }
    }
}
