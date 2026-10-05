package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolCall
import com.itsaky.androidide.plugins.aicore.tool.web.WebAccess

/**
 * What the one activity line says while a run works through its tool calls.
 *
 * A run appends a single bubble and rewrites it as each call starts, so a ten-call run reads as one
 * line rather than twenty messages. Pure and string-injected: the wording is a resource, this is
 * only the choice of what to name.
 */
object AgentActivity {

    /** Longest subject shown; past this the line wraps and stops being a line. */
    const val SUBJECT_LIMIT = 40

    /** Longest argument value [logEntry] keeps; a file's whole content is not the point of the log. */
    const val LOG_ARG_LIMIT = 200

    /** Longest web result [logEntry] keeps: a whole search report, Sources list included. */
    const val LOG_RESULT_LIMIT = 12000

    /**
     * Argument names that say what a call acts on, most specific first. A tool whose arguments
     * are all content (`gradle_sync`, `run_app`) matches none, and shows its name alone.
     */
    private val SUBJECT_ARGS = listOf(
        "file_path",
        "path",
        "build_file",
        "directory",
        "query",
        "template_name",
        "dependency",
    )

    /**
     * The part of [call] worth showing beside the tool name.
     *
     * A path is reduced to its last segment: the full one is what turned the old badge into three
     * wrapped lines, and the file name is what a user recognises.
     *
     * @param call the call about to run.
     * @return the subject, or null when the call has nothing recognisable to name.
     */
    fun subjectOf(call: ToolCall): String? {
        val raw = SUBJECT_ARGS.firstNotNullOfOrNull { key ->
            call.args[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        } ?: return null
        val flat = raw.replace("\n", " ").trim().trimEnd('/')
        val leaf = flat.substringAfterLast('/').ifEmpty { flat }
        // "." and ".." are how a model spells the project root: a name, but not one worth showing.
        if (leaf.isEmpty() || leaf == "." || leaf == "..") return null
        return if (leaf.length <= SUBJECT_LIMIT) leaf else leaf.take(SUBJECT_LIMIT) + "…"
    }

    /**
     * One call and its result, as the exported chat records them: without it a transcript cannot
     * tell a fact the search returned from one the model remembered (ADFA-6223). A web tool keeps
     * its full result; a project tool only its message, so the export does not copy the project.
     *
     * @param call the call that ran.
     * @param result what it returned.
     * @return the entry, its first line naming the call and its arguments.
     */
    fun logEntry(call: ToolCall, result: ToolResult): String = buildString {
        append(if (result.success) "✓ " else "✗ ").append(call.name).append('(')
        append(call.args.entries.joinToString(", ") { (key, value) -> "$key=${clip(value, LOG_ARG_LIMIT)}" })
        append(")\n").append(result.message)
        val extra = if (result.success) result.data else result.error_details
        if (call.name in WebAccess.TOOL_NAMES || !result.success) {
            extra?.takeIf { it.isNotBlank() }?.let { append('\n').append(clip(it, LOG_RESULT_LIMIT)) }
        }
    }

    private fun clip(value: Any?, limit: Int): String {
        val text = value?.toString().orEmpty()
        return if (text.length <= limit) text else text.take(limit) + "…[${text.length - limit} more chars]"
    }

    /**
     * The tool names a run's summary lists: each one once, in the order the run first used it, so
     * a loop that read six files still reads as `read_file`.
     *
     * @param names every executed tool name, in execution order.
     * @return the distinct names, blanks dropped.
     */
    fun distinctNames(names: List<String>): List<String> =
        names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
