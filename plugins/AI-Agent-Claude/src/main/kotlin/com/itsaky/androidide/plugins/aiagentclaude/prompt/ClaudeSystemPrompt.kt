package com.itsaky.androidide.plugins.aiagentclaude.prompt

import com.itsaky.androidide.plugins.ai.prompt.PromptTemplateEngine
import com.itsaky.androidide.plugins.aiagentclaude.prompt.config.ClaudePromptConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService.SystemPromptRequest
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition

/**
 * The system prompt this backend asks for: `layout.yml`'s `system_prompt`, rendered in one pass.
 * Written for a large cloud model, so the wording lives with the backend that talks to it; knows
 * no wording itself, which is the config's. Pure and thread-safe.
 */
internal object ClaudeSystemPrompt {

    /**
     * Builds the prompt; the envelope and its examples appear only when the caller parses text.
     *
     * @param request the tool list, envelope syntax and example path to describe.
     * @param config the loaded prompt config.
     * @return the system prompt, without the caller's IDE-context block.
     */
    fun build(request: SystemPromptRequest, config: ClaudePromptConfig): String =
        PromptTemplateEngine.render(config.layout.systemPrompt, ClaudePromptVariables.collect(config, request))
            .trimEnd()

    /**
     * Renders requests that open and close every section, to catch a name typo.
     *
     * @param config the loaded prompt config.
     * @return one message per distinct failure; empty when every request renders.
     */
    fun problems(config: ClaudePromptConfig): List<String> =
        CHECK_REQUESTS.mapNotNull { request ->
            try {
                build(request, config)
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
        }.distinct()

    /** Text and native calling, two tools and none, a real path and the fallback. */
    private val CHECK_REQUESTS: List<SystemPromptRequest> = run {
        val tools = listOf(
            ToolDefinition("read_file", "Read a file.", emptyMap()),
            ToolDefinition("respond", "Reply.", emptyMap()),
        )
        listOf(
            SystemPromptRequest(tools, "<tool_call>…</tool_call>", "app/Main.kt"),
            SystemPromptRequest(emptyList(), null, null),
        )
    }
}
