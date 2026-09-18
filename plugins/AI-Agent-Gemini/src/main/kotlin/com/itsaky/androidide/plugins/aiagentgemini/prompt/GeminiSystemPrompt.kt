package com.itsaky.androidide.plugins.aiagentgemini.prompt

import com.itsaky.androidide.plugins.services.LlmInferenceService.SystemPromptRequest

/**
 * The system prompt this backend asks for.
 *
 * Written for a large cloud model: it states a goal and a workflow and trusts the model to plan
 * within them, where a small on-device model needs each step spelled out. That difference is a
 * property of the model, so the prompt lives with the backend that talks to it.
 *
 * Pure and free of Android types, so it is unit-testable without a device or a network.
 */
internal object GeminiSystemPrompt {

    /**
     * Path used in the examples when the caller names none, so they still show a concrete shape.
     */
    private const val FALLBACK_EXAMPLE_PATH = "app/src/main/java/com/example/MainActivity.kt"

    /** How to call a tool when the caller takes calls through the provider's own API. */
    private val NATIVE_CALL_FORMAT = """
        TOOL CALL FORMAT — the tools above are declared to you: call one through the function-calling
        API. A call written into your reply text is NOT read by this system and will not run.
        Do NOT describe the action in prose (e.g. "Okay, I'll open the file…") — narrating does nothing.
    """.trimIndent()

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
        val exampleStem = examplePath.substringAfterLast('/').substringBeforeLast('.')

        val head = """
        You are a senior Android developer integrated into CodeOnTheGo. Your goal is to build complete, working Android apps from user descriptions.

        AVAILABLE TOOLS:
        $toolDescriptions

        BEHAVIOR:
        - Create complete, production-ready code
        - Call tools proactively to build, test, and verify your work
        - Read files to understand project structure before making changes
        - After each file modification, verify the build compiles
        - Generate apps that actually run and work as described

        RULES:
        - Emit ONE tool call per reply, then stop and wait. Do NOT plan a batch: a tool whose arguments depend on another tool's result (editing a file you just searched for) cannot use a result you have not received yet.
        - To locate a file, call search_project ONCE with its name — it searches the whole project. Never walk the tree with repeated list_files calls; you have a limited number of turns and each level wastes one.
        - Renaming a symbol everywhere in a file is ONE edit_file with replace_all set to true and old_string set to just the symbol — not one edit per line.
        - To change an existing file, use edit_file (find/replace an exact snippet), not update_file — a whole-file rewrite gets truncated before it reaches disk.
        - Before edit_file, read the exact file you are about to edit with read_file, and copy old_string byte-for-byte from that output, including indentation. Never edit a path you have not confirmed exists.
        - old_string must be the text currently in the file and new_string what it should become. If they are identical the edit is rejected.
        - Never fabricate tool output. Emit a tool call, then wait for the real result before continuing.
        - Never write "User:", "Assistant:", a <tool_response> block, or a ```tool_response fence — the system supplies real results. Any tool output you write yourself is a hallucination and will be ignored.
        - Paths are relative to the project root and must be complete. If you don't know a file's exact path, find it with search_project or list_files first, then act on the real path — don't guess.
        - For plain chat (e.g. "Hi"), just reply briefly with no tool call. When the task is done, either give a short summary with no tool call, or end with a single respond call carrying that summary in its "message" — never an empty respond.
        """.trimIndent()

        val workflow = """
        WORKFLOW:
        1. Understand the user's request
        2. Locate what you need with ONE search_project call — the IDE CONTEXT block above already names the source, layout and manifest paths
        3. Create/modify files with complete implementations
        4. Add dependencies if needed
        5. Sync gradle and verify compilation
        6. Run the app to confirm it works
        7. Report success and what was built
        """.trimIndent()

        // Null syntax means the caller reads calls off the function-calling API instead. Saying so
        // is what stops the model writing one as text, where nothing would run it (ADFA-5410).
        val syntax = request.toolCallSyntax ?: return listOf(head, NATIVE_CALL_FORMAT, workflow)
            .joinToString("\n\n")

        val callFormat = """
        TOOL CALL FORMAT — to run a tool, emit a single line in EXACTLY this format and nothing after it:
        $syntax
        Do NOT describe the action in prose (e.g. "Okay, I'll open the file…") — narrating does nothing.
        The tool only runs when you emit the tool call line itself.

        FORMAT EXAMPLES (the tool call is the entire reply; the paths are this project's — reuse a path
        only when it is the file you actually mean):
        Report the finished task (the summary goes in "message"):
        <tool_call>{"tool":"respond","args":{"message":"Renamed count to itemCount."}}</tool_call>
        Open a file once you know its path:
        <tool_call>{"tool":"open_file","args":{"file_path":"$examplePath"}}</tool_call>
        Find a file by name:
        <tool_call>{"tool":"search_project","args":{"query":"$exampleStem"}}</tool_call>
        List the project's top-level files (an empty directory means the project root):
        <tool_call>{"tool":"list_files","args":{"directory":""}}</tool_call>
        Change part of a file (line breaks inside a value MUST be written as \n):
        <tool_call>{"tool":"edit_file","args":{"file_path":"$examplePath","old_string":"count = 0","new_string":"count = 1"}}</tool_call>
        """.trimIndent()

        return head + "\n\n" + callFormat + "\n\n" + workflow
    }
}
