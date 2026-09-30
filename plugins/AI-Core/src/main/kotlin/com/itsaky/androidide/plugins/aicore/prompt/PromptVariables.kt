package com.itsaky.androidide.plugins.aicore.prompt

import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService.SystemPromptRequest

/**
 * The values the prompt config is rendered with: its texts, named by YAML path, and the run's.
 * Every key is always present, empty when it does not apply, so a name missing here is a typo in
 * a file and fails the render instead of silently dropping text.
 */
object PromptVariables {

    // Config texts: `identity` is IDENTITY, `tools.heading` is TOOLS_HEADING, and so on.
    const val IDENTITY = "IDENTITY"
    const val RULES = "RULES"
    const val HEADING = "HEADING"
    const val ITEMS = "ITEMS"
    const val TEXT = "TEXT"
    const val TOOLS_HEADING = "TOOLS_HEADING"
    const val TOOL_CALL_FORMAT_INSTRUCTION = "TOOL_CALL_FORMAT_INSTRUCTION"
    const val TOOL_CALL_FORMAT_EXAMPLE_HEADING = "TOOL_CALL_FORMAT_EXAMPLE_HEADING"
    const val TOOL_CALL_FORMAT_EXAMPLE = "TOOL_CALL_FORMAT_EXAMPLE"
    const val IDE_CONTEXT_HEADING = "IDE_CONTEXT_HEADING"
    const val IDE_CONTEXT_CURRENT_FILE = "IDE_CONTEXT_CURRENT_FILE"
    const val IDE_CONTEXT_OTHER_FILES = "IDE_CONTEXT_OTHER_FILES"
    const val IDE_CONTEXT_MODULE_SOURCE_DIR = "IDE_CONTEXT_MODULE_SOURCE_DIR"
    const val IDE_CONTEXT_MODULE_LAYOUT_DIR = "IDE_CONTEXT_MODULE_LAYOUT_DIR"
    const val IDE_CONTEXT_MODULE_MANIFEST = "IDE_CONTEXT_MODULE_MANIFEST"
    const val IDE_CONTEXT_MODULES_KNOWN = "IDE_CONTEXT_MODULES_KNOWN"
    const val IDE_CONTEXT_CLOSING = "IDE_CONTEXT_CLOSING"
    const val SESSION_CURRENT_TIME = "SESSION_CURRENT_TIME"
    const val SESSION_WEB_ACCESS = "SESSION_WEB_ACCESS"
    const val LAYOUT_IDE_CONTEXT = "LAYOUT_IDE_CONTEXT"
    const val AGENT_LOOP_GROUNDING = "AGENT_LOOP_GROUNDING"
    const val AGENT_LOOP_AFTER_SUCCESS = "AGENT_LOOP_AFTER_SUCCESS"
    const val AGENT_LOOP_AFTER_FAILURE = "AGENT_LOOP_AFTER_FAILURE"
    const val CONTEXT_FILES_HEADING = "CONTEXT_FILES_HEADING"
    const val ANSWER_REVIEW_NO_EVIDENCE = "ANSWER_REVIEW_NO_EVIDENCE"

    /** What the user asked, verbatim; inside `layout.answer_review`. */
    const val REQUEST = "REQUEST"

    /** What the run's tools returned, verbatim; inside `layout.answer_review`. Empty when none ran. */
    const val EVIDENCE = "EVIDENCE"

    /** Whether any tool ran, so [EVIDENCE] holds anything; inside `layout.answer_review`. */
    const val HAS_EVIDENCE = "HAS_EVIDENCE"

    /** The answer to check, verbatim; inside `layout.answer_review`. */
    const val DRAFT = "DRAFT"

    /** The line a complete review reply ends on; inside `answer_review.instruction`. */
    const val END_MARKER = "END_MARKER"

    /** A tool batch's results, already in their `<tool_response>` envelopes; inside `layout.tool_results`. */
    const val TOOL_RESPONSES = "TOOL_RESPONSES"

    /** Whether every tool in the batch succeeded; inside `layout.tool_results`. */
    const val ALL_SUCCEEDED = "ALL_SUCCEEDED"

