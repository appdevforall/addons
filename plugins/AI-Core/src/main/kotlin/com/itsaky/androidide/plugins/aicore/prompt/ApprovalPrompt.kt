package com.itsaky.androidide.plugins.aicore.prompt

import com.itsaky.androidide.plugins.ai.prompt.PromptTemplateEngine
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig


/**
 * What the agent is told when the user does not approve a tool call, worded by `agent_loop.yml`'s
 * `approval`. The loop feeds it back as the call's failure, so the model revises or moves on.
 */
object ApprovalPrompt {

    /**
     * @param config the loaded prompt config.
     * @param tool the tool the user refused.
     * @return the denial message.
     */
    fun denied(config: AgentPromptConfig, tool: String): String =
        PromptTemplateEngine.render(config.approval.denied, mapOf(PromptVariables.TOOL to tool))

    /**
     * @param config the loaded prompt config.
     * @param tool the tool the user asked to revise.
     * @param instruction what the user typed; blank when they typed nothing.
     * @return the denial message, relaying [instruction] when there is one.
     */
    fun corrected(config: AgentPromptConfig, tool: String, instruction: String): String =
        if (instruction.isBlank()) {
            PromptTemplateEngine.render(config.approval.corrected, mapOf(PromptVariables.TOOL to tool))
        } else {
            val values = mapOf(PromptVariables.TOOL to tool, PromptVariables.INSTRUCTION to instruction.trim())
            PromptTemplateEngine.render(config.approval.correctedWithInstruction, values)
        }

    /**
     * @param config the loaded prompt config.
     * @param tool the tool whose dialog went unanswered.
     * @param minutes how long the dialog waited.
     * @return the denial message.
     */
    fun timedOut(config: AgentPromptConfig, tool: String, minutes: Long): String {
        val values = mapOf(PromptVariables.TOOL to tool, PromptVariables.MINUTES to minutes.toString())
        return PromptTemplateEngine.render(config.approval.timedOut, values)
    }

    /**
     * Renders every message, to catch a name typo.
     *
     * @param config the loaded prompt config.
     * @return one message per distinct failure; empty when every one renders.
     */
    fun problems(config: AgentPromptConfig): List<String> {
        val renders: List<() -> String> = listOf(
            { denied(config, CHECK_TOOL) },
            { corrected(config, CHECK_TOOL, "") },
            { corrected(config, CHECK_TOOL, "keep the name") },
            { timedOut(config, CHECK_TOOL, 5) },
        )
        return renders.mapNotNull { check ->
            try {
                check()
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
        }.distinct()
    }

    private const val CHECK_TOOL = "edit_file"
}
