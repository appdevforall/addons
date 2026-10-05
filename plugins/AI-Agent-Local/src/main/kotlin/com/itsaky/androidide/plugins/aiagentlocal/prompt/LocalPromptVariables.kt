package com.itsaky.androidide.plugins.aiagentlocal.prompt

import com.itsaky.androidide.plugins.aiagentlocal.prompt.config.LocalPromptConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService.SystemPromptRequest

/**
 * The values the prompt config is rendered with: its texts, named by YAML path, and the request's.
 * Every key is always present, empty when it does not apply, so a name missing here is a typo in
 * a file and fails the render instead of silently dropping text.
 */
internal object LocalPromptVariables {

    // Config texts: `identity` is IDENTITY, `tools.heading` is TOOLS_HEADING, and so on.
    const val IDENTITY = "IDENTITY"
    const val RULES = "RULES"
    const val HEADING = "HEADING"
    const val ITEMS = "ITEMS"
    const val TEXT = "TEXT"
    const val TOOLS_HEADING = "TOOLS_HEADING"
    const val TOOL_CALL_FORMAT_TEXT_INSTRUCTION = "TOOL_CALL_FORMAT_TEXT_INSTRUCTION"
    const val TOOL_CALL_FORMAT_TEXT_EXAMPLES_HEADING = "TOOL_CALL_FORMAT_TEXT_EXAMPLES_HEADING"
    const val TOOL_CALL_FORMAT_TEXT_EXAMPLES = "TOOL_CALL_FORMAT_TEXT_EXAMPLES"
    const val PURPOSE = "PURPOSE"
    const val CALL = "CALL"

    /** The tools the request offers, each with [NAME] and [DESCRIPTION]. */
    const val TOOLS = "TOOLS"

    /** The tool-call envelope; null when the caller parses none, which drops the call format. */
    const val TOOL_CALL_SYNTAX = "TOOL_CALL_SYNTAX"

    /** A real project path to show in examples. */
    const val EXAMPLE_FILE_PATH = "EXAMPLE_FILE_PATH"

    /** [EXAMPLE_FILE_PATH]'s bare file name, which read_file and open_file accept. */
    const val EXAMPLE_FILE_NAME = "EXAMPLE_FILE_NAME"

    /** [EXAMPLE_FILE_NAME] without its extension, for search examples. */
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
    fun collect(config: LocalPromptConfig, request: SystemPromptRequest): Map<String, Any?> {
        val examplePath = request.exampleFilePath ?: FALLBACK_EXAMPLE_PATH
        val exampleName = examplePath.substringAfterLast('/')
        val format = config.toolCallFormat.text
        return mapOf(
            IDENTITY to config.identity,
            RULES to config.rules.map { group ->
                mapOf(HEADING to group.heading, ITEMS to group.items.map { mapOf(TEXT to it) })
            },
            TOOLS_HEADING to config.tools.heading,
            TOOL_CALL_FORMAT_TEXT_INSTRUCTION to format.instruction,
            TOOL_CALL_FORMAT_TEXT_EXAMPLES_HEADING to format.examplesHeading,
            TOOL_CALL_FORMAT_TEXT_EXAMPLES to format.examples.map {
                mapOf(PURPOSE to it.purpose, CALL to it.call)
            },
            // Plain Strings, so a contributed tool's description is never rendered as a template.
            TOOLS to request.tools.map { mapOf(NAME to it.name, DESCRIPTION to it.description) },
            EXAMPLE_FILE_PATH to examplePath,
            EXAMPLE_FILE_NAME to exampleName,
            // A dotfile's name is all extension, so its stem would be an empty search.
            EXAMPLE_FILE_STEM to exampleName.substringBeforeLast('.').ifEmpty { exampleName },
            // Null syntax: the caller parses no envelope, so none is taught (ADFA-5410).
            TOOL_CALL_SYNTAX to request.toolCallSyntax,
        )
    }
}
