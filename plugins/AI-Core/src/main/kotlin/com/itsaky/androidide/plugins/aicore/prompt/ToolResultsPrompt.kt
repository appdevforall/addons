package com.itsaky.androidide.plugins.aicore.prompt

import com.itsaky.androidide.plugins.ai.prompt.PromptConfigProvider
import com.itsaky.androidide.plugins.ai.prompt.PromptTemplateEngine
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig
import com.itsaky.androidide.plugins.aicore.tool.ToolCall
import com.itsaky.androidide.plugins.aicore.tool.ToolResultsFormatter
import com.itsaky.androidide.plugins.aicore.tool.web.WebAccess

/**
 * The turn after each tool batch: the results in `<tool_response>` envelopes, then what to do next,
 * worded by `agent_loop.yml` and arranged by `layout.tool_results`. The envelope stays in code,
 * since chat-tuned models are trained on the tag; handed bare prose they re-issue the call.
 *
 * @param config supplies the cached prompt config.
 * @param terminalTool the name of the tool that ends a run by answering the user.
 * @param charLimit each result's cap, so a big output does not blow a local model's context.
 */
class ToolResultsPrompt(
    private val config: PromptConfigProvider<AgentPromptConfig>,
    private val terminalTool: String,
    private val charLimit: Int = DEFAULT_CHAR_LIMIT,
) : ToolResultsFormatter {

    override suspend fun format(calls: List<ToolCall>, results: List<ToolResult>): String =
        render(config.config(), terminalTool, charLimit, calls, results)

    /** The turn sent after a reply that ran tools and ended without the terminal tool. */
    suspend fun unfinished(): String = renderUnfinished(config.config(), terminalTool)

    /** The turn sent after a reply that answered before calling [tool], which the run had to call first. */
    suspend fun requiredTool(tool: String): String = renderRequiredTool(config.config(), terminalTool, tool)

    companion object {
        /** Per-result cap fed back into the prompt, so big outputs don't blow a local model's context. */
        const val DEFAULT_CHAR_LIMIT = 4000

        /**
         * A search report's or fetched page's cap: [DEFAULT_CHAR_LIMIT] cut a report before its Sources
         * list, and a page before anything past its navigation.
         */
        const val WEB_SEARCH_CHAR_LIMIT = 12000

        /**
         * Renders one batch's turn. Pure and thread-safe.
         *
         * @param config the loaded prompt config.
         * @param terminalTool the name of the tool that ends a run by answering the user.
         * @param charLimit each result's cap; a web search's or fetch's is at least [WEB_SEARCH_CHAR_LIMIT].
         * @param calls the tool calls that ran.
         * @param results their results, positionally aligned with [calls].
         * @return the turn to add to the transcript.
         */
        fun render(
            config: AgentPromptConfig,
            terminalTool: String,
            charLimit: Int,
            calls: List<ToolCall>,
            results: List<ToolResult>,
        ): String {
            val responses = buildString {
                results.forEachIndexed { index, result ->
                    val name = calls.getOrNull(index)?.name ?: "tool"
                    val limit =
                        if (name == WebAccess.WEB_SEARCH_TOOL || name == WebAccess.FETCH_URL_TOOL) maxOf(charLimit, WEB_SEARCH_CHAR_LIMIT) else charLimit
                    val body = truncate(config, terminalTool, body(config, terminalTool, result), limit)
                    append("<tool_response>\n[").append(name).append("] ").append(body)
                    append("\n</tool_response>\n\n")
                }
            }
            val allSucceeded = results.isNotEmpty() && results.all { it.success }
            return PromptTemplateEngine.render(
                config.layout.toolResults,
                PromptVariables.toolResults(config, terminalTool, responses, allSucceeded),
            )
        }

        /**
         * Renders the turn that asks a run which ended without the terminal tool to finish it.
         *
         * @param config the loaded prompt config.
         * @param terminalTool the name of the tool that ends a run by answering the user.
         * @return the turn to add to the transcript.
         */
        fun renderUnfinished(config: AgentPromptConfig, terminalTool: String): String =
            PromptTemplateEngine.render(
                config.agentLoop.unfinished,
                mapOf(PromptVariables.TERMINAL_TOOL to terminalTool),
            )

        /**
         * Renders the turn that asks a run which answered too early to call [tool] first.
         *
         * @param config the loaded prompt config.
         * @param terminalTool the name of the tool that ends a run by answering the user.
         * @param tool the tool the run had to call before answering.
         * @return the turn to add to the transcript.
         */
        fun renderRequiredTool(config: AgentPromptConfig, terminalTool: String, tool: String): String =
            PromptTemplateEngine.render(
                config.agentLoop.requiredTool,
                mapOf(PromptVariables.TERMINAL_TOOL to terminalTool, PromptVariables.TOOL to tool),
            )

        /**
         * Renders every turn this class words, reaching each text and section, to catch a name typo.
         *
         * @param config the loaded prompt config.
         * @return one message per distinct failure; empty when every batch renders.
         */
        fun problems(config: AgentPromptConfig): List<String> {
            val renders: List<() -> String> = CHECK_BATCHES.map { (calls, results) ->
                { render(config, CHECK_TERMINAL_TOOL, CHECK_CHAR_LIMIT, calls, results) }
            } + { renderUnfinished(config, CHECK_TERMINAL_TOOL) } +
                { renderRequiredTool(config, CHECK_TERMINAL_TOOL, CHECK_REQUIRED_TOOL) }
            return renders.mapNotNull { check ->
                try {
                    check()
                    null
                } catch (e: IllegalArgumentException) {
                    e.message
                }
            }.distinct()
        }

        /** The result's own text: its message, then its data or, for a failure, its details. */
        private fun body(config: AgentPromptConfig, terminalTool: String, result: ToolResult): String {
            val content = buildString {
                append(result.message)
                val extra = if (result.success) result.data else result.error_details
                extra?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
            }
            if (result.success) return content
            val values = mapOf(
                PromptVariables.TERMINAL_TOOL to terminalTool,
                PromptVariables.MESSAGE to content,
            )
            return PromptTemplateEngine.render(config.agentLoop.failed, values)
        }

        private fun truncate(
            config: AgentPromptConfig,
            terminalTool: String,
            text: String,
            limit: Int,
        ): String {
            if (text.length <= limit) return text
            val values = mapOf(
                PromptVariables.TERMINAL_TOOL to terminalTool,
                PromptVariables.KEPT to text.take(limit),
                PromptVariables.COUNT to (text.length - limit).toString(),
            )
            return PromptTemplateEngine.render(config.agentLoop.truncated, values)
        }

        private const val CHECK_TERMINAL_TOOL = "respond"
        private const val CHECK_REQUIRED_TOOL = "web_search"

        /** Small enough that the check batches' results are truncated too. */
        private const val CHECK_CHAR_LIMIT = 8

        /** A mixed batch of two, so a list section is reached twice; and one that succeeds. */
        private val CHECK_BATCHES: List<Pair<List<ToolCall>, List<ToolResult>>> = listOf(
            listOf(ToolCall("read_file", emptyMap()), ToolCall("open_file", emptyMap())) to listOf(
                ToolResult.success("read", "x".repeat(20)),
                ToolResult.failure("not found", "detail"),
            ),
            listOf(ToolCall("open_file", emptyMap())) to listOf(ToolResult.success("opened")),
        )
    }
}
