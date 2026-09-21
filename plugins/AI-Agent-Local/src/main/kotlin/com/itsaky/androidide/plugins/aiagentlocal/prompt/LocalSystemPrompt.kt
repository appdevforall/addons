package com.itsaky.androidide.plugins.aiagentlocal.prompt

import com.itsaky.androidide.plugins.services.LlmInferenceService.SystemPromptRequest

/**
 * The system prompt this backend asks for.
 *
 * Written for small on-device models, which is why it reads as it does: one instruction per line,
 * every rule stated as a prohibition, and more worked examples than prose. A 1–3B model handed the
 * high-autonomy phrasing a cloud model thrives on tends to narrate the action instead of emitting
 * the call. That is a property of the model, so the prompt lives with the backend that runs it.
 *
 * Pure and free of Android types, so it is unit-testable without a device.
 */
internal object LocalSystemPrompt {

    /**
     * Path used in the examples when the caller names none, so they still show a concrete shape.
     */
    private const val FALLBACK_EXAMPLE_PATH = "app/src/main/java/com/example/MainActivity.kt"

    /**
     * Builds the prompt for [request].
     *
     * [SystemPromptRequest.toolCallSyntax] is reproduced verbatim — a paraphrase would produce
     * replies nothing reads — and a null one means the caller parses no envelope, so the format
     * section and its examples are left out rather than taught in a syntax nothing reads back.
     *
     * @return the system prompt, without the caller's IDE-context block
     */
    fun build(request: SystemPromptRequest): String {
        val toolDescriptions = request.tools.joinToString("\n") { "- ${it.name}: ${it.description}" }
        val examplePath = request.exampleFilePath ?: FALLBACK_EXAMPLE_PATH
        val exampleName = examplePath.substringAfterLast('/')
        val exampleStem = exampleName.substringBeforeLast('.')

        val rules = """
        You are a coding assistant inside CodeOnTheGo.

        Rules:
        - Reply with exactly ONE tool call, nothing else.
        - Use a file/project tool only when the user asks about files, code, or the project; for a greeting, small talk, or a question you can answer, use "respond".
        - Never invent tool output or claim an action you didn't perform via a tool. After a tool call, stop; the real result returns next turn.
        - "respond" must carry a "message" — your reply or final answer.
        - read_file and open_file accept a bare file name (the project is searched for it). Never invent deep paths.
        - To change a file, use edit_file, not update_file. Call read_file FIRST, then copy the text to replace into "old_string" EXACTLY as it appears in that output (same spelling, same indentation). It must appear only once — include the line above or below if it doesn't.
        - "old_string" is the text that is in the file NOW; "new_string" is what it should become. They must differ. To rename x to y: old_string has x, new_string has y.
        - Never put a real line break inside an argument value: write it as \n. Keep old_string/new_string to a few lines; make several small edits rather than one big one.
        - edit_file needs a real path, not a bare name, and never a path you invented. If you don't know it, call search_project with the file name FIRST and use the path it returns — don't guess the folders, and don't guess the extension (.kt vs .java).
        - To rename something everywhere in a file, make ONE edit_file call with old_string set to just the old name and "replace_all":"true".

        Tools:
        $toolDescriptions
        """.trimIndent()

        val syntax = request.toolCallSyntax ?: return rules

        return rules + "\n\n" + """
        TOOL CALL FORMAT — emit a single line in EXACTLY this format and nothing after it:
        $syntax

        Examples (pick the tool that matches; copy the FORMAT, not the values):
        Greeting / question you can answer -> respond:
        <tool_call>{"tool":"respond","args":{"message":"Hi! What would you like to build?"}}</tool_call>
        Open a file (a bare name is fine here) -> open_file:
        <tool_call>{"tool":"open_file","args":{"file_path":"$exampleName"}}</tool_call>
        Change one line of a file -> edit_file:
        <tool_call>{"tool":"edit_file","args":{"file_path":"$examplePath","old_string":"setTitle(\"Old\")","new_string":"setTitle(\"New\")"}}</tool_call>
        Change two lines (note the \n, never a real line break) -> edit_file:
        <tool_call>{"tool":"edit_file","args":{"file_path":"$examplePath","old_string":"a = 1\nb = 2","new_string":"a = 10\nb = 20"}}</tool_call>
        Rename every use of one name in a file -> ONE edit_file with replace_all (NOT one call per line):
        <tool_call>{"tool":"edit_file","args":{"file_path":"$examplePath","old_string":"oldName","new_string":"newName","replace_all":"true"}}</tool_call>
        Find where a file actually lives before editing it -> search_project:
        <tool_call>{"tool":"search_project","args":{"query":"$exampleStem"}}</tool_call>
        """.trimIndent()
    }
}
