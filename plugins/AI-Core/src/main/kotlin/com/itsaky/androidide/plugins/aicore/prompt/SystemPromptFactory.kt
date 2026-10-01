package com.itsaky.androidide.plugins.aicore.prompt

import com.itsaky.androidide.plugins.ai.prompt.PromptConfigProvider
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig
import com.itsaky.androidide.plugins.aicore.tool.web.WebAccess
import com.itsaky.androidide.plugins.services.LlmInferenceService.SystemPromptRequest
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition

/**
 * Chooses the system prompt for one run: the prompt config's, or the backend's own with the IDE CONTEXT
 * block appended. Knows no wording and no file names; see [SystemPromptRenderer].
 *
 * @param config supplies the cached prompt config.
 * @param ideContext reads what the IDE has open.
 * @param backend answers for the backend this run is against.
 * @param session reads the clock, fresh for every run.
 * @param terminalTool the name of the tool that ends a run by answering the user.
 * @param toolCallSyntax the envelope this side parses back, taught only under the text protocol.
 */
class SystemPromptFactory(
    private val config: PromptConfigProvider<AgentPromptConfig>,
    private val ideContext: IdeContextSource,
    private val backend: BackendPrompts,
    private val session: () -> SessionContext,
    private val terminalTool: String,
    private val toolCallSyntax: String,
) {

    /**
     * Builds the prompt; a backend with no prompt of its own gets the general one instead.
     *
     * @param tools the definitions this run offers, from [PromptToolCatalog], also sent natively.
     * @return the prompt to send as the run's system prompt.
     */
    suspend fun create(tools: List<ToolDefinition>): String {
        val loaded = config.config()
        // One editor read serves both the IDE CONTEXT block and the paths in the examples.
        val context = ideContext.read()
        val session = session().copy(canSearchWeb = tools.any { it.name == WebAccess.WEB_SEARCH_TOOL })
        val request = SystemPromptRequest(
            tools,
            // Null tells the backend this side parses no envelope; see SystemPromptRequest.
            toolCallSyntax.takeUnless { backend.callsToolsNatively() },
            context.exampleFilePath,
        )

        val own = backend.systemPrompt(request)
            ?: return SystemPromptRenderer.render(loaded, request, terminalTool, context, session)
        return listOf(own, SystemPromptRenderer.renderIdeContext(loaded, context, session))
            .filter { it.isNotEmpty() }
            .joinToString(SEPARATOR)
    }

    private companion object {
        /** What separates a backend's own prompt from the IDE CONTEXT block. */
        const val SEPARATOR = "\n\n"
    }
}