    /** What a failed tool reported, verbatim; inside `agent_loop.failed`. */
    const val MESSAGE = "MESSAGE"

    /** The part of a result kept under the size limit; inside `agent_loop.truncated`. */
    const val KEPT = "KEPT"

    /** How many characters were cut from a result; inside `agent_loop.truncated`. */
    const val COUNT = "COUNT"

    /**
     * The tool the user did not approve, inside `approval`; or the one a run must call before it
     * answers, inside `agent_loop.required_tool`.
     */
    const val TOOL = "TOOL"

    /** What the user typed when asking for a revision, verbatim; inside `approval.corrected_with_instruction`. */
    const val INSTRUCTION = "INSTRUCTION"

    /** How long the approval dialog waited; inside `approval.timed_out`. */
    const val MINUTES = "MINUTES"

    /** The attached files that could be read, each with [NAME] and [CONTENT]; inside `layout.context_files`. */
    const val FILES = "FILES"

    /** The chat's first user message, cut to its start; inside `layout.chat_title`. */
    const val USER_TEXT = "USER_TEXT"

    /** The agent's reply to it, cut to its start; inside `layout.chat_title`. */
    const val REPLY_TEXT = "REPLY_TEXT"

    /** An attached file's text, verbatim; inside `{{#FILES}}`. */
    const val CONTENT = "CONTENT"

    /** The tool that ends a run by answering the user. */
    const val TERMINAL_TOOL = "TERMINAL_TOOL"

    /** The tools this run offers, each with [NAME] and [DESCRIPTION]. */
    const val TOOLS = "TOOLS"

    /** The tool-call envelope; null under native calling, where the text protocol is not taught. */
    const val TOOL_CALL_SYNTAX = "TOOL_CALL_SYNTAX"

    /** A real project path to show in examples. */
    const val EXAMPLE_FILE_PATH = "EXAMPLE_FILE_PATH"

    /** The device's date, time and time zone; see [SessionContext]. */
    const val CURRENT_TIME = "CURRENT_TIME"

    /** Whether the IDE has anything open or any module worth stating. */
    const val HAS_IDE_CONTEXT = "HAS_IDE_CONTEXT"

    /** The focused file, or null. */
    const val CURRENT_FILE = "CURRENT_FILE"

    /** The other open tabs, comma separated; empty when there are none. */
    const val OTHER_FILES = "OTHER_FILES"

    /** The project's modules, each with [NAME], [SOURCE_DIR], [LAYOUT_DIR] and [MANIFEST]. */
    const val MODULES = "MODULES"

    /** Whether [MODULES] has any, for text stated once rather than per module. */
    const val HAS_MODULES = "HAS_MODULES"

    /** A tool's, a module's or an attached file's name, inside `{{#TOOLS}}`, `{{#MODULES}}` or `{{#FILES}}`. */
    const val NAME = "NAME"

    /** A tool's description, inside `{{#TOOLS}}`. */
    const val DESCRIPTION = "DESCRIPTION"

    /** A module's source directory, or null; inside `{{#MODULES}}`. */
    const val SOURCE_DIR = "SOURCE_DIR"

    /** A module's layout directory, or null; inside `{{#MODULES}}`. */
    const val LAYOUT_DIR = "LAYOUT_DIR"

    /** A module's manifest, or null; inside `{{#MODULES}}`. */
    const val MANIFEST = "MANIFEST"

