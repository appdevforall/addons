package com.itsaky.androidide.plugins.aicore.prompt.config

import com.itsaky.androidide.plugins.ai.prompt.PromptConfigDocument
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigException
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigLoader
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigObject
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigParser
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig.AgentLoopText
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig.AnswerReviewText
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig.ApprovalText
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig.BuiltInTools
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig.ChatTitleText
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig.ContextFilesText
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig.IdeContextText
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig.Layout
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig.RuleGroup
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig.SessionText
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig.ToolCallFormat
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig.ToolText
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig.Tools
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig.WebSearchText

/**
 * Maps the merged config onto an [AgentPromptConfig]. Strict: a missing, mistyped or unknown key
 * throws [PromptConfigException] naming the file that holds it, so a typo fails on activation.
 */
object AgentPromptConfigParser : PromptConfigParser<AgentPromptConfig> {

    /**
     * Parses the config merged from `agent.yml` and its includes; see [PromptConfigLoader].
     *
     * @param document the merged top-level keys and the file each came from.
     * @return the config.
     */
    override fun parse(document: PromptConfigDocument): AgentPromptConfig =
        document.read {
            val version = int("schema_version")
            if (version != AgentPromptConfig.SCHEMA_VERSION) {
                val supported = AgentPromptConfig.SCHEMA_VERSION
                throw invalid("schema_version", "is $version, but this ai-core reads $supported")
            }
            AgentPromptConfig(
                identity = text("identity"),
                rules = objects("rules").map { it.read { RuleGroup(text("heading"), texts("items")) } },
                tools = obj("tools").read { Tools(text("heading")) },
                toolCallFormat = obj("tool_call_format").read {
                    ToolCallFormat(text("instruction"), text("example_heading"), text("example"))
                },
                ideContext = obj("ide_context").read {
                    IdeContextText(
                        heading = text("heading"),
                        currentFile = text("current_file"),
                        otherFiles = text("other_files"),
                        moduleSourceDir = text("module_source_dir"),
                        moduleLayoutDir = text("module_layout_dir"),
                        moduleManifest = text("module_manifest"),
                        modulesKnown = text("modules_known"),
                        closing = text("closing"),
                    )
                },
                session = obj("session").read {
                    SessionText(
                        currentTime = text("current_time"),
                        webSearch = text("web_search"),
                        webAccess = text("web_access"),
                    )
                },
                agentLoop = obj("agent_loop").read {
                    AgentLoopText(
                        failed = text("failed"),
                        truncated = text("truncated"),
                        grounding = text("grounding"),
                        afterSuccess = text("after_success"),
                        afterFailure = text("after_failure"),
                        unfinished = text("unfinished"),
                        requiredTool = text("required_tool"),
                    )
                },
                approval = obj("approval").read {
                    ApprovalText(
                        denied = text("denied"),
                        corrected = text("corrected"),
                        correctedWithInstruction = text("corrected_with_instruction"),
                        timedOut = text("timed_out"),
                    )
                },
                contextFiles = obj("context_files").read { ContextFilesText(text("heading")) },
                chatTitle = obj("chat_title").read { ChatTitleText(text("instruction")) },
                terminalTool = obj("terminal_tool").read { toolText() },
                builtInTools = BuiltInTools(
                    objectEntries("built_in_tools").mapValues { (_, tool) -> tool.read { toolText() } },
                    labelOf("built_in_tools"),
                ),
                webSearch = obj("web_search").read { WebSearchText(text("instruction")) },
                answerReview = obj("answer_review").read {
                    AnswerReviewText(text("instruction"), text("no_evidence"))
                },
                layout = obj("layout").read {
                    Layout(
                        text("system_prompt"),
                        text("ide_context"),
                        text("tool_results"),
                        text("context_files"),
                        text("chat_title"),
                        text("answer_review"),
                    )
                },
            )
        }

    private fun PromptConfigObject.toolText() = ToolText(text("description"), optionalTextEntries("arguments"))
}
