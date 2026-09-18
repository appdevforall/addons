package com.itsaky.androidide.plugins.aicore.fragments

import com.itsaky.androidide.plugins.aicore.tool.handlers.EditFileHandler
import com.itsaky.androidide.plugins.aicore.tool.parseToolBoolean
import org.json.JSONObject

/**
 * Renders a pending tool call as the text the approval dialog shows. Split from
 * [ApprovalDialogFragment] because informed consent is decided here, and a pure function of the
 * arguments is testable without a fragment harness. Values are truncated on purpose.
 */
object ApprovalTextFormatter {

    /** Per-value cap in the generic argument dump. */
    private const val MAX_VALUE_CHARS = 200

    /** Per-side cap in the edit preview; long enough for a real hunk, short enough to read. */
    private const val MAX_SNIPPET_CHARS = 600

    /**
     * Renders the proposed edit as a diff-style before/after block.
     * @param args the `edit_file` call arguments.
     * @return the preview text.
     */
    fun formatEdit(args: Map<String, Any?>): String {
        val path = args[EditFileHandler.ARG_PATH]?.toString().orEmpty()
        val old = args[EditFileHandler.ARG_OLD]?.toString().orEmpty()
        val new = args[EditFileHandler.ARG_NEW]?.toString().orEmpty()
        // Shared with the handler's parsing, so the "every occurrence" warning cannot disagree.
        val replaceAll = parseToolBoolean(args[EditFileHandler.ARG_REPLACE_ALL])

        return buildString {
            append(path).append("\n\n")
            append("— Remove —\n")
            append(diffBlock(old, "- "))
            append("\n\n")
            if (new.isEmpty()) {
                append("+ (deleted)")
            } else {
                append("+ Add +\n")
                append(diffBlock(new, "+ "))
            }
            if (replaceAll) {
                append("\n\n")
                append("⚠ Applies to every occurrence in the file.")
            }
        }
    }

    /**
     * Renders any other tool call's arguments as pretty-printed JSON.
     * @param args the call arguments.
     * @return the argument dump, falling back to [Map.toString] if JSON rendering fails.
     */
    fun formatArgs(args: Map<String, Any?>): String {
        if (args.isEmpty()) return "{}"
        return try {
            val json = JSONObject()
            args.forEach { (key, value) ->
                json.put(key, truncate(value?.toString() ?: "", MAX_VALUE_CHARS))
            }
            json.toString(2)
        } catch (e: Exception) {
            args.toString()
        }
    }

    /**
     * Renders one side of the diff, stating any omission *outside* the prefixed lines: carrying a
     * `- `/`+ ` marker it would read as part of the code being changed. The notice also says the
     * hidden text is still written, because truncation is a display limit, not a limit on the edit.
     * @param text the full snippet.
     * @param prefix the diff marker for each shown line.
     * @return the prefixed lines, followed by an omission notice when [text] was cut.
     */
    private fun diffBlock(text: String, prefix: String): String {
        val shown = text.take(MAX_SNIPPET_CHARS)
        val hidden = text.length - shown.length
        val body = prefixLines(shown, prefix)
        if (hidden == 0) return body
        return body + "\n\n⚠ $hidden more characters are not shown here, " +
            "but they WILL be written. Decline if you can't review the whole change."
    }

    private fun prefixLines(text: String, prefix: String): String =
        text.lineSequence().joinToString("\n") { prefix + it }

    private fun truncate(text: String, limit: Int): String =
        if (text.length <= limit) text
        else text.take(limit) + "\n…(${text.length - limit} more characters)"
}