    /**
     * Collects every value `layout.system_prompt` may use.
     *
     * @param config the loaded prompt config.
     * @param request the tool list, envelope syntax and example path this run needs described.
     * @param terminalTool the name of the tool that ends a run by answering the user.
     * @param context what the IDE has open.
     * @param session the clock.
     * @return the values, keyed by name.
     */
    fun collect(
        config: AgentPromptConfig,
        request: SystemPromptRequest,
        terminalTool: String,
        context: IdeContext,
        session: SessionContext,
    ): Map<String, Any?> = ideContext(config, context, session) + mapOf(
        IDENTITY to config.identity,
        RULES to config.rules.map { group ->
            mapOf(HEADING to group.heading, ITEMS to group.items.map { mapOf(TEXT to it) })
        },
        TOOLS_HEADING to config.tools.heading,
        TOOL_CALL_FORMAT_INSTRUCTION to config.toolCallFormat.instruction,
        TOOL_CALL_FORMAT_EXAMPLE_HEADING to config.toolCallFormat.exampleHeading,
        TOOL_CALL_FORMAT_EXAMPLE to config.toolCallFormat.example,
        LAYOUT_IDE_CONTEXT to config.layout.ideContext,
        TERMINAL_TOOL to terminalTool,
        // Plain Strings, so a contributed tool's description is never rendered as a template.
        TOOLS to request.tools.map { mapOf(NAME to it.name, DESCRIPTION to it.description) },
        EXAMPLE_FILE_PATH to (request.exampleFilePath ?: IdeContext.FALLBACK_EXAMPLE_PATH),
        // Under native calling teaching an envelope too invites both, and the text one runs twice.
        TOOL_CALL_SYNTAX to request.toolCallSyntax?.takeIf { it.isNotBlank() },
    )

    /**
     * Collects the values `layout.tool_results` uses.
     *
     * @param config the loaded prompt config.
     * @param terminalTool the name of the tool that ends a run by answering the user.
     * @param toolResponses the batch's results, already enveloped; a plain String, so never rescanned.
     * @param allSucceeded whether every tool in the batch succeeded.
     * @return the values, keyed by name.
     */
    fun toolResults(
        config: AgentPromptConfig,
        terminalTool: String,
        toolResponses: String,
        allSucceeded: Boolean,
    ): Map<String, Any?> = mapOf(
        AGENT_LOOP_GROUNDING to config.agentLoop.grounding,
        AGENT_LOOP_AFTER_SUCCESS to config.agentLoop.afterSuccess,
        AGENT_LOOP_AFTER_FAILURE to config.agentLoop.afterFailure,
        TERMINAL_TOOL to terminalTool,
        TOOL_RESPONSES to toolResponses,
        ALL_SUCCEEDED to allSucceeded,
    )

    /**
     * Collects the values `layout.context_files` uses.
     *
     * @param config the loaded prompt config.
     * @param files each readable attachment's name and text; plain Strings, so never rescanned.
     * @return the values, keyed by name.
     */
    fun contextFiles(config: AgentPromptConfig, files: List<Pair<String, String>>): Map<String, Any?> = mapOf(
        CONTEXT_FILES_HEADING to config.contextFiles.heading,
        FILES to files.map { (name, content) -> mapOf(NAME to name, CONTENT to content) },
    )

    /**
     * Collects the values `layout.ide_context` uses, for appending it to a backend's own prompt.
     *
     * @param config the loaded prompt config.
     * @param context what the IDE has open.
     * @param session the clock.
     * @return the values, keyed by name.
     */
    fun ideContext(
        config: AgentPromptConfig,
        context: IdeContext,
        session: SessionContext,
    ): Map<String, Any?> {
        val text = config.ideContext
        return mapOf(
            SESSION_CURRENT_TIME to config.session.currentTime,
            SESSION_WEB_ACCESS to config.session.webAccess,
            CURRENT_TIME to session.currentTime,
            IDE_CONTEXT_HEADING to text.heading,
            IDE_CONTEXT_CURRENT_FILE to text.currentFile,
            IDE_CONTEXT_OTHER_FILES to text.otherFiles,
            IDE_CONTEXT_MODULE_SOURCE_DIR to text.moduleSourceDir,
            IDE_CONTEXT_MODULE_LAYOUT_DIR to text.moduleLayoutDir,
            IDE_CONTEXT_MODULE_MANIFEST to text.moduleManifest,
            IDE_CONTEXT_MODULES_KNOWN to text.modulesKnown,
            IDE_CONTEXT_CLOSING to text.closing,
            HAS_IDE_CONTEXT to !context.isEmpty,
            CURRENT_FILE to context.currentFile,
            OTHER_FILES to context.otherFiles.joinToString(", "),
            MODULES to context.modules.map {
                mapOf(NAME to it.name, SOURCE_DIR to it.sourceDir, LAYOUT_DIR to it.layoutDir, MANIFEST to it.manifest)
            },
            HAS_MODULES to context.modules.isNotEmpty(),
        )
    }
}
