package com.itsaky.androidide.plugins.aiagentclaude.prompt

import com.itsaky.androidide.plugins.aiagentclaude.prompt.config.ClaudePromptConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService.SystemPromptRequest

/**
 * The values the prompt config is rendered with: its texts, named by YAML path, and the request's.
 * Every key is always present, empty when it does not apply, so a name missing here is a typo in
 * a file and fails the render instead of silently dropping text.
 */
internal object ClaudePromptVariables {

    // Config texts: `identity` is IDENTITY, `scope.heading` is SCOPE_HEADING, and so on.
    const val IDENTITY = "IDENTITY"
    const val SCOPE_HEADING = "SCOPE_HEADING"
    const val SCOPE_ITEMS = "SCOPE_ITEMS"
    const val RULES = "RULES"
    const val HEADING = "HEADING"
    const val ITEMS = "ITEMS"
    const val TEXT = "TEXT"
    const val BEHAVIOR_HEADING = "BEHAVIOR_HEADING"
    const val BEHAVIOR_ITEMS = "BEHAVIOR_ITEMS"
    const val WORKFLOW_HEADING = "WORKFLOW_HEADING"
    const val WORKFLOW_STEPS = "WORKFLOW_STEPS"
    const val WORKFLOW_CLOSING = "WORKFLOW_CLOSING"
    const val TOOLS_HEADING = "TOOLS_HEADING"
    const val TOOL_CALL_FORMAT_NO_NARRATION = "TOOL_CALL_FORMAT_NO_NARRATION"
    const val TOOL_CALL_FORMAT_NATIVE = "TOOL_CALL_FORMAT_NATIVE"
    const val TOOL_CALL_FORMAT_TEXT_INSTRUCTION = "TOOL_CALL_FORMAT_TEXT_INSTRUCTION"
    const val TOOL_CALL_FORMAT_TEXT_ONLY_THE_LINE_RUNS = "TOOL_CALL_FORMAT_TEXT_ONLY_THE_LINE_RUNS"
    const val TOOL_CALL_FORMAT_TEXT_EXAMPLES_HEADING = "TOOL_CALL_FORMAT_TEXT_EXAMPLES_HEADING"
    const val TOOL_CALL_FORMAT_TEXT_EXAMPLES = "TOOL_CALL_FORMAT_TEXT_EXAMPLES"
    const val PURPOSE = "PURPOSE"
    const val CALL = "CALL"

    /** A workflow step's 1-based position, inside `{{#WORKFLOW_STEPS}}`. */
    const val NUMBER = "NUMBER"

    /** The tools the request offers, each with [NAME] and [DESCRIPTION]. */
    const val TOOLS = "TOOLS"

    /** The tool-call envelope; null when calls travel through the function-calling API. */
    const val TOOL_CALL_SYNTAX = "TOOL_CALL_SYNTAX"

    /** Whether calls travel through the function-calling API rather than the reply text. */
    const val NATIVE_TOOL_CALLS = "NATIVE_TOOL_CALLS"

    /** A real project path to show in examples. */
    const val EXAMPLE_FILE_PATH = "EXAMPLE_FILE_PATH"

    /** [EXAMPLE_FILE_PATH]'s file name without folder or extension, for search examples. */
    const val EXAMPLE_FILE_STEM = "EXAMPLE_FILE_STEM"

    /** A tool's name, inside `{{#TOOLS}}`. */
    const val NAME = "NAME"

    /** A tool's description, inside `{{#TOOLS}}`. */
    const val DESCRIPTION = "DESCRIPTION"

    /** Path used in examples when the caller names none, so they still show a concrete shape. */
    const val FALLBACK_EXAMPLE_PATH = "app/src/main/java/com/example/MainActivity.kt"

    /**
     * Collects every value `layout.system_prompt` may use.
     *
     * @param config the loaded prompt config.
     * @param request the tool list, envelope syntax and example path to describe.
     * @return the values, keyed by name.
     */
    fun collect(config: ClaudePromptConfig, request: SystemPromptRequest): Map<String, Any?> {
        val examplePath = request.exampleFilePath ?: FALLBACK_EXAMPLE_PATH
        val format = config.toolCallFormat
        return mapOf(
            IDENTITY to config.identity,
            SCOPE_HEADING to config.scope.heading,
            SCOPE_ITEMS to config.scope.items.map { mapOf(TEXT to it) },
            RULES to config.rules.map { group ->
                mapOf(HEADING to group.heading, ITEMS to group.items.map { mapOf(TEXT to it) })
            },
            BEHAVIOR_HEADING to config.behavior.heading,
            BEHAVIOR_ITEMS to config.behavior.items.map { mapOf(TEXT to it) },
            WORKFLOW_HEADING to config.workflow.heading,
            WORKFLOW_STEPS to config.workflow.steps.mapIndexed { index, step ->
                mapOf(NUMBER to (index + 1).toString(), TEXT to step)
            },
            WORKFLOW_CLOSING to config.workflow.closing,
            TOOLS_HEADING to config.tools.heading,
            TOOL_CALL_FORMAT_NO_NARRATION to format.noNarration,
            TOOL_CALL_FORMAT_NATIVE to format.native,
            TOOL_CALL_FORMAT_TEXT_INSTRUCTION to format.text.instruction,
            TOOL_CALL_FORMAT_TEXT_ONLY_THE_LINE_RUNS to format.text.onlyTheLineRuns,
            TOOL_CALL_FORMAT_TEXT_EXAMPLES_HEADING to format.text.examplesHeading,
            TOOL_CALL_FORMAT_TEXT_EXAMPLES to format.text.examples.map {
                mapOf(PURPOSE to it.purpose, CALL to it.call)
            },
            // Plain Strings, so a contributed tool's description is never rendered as a template.
            TOOLS to request.tools.map { mapOf(NAME to it.name, DESCRIPTION to it.description) },
            EXAMPLE_FILE_PATH to examplePath,
            // A dotfile's name is all extension, so its stem would be an empty search.
            EXAMPLE_FILE_STEM to examplePath.substringAfterLast('/').let { name ->
                name.substringBeforeLast('.').ifEmpty { name }
            },
            // Null syntax: calls arrive via the function-calling API, not the text (ADFA-5410).
            TOOL_CALL_SYNTAX to request.toolCallSyntax,
            NATIVE_TOOL_CALLS to (request.toolCallSyntax == null),
        )
    }
}
