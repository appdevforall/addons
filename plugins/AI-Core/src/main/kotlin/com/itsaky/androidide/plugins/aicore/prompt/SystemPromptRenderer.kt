package com.itsaky.androidide.plugins.aicore.prompt

import com.itsaky.androidide.plugins.ai.prompt.PromptTemplateEngine
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService.SystemPromptRequest
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition

/**
 * Renders the prompt from `layout.yml`'s layouts in one pass. Knows no wording: that is the config's,
 * and the values are [PromptVariables]'. Pure and thread-safe.
 */
object SystemPromptRenderer {

    /**
     * Renders `layout.system_prompt`, the whole prompt for a backend that supplies none of its own.
     *
     * @param config the loaded prompt config.
     * @param request the tools, envelope syntax and example path this run needs described.
     * @param terminalTool the name of the tool that ends a run by answering the user.
     * @param context what the IDE has open.
     * @param session the clock.
     * @return the system prompt.
     */
    fun render(
        config: AgentPromptConfig,
        request: SystemPromptRequest,
        terminalTool: String,
        context: IdeContext,
        session: SessionContext,
    ): String = PromptTemplateEngine.render(
        config.layout.systemPrompt,
        PromptVariables.collect(config, request, terminalTool, context, session),
    ).trimEnd()

    /**
     * Renders `layout.ide_context` alone, to append to a backend's own prompt.
     *
     * @param config the loaded prompt config.
     * @param context what the IDE has open.
     * @param session the clock.
     * @return the block; the session lines alone when the IDE has nothing open.
     */
    fun renderIdeContext(config: AgentPromptConfig, context: IdeContext, session: SessionContext): String =
        PromptTemplateEngine.render(
            config.layout.ideContext,
            PromptVariables.ideContext(config, context, session),
        ).trimEnd()

    /**
     * Renders both layouts against runs that open and close every section, to catch a name typo.
     *
     * @param config the loaded prompt config.
     * @return one message per distinct failure; empty when every run renders.
     */
    fun problems(config: AgentPromptConfig): List<String> =
        CHECK_RUNS.flatMap { (request, context, session) ->
            listOf(
                { render(config, request, CHECK_TERMINAL_TOOL, context, session) },
                { renderIdeContext(config, context, session) },
            ).mapNotNull { run ->
                try {
                    run()
                    null
                } catch (e: IllegalArgumentException) {
                    e.message
                }
            }
        }.distinct()

    private const val CHECK_TERMINAL_TOOL = "respond"

    /** Two of everything, so a `{{^FIRST}}` inside a list is reached too; and nothing at all. */
    private val CHECK_RUNS: List<Triple<SystemPromptRequest, IdeContext, SessionContext>> = run {
        val tools = listOf(
            ToolDefinition("read_file", "Read a file.", emptyMap()),
            ToolDefinition("respond", "Reply.", emptyMap()),
        )
        val full = ProjectLayout.Module("app", "app/src", "app/res/layout", "app/AndroidManifest.xml")
        val bare = ProjectLayout.Module("lib", null, null, null)
        val session = SessionContext("Monday, 1 January 2029, 09:00 (UTC, UTC+00:00)")
        listOf(
            Triple(
                SystemPromptRequest(tools, "<tool_call>…</tool_call>", "app/Main.kt"),
                IdeContext("app/Main.kt", listOf("lib/A.kt", "lib/B.kt"), listOf(full, bare)),
                session,
            ),
            Triple(SystemPromptRequest(emptyList(), null, null), IdeContext(null, emptyList(), listOf(bare)), session),
            Triple(SystemPromptRequest(emptyList(), null, null), IdeContext.EMPTY, session),
        )
    }
}
