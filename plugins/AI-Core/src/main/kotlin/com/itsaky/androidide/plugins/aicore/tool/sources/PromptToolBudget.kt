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

    /** Contributed tools admitted into the prompt. Built-ins are never counted or dropped. */
    const val MAX_CONTRIBUTED_TOOLS = 12

    /** Per-description cap, applied to contributed descriptions only. */
    const val MAX_DESCRIPTION_CHARS = 200

    /** Argument names spelled out per contributed tool; one schema can declare dozens. */
    const val MAX_ARGUMENT_NAMES = 12

    /** Per-name cap, so one absurd property name cannot crowd out the rest of the list. */
    private const val MAX_ARGUMENT_NAME_CHARS = 40

    /**
     * The tool list as the prompt will see it.
     * @property definitions what to hand the backend.
     * @property droppedTools names left out by [MAX_CONTRIBUTED_TOOLS], for the caller to log.
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
     * @return the definitions to send plus what the budget cost.
     */
    fun apply(handlers: List<ToolHandler>): Budgeted {
        val definitions = mutableListOf<LlmInferenceService.ToolDefinition>()
        val dropped = mutableListOf<String>()
        var admitted = 0
        var truncated = 0

        for (handler in handlers) {
            if (handler !is ContributedToolHandler) {
                definitions += LlmInferenceService.ToolDefinition(
                    handler.toolName,
                    handler.description,
                    handler.parametersSchema,
                )
                continue
            }

            if (admitted >= MAX_CONTRIBUTED_TOOLS) {
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
