package com.itsaky.androidide.plugins.aicore.tool.web

import com.itsaky.androidide.plugins.ai.prompt.PromptConfigProvider
import com.itsaky.androidide.plugins.ai.prompt.PromptTemplateEngine
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.prompt.PromptVariables
import com.itsaky.androidide.plugins.aicore.prompt.SessionContext
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmBackend
import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService.WebSearchBackend.EXTRA_PARAM_WEB_SEARCH
import kotlinx.coroutines.future.await

/**
 * Answers a web search through the active backend's own provider search, as a separate one-off
 * request with no other tools declared: Gemini 2.x refuses Google Search beside function calling,
 * and OpenAI offers web search only on its Responses API, not the chat transport the agent uses.
 *
 * @param config supplies the prompt config holding `web_search.instruction`.
 * @param backendId the backend the current run is against.
 * @param backend resolves that backend, or null when it cannot be reached.
 * @param currentTime the device's date and time as the prompt words it, so "latest" means today.
 */
class BackendWebSearch(
    private val config: PromptConfigProvider<AgentPromptConfig>,
    private val backendId: () -> String,
    private val backend: () -> LlmBackend?,
    private val currentTime: () -> String = { SessionContext.current().currentTime },
) {

    /**
     * Searches for [query].
     *
     * @param query what to look up, as the model phrased it.
     * @return the backend's findings with their sources, or why it could not search.
     */
    suspend fun search(query: String): ToolResult {
        val backend = backend()
            ?: return ToolResult.failure("No AI backend is available to search with")
        val request = LlmConfig(backendId()).apply {
            temperature = SEARCH_TEMPERATURE
            maxTokens = SEARCH_MAX_TOKENS
            systemPrompt = instruction(config.config(), currentTime())
            // A backend that cannot search must refuse on seeing this, not answer from memory.
            extraParams = mapOf(EXTRA_PARAM_WEB_SEARCH to true)
        }
        val response = backend.generate(query, request).await()
        if (!response.success) {
            return ToolResult.failure(response.error?.takeIf { it.isNotBlank() } ?: "Web search failed")
        }
        val text = response.text?.trim().orEmpty()
        if (text.isEmpty()) return ToolResult.failure("Web search returned nothing for: $query")
        return ToolResult.success("Searched the web for: $query", text)
    }

    companion object {

        /**
         * Renders the search request's system prompt.
         *
         * @param config the loaded prompt config.
         * @param currentTime the device's date and time, as the prompt words it.
         * @return the instruction.
         */
        fun instruction(config: AgentPromptConfig, currentTime: String): String =
            PromptTemplateEngine.render(
                config.webSearch.instruction,
                mapOf(PromptVariables.CURRENT_TIME to currentTime),
            )

        /**
         * Renders the instruction once, to catch a name typo on activation.
         *
         * @param config the loaded prompt config.
         * @return the failure's message; empty when it renders.
         */
        fun problems(config: AgentPromptConfig): List<String> = try {
            instruction(config, CHECK_TIME)
            emptyList()
        } catch (e: IllegalArgumentException) {
            listOfNotNull(e.message)
        }

        private const val CHECK_TIME = "Monday, 1 January 2029, 09:00 (UTC, UTC+00:00)"

        /** Low: this is a report of what the results say, not a place for invention. */
        private const val SEARCH_TEMPERATURE = 0.2f
        /**
         * Gemini counts its thinking against this cap, and 2048 cut a report mid-sentence before
         * the version it was asked for (ADFA-6223). OpenAI's search request sends no cap at all.
         */
        private const val SEARCH_MAX_TOKENS = 8192
    }
}
