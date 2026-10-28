package com.itsaky.androidide.plugins.aicore.tool.sources

import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.services.LlmInferenceService

/**
 * Decides how much of the contributed tool list is allowed into the system prompt.
 *
 * Every backend renders the tool list itself, so the cap has to happen once here, before the list
 * crosses the plugin boundary — including into backends nobody here has written. One MCP server can
 * advertise ninety tools and exhaust a phone-sized context window on its own.
 */
object PromptToolBudget {

    /**
     * Contributed tools admitted into the prompt. Built-ins are never counted against it, and are
     * left out only when a caller passes an explicit allowlist (see [COMPACT_BUILT_INS]).
     */
    const val MAX_CONTRIBUTED_TOOLS = 12

    /** Per-description cap, applied to contributed descriptions only. */
    const val MAX_DESCRIPTION_CHARS = 200

    /** Argument names spelled out per contributed tool; one schema can declare dozens. */
    const val MAX_ARGUMENT_NAMES = 12

    /** Per-name cap, so one absurd property name cannot crowd out the rest of the list. */
    private const val MAX_ARGUMENT_NAME_CHARS = 40

    /**
     * The built-in tools a small on-device model is offered: reading and navigating the project,
     * changing files, and running a Gradle task with its build output. The full list is about 4,000
     * characters, over half of the local model's opening prompt, and every token of it is read before
     * the model answers; these ten are about two-fifths of that (ADFA-6757).
     *
     * `run_gradle_task` is in because a model without any way to run a build or the tests does not say
     * so: asked to run the tests on a Pixel 6 Pro, it called `read_build_output` with an argument it
     * made up. A build is the one request a coding assistant cannot answer with its eyes alone.
     * `list_gradle_tasks` comes with it because `run_gradle_task`'s own description tells the model to
     * call it when unsure of a task's name, and the grammar would not let it. `gradle_sync` comes with
     * the pair because their results send the model to it when the project has not synced, and an
     * unsynced project blocks both.
     *
     * An allowlist on purpose: a tool added to the app later stays out of a small model's prompt
     * until someone decides it earns its tokens. Leaving a tool out also leaves it out of the grammar
     * that constrains the model, so it cannot call one its prompt never mentioned.
     */
    val COMPACT_BUILT_INS: Set<String> = setOf(
        "read_file",
        "list_files",
        "search_project",
        "open_file",
        "read_build_output",
        "create_file",
        "edit_file",
        "run_gradle_task",
        "list_gradle_tasks",
        "gradle_sync",
    )

    /**
     * Contributed tools admitted when the built-ins are [COMPACT_BUILT_INS], in place of
     * [MAX_CONTRIBUTED_TOOLS]. Twelve contributed descriptions at [MAX_DESCRIPTION_CHARS] each can run
     * to more text than the whole compact built-in list, so without this a user with several tool
     * sources would read the saving back as prompt.
     */
    const val COMPACT_MAX_CONTRIBUTED_TOOLS = 4

    /** The largest context window, in tokens, that still gets [COMPACT_BUILT_INS]. */
    const val COMPACT_UP_TO_TOKENS = 32_768

    /**
     * Whether a run should be offered [COMPACT_BUILT_INS] rather than every built-in.
     *
     * All three conditions, because none alone describes a small on-device model. A window can be
     * small on a natively calling backend too, since the OpenAI backend takes one from the user for
     * any server they point it at, and dropping that user's tools would be a surprise. A text-protocol
     * backend with room to spare can afford the full list. And the local backend caps its window at
     * 16,384 whatever the model, so the window alone cannot tell a phone-sized model from a larger one:
     * the user's own choice does. Turning the short prompt off hands back this plugin's full prompt,
     * and with it every tool.
     *
     * An unknown window (0) is not compact, on purpose: a backend that reports nothing may be one that
     * can afford the full list. The on-device model reports 0 until its context has been sized, which
     * follows opening the model file and is not instant, so a message sent before then gets the full
     * set and every later one the compact set.
     *
     * @param callsToolsNatively whether the backend carries tool calls in its provider's own API.
     * @param hasOwnPrompt whether the backend supplies its own, short system prompt for this run.
     * @param contextWindowTokens the window the backend reports; 0 when it reports none.
     * @return true for a text-protocol backend with its own prompt and a known window of at most
     *   [COMPACT_UP_TO_TOKENS].
     */
    fun isCompact(callsToolsNatively: Boolean, hasOwnPrompt: Boolean, contextWindowTokens: Int): Boolean =
        !callsToolsNatively && hasOwnPrompt && contextWindowTokens in 1..COMPACT_UP_TO_TOKENS

