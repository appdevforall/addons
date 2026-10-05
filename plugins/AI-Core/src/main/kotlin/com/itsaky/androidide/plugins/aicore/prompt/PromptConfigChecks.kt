package com.itsaky.androidide.plugins.aicore.prompt

import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.web.BackendWebSearch
import com.itsaky.androidide.plugins.aicore.viewmodel.AnswerReview
import com.itsaky.androidide.plugins.aicore.viewmodel.ChatTitle

/**
 * Every render-time check activation runs on a freshly loaded config, in one list so a test covers
 * exactly what activation does; a renderer left out here would only fail on its first chat turn.
 */
object PromptConfigChecks {

    /**
     * Checks [config] against every prompt that renders from it.
     *
     * @param config the loaded prompt config.
     * @param builtIns ai-core's own handlers, whose descriptions the config must supply.
     * @return one message per problem, each naming its file and path; empty when all will render.
     */
    fun problems(config: AgentPromptConfig, builtIns: List<ToolHandler>): List<String> =
        SystemPromptRenderer.problems(config) + ToolResultsPrompt.problems(config) +
            ApprovalPrompt.problems(config) + ContextFilesPrompt.problems(config) + ChatTitle.problems(config) +
            ToolDescriptions.problems(config, builtIns) + BackendWebSearch.problems(config) +
            AnswerReview.problems(config)
}
