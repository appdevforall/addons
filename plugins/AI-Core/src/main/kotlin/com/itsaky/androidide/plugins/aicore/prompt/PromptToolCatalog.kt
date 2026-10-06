package com.itsaky.androidide.plugins.aicore.prompt

import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig
import com.itsaky.androidide.plugins.aicore.tool.AgentTools
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema
import com.itsaky.androidide.plugins.services.LlmInferenceService

/**
 * The tool list the model is shown, assembled once and used for both halves of the protocol.
 *
 * Kept in one place because the prose a text-protocol model reads and the schemas a natively
 * calling backend is sent must never name different tools.
 */
object PromptToolCatalog {

    /**
     * The terminal tool's arguments: a schema, not emptyMap(), since under native calling a
     * parameterless declaration is one the model cannot put its answer in.
     */
    val TERMINAL_TOOL_SCHEMA: Map<String, Any> = ToolSchema.objectOf(
        "message" to ToolSchema.string(),
        required = listOf("message"),
    )

    /**
     * The snapshot's budgeted tools, plus [terminalTool], which is not a handler but is how the
     * model addresses the user. Built-in wording comes from `tool_descriptions.yml`.
     *
     * The cap is applied when the snapshot is built, not here, so the grammar the local backend is
     * constrained by and the list the prompt describes can never disagree.
     *
     * @param tools the snapshot this run is using.
     * @param terminalTool the name of the tool that ends a run by answering the user.
     * @param config the loaded prompt config.
     * @return the definitions to hand the backend.
     */
    fun definitions(
        tools: AgentTools,
        terminalTool: String,
        config: AgentPromptConfig,
    ): List<LlmInferenceService.ToolDefinition> {
        val contributed = tools.contributedHandlers.mapTo(mutableSetOf()) { it.toolName }
        val budgeted = tools.promptTools.definitions
        val builtInNames = budgeted.map { it.name }.filterTo(mutableSetOf()) { it !in contributed }
        return ToolDescriptions.apply(config, terminalTool, budgeted, builtInNames) +
            ToolDescriptions.terminalTool(config, terminalTool, TERMINAL_TOOL_SCHEMA)
    }
}