    /**
     * The tool list as the prompt will see it.
     * @property definitions what to hand the backend.
     * @property droppedTools contributed names left out by the cap, for the caller to log.
     * @property truncatedDescriptions how many descriptions were shortened.
     */
    data class Budgeted(
        val definitions: List<LlmInferenceService.ToolDefinition>,
        val droppedTools: List<String>,
        val truncatedDescriptions: Int,
    )

    /**
     * Applies the budget to a tool set.
     *
     * Contributed descriptions are also flattened to a single line: they are provider-supplied text
     * that lands verbatim in a system prompt assembled inside a third-party plugin, where a
     * newline is enough to forge structure.
     *
     * @param handlers the registered handlers, built-in and contributed.
     * @param builtIns the built-in tool names to keep, or null for every one.
     * @param maxContributed how many contributed tools to admit; [MAX_CONTRIBUTED_TOOLS] unless the
     *   caller is offering [COMPACT_BUILT_INS], when it is [COMPACT_MAX_CONTRIBUTED_TOOLS].
     * @return the definitions to send plus what the budget cost.
     */
    fun apply(
        handlers: List<ToolHandler>,
        builtIns: Set<String>? = null,
        maxContributed: Int = MAX_CONTRIBUTED_TOOLS,
    ): Budgeted {
        val definitions = mutableListOf<LlmInferenceService.ToolDefinition>()
        val dropped = mutableListOf<String>()
        var admitted = 0
        var truncated = 0

        for (handler in handlers) {
            if (handler !is ContributedToolHandler) {
                if (builtIns != null && handler.toolName !in builtIns) continue
                definitions += LlmInferenceService.ToolDefinition(
                    handler.toolName,
                    handler.description,
                    handler.parametersSchema,
                )
                continue
            }

            if (admitted >= maxContributed) {
                dropped += handler.toolName
                continue
            }
            admitted++

            val flattened = ContributedText.flatten(handler.description)
            val description = if (flattened.length > MAX_DESCRIPTION_CHARS) {
                truncated++
                flattened.take(MAX_DESCRIPTION_CHARS).trimEnd() + "…"
            } else {
                flattened
            }
            definitions += LlmInferenceService.ToolDefinition(
                handler.toolName,
                description + argumentHint(handler.parametersSchema),
                handler.parametersSchema,
            )
        }

        return Budgeted(definitions, dropped, truncated)
    }

    /**
     * The argument names a contributed tool takes, as a clause to append to its prompt description.
     *
     * Every backend renders a tool as its name and description alone, so a schema that stops here
     * leaves the model guessing argument names — and it guesses the snake_case shape the built-in
     * tools use, spending a turn on `repo_name` before a failure names `repoName` back to it.
     *
     * @param schema the tool's JSON Schema, as its provider supplied it.
     * @return the clause to append, or empty when the schema names no properties.
     */
    private fun argumentHint(schema: Map<String, Any>): String {
        val required = (schema["required"] as? Collection<*>)?.mapNotNull { it as? String }.orEmpty().toSet()
        val names = (schema["properties"] as? Map<*, *>)?.keys.orEmpty()
            .mapNotNull { it as? String }
            .filter { it.isNotBlank() }
        if (names.isEmpty()) return ""

        val kept = names.take(MAX_ARGUMENT_NAMES)
        val listed = kept.joinToString(", ") { name ->
            // Flattened and capped like every other provider string reaching the prompt.
            val label = ContributedText.label(name, MAX_ARGUMENT_NAME_CHARS)
            if (name in required) "$label (required)" else label
        }
        val elided = if (names.size > kept.size) ", and ${names.size - kept.size} more" else ""
        return " Arguments, spelled exactly as written: $listed$elided."
    }
}
