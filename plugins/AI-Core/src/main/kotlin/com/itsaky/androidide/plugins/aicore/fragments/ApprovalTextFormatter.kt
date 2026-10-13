package com.itsaky.androidide.plugins.aicore.fragments

import com.itsaky.androidide.plugins.aicore.tool.handlers.EditFileHandler
import com.itsaky.androidide.plugins.aicore.tool.handlers.ShellInvocation
import com.itsaky.androidide.plugins.aicore.tool.handlers.StopTerminalCommandHandler
import com.itsaky.androidide.plugins.aicore.tool.handlers.TerminalCommandHandler
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

    /** Unicode categories that draw nothing: bidi overrides and zero-width marks, line and paragraph breaks. */
    private val HIDDEN_TYPES = setOf(
        Character.FORMAT,
        Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR,
    )

    /**
     * Code points that draw nothing yet fall outside [HIDDEN_TYPES]: the rest of Unicode's
     * Default_Ignorable set (Hangul fillers, variation selectors, tag characters) and the braille blank.
     */
    private val INVISIBLE_CODE_POINTS = listOf(
        0x034F..0x034F,
        0x115F..0x1160,
        0x17B4..0x17B5,
        0x180B..0x180F,
        0x2065..0x2065,
        0x2800..0x2800,
        0x3164..0x3164,
        0xFE00..0xFE0F,
        0xFFA0..0xFFA0,
        0xFFF0..0xFFF8,
        0xE0000..0xE0FFF,
    )

    /** Blank lines in a row shown as written; a longer run could push the rest below the dialog. */
    private const val MAX_BLANK_LINES = 2

    private const val LTR_ISOLATE = '\u2066'
    private const val POP_ISOLATE = '\u2069'

    private const val HIDDEN_CHARACTERS_WARNING =
        "⚠ This command contains invisible or control characters, shown as <U+XXXX>. " +
            "They will be sent to the shell as they are. Decline unless you know why they are there."

    private const val BLANK_LINES_WARNING =
        "⚠ This command contains long runs of blank lines, shown as [N blank lines]. " +
            "Read every line below them. Decline unless you know why they are there."

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
     * Renders a shell command uncut, with invisible characters spelled as `<U+XXXX>`, long runs of
     * blank lines counted and each line held left to right, so the text read is the text run. A
     * working directory, when given, goes above the command as a label.
     * @param args the `run_shell_command` call arguments.
     * @param directoryLabel words the line naming the working directory.
     * @return the command text.
     */
    fun formatShellCommand(args: Map<String, Any?>, directoryLabel: (String) -> String): String {
        val invocation = ShellInvocation.from(args) ?: return ""
        val command = revealHidden(invocation.command)
        val directory = invocation.workingDirectory?.let(::revealHidden)
        val shownCommand = collapseBlankLines(command)
        val shown = directory?.let { "${directoryLabel(leftToRight(it))}\n\n${leftToRight(shownCommand)}" }
            ?: leftToRight(shownCommand)
        val revealed = command != invocation.command || directory != invocation.workingDirectory
        val warnings = buildList {
            if (revealed) add(HIDDEN_CHARACTERS_WARNING)
            if (shownCommand != command) add(BLANK_LINES_WARNING)
        }
        return (listOf(shown) + warnings).joinToString("\n\n")
    }

    /**
     * Renders a stop as the command it would interrupt, under the Terminal session it runs in, so two
     * running commands can be told apart. An id this chat did not start is shown by its id alone.
     * @param args the `stop_terminal_command` arguments its handler validated.
     * @param sessionLabel words the line naming the session.
     * @param unknownCommand words the line for an id whose command is not known.
     * @return the text naming what would stop.
     */
    fun formatStopCommand(
        args: Map<String, Any?>,
        sessionLabel: (String) -> String,
        unknownCommand: (String) -> String,
    ): String {
        val commandId = args[TerminalCommandHandler.ARG_COMMAND_ID]?.toString().orEmpty()
        val command = args[StopTerminalCommandHandler.DETAIL_COMMAND]?.toString()
            ?: return unknownCommand(commandId)
        val session = args[StopTerminalCommandHandler.DETAIL_SESSION]?.toString().orEmpty()
        return "${sessionLabel(session)}\n\n${leftToRight(collapseBlankLines(revealHidden(command)))}"
    }

    /**
     * Spells out each code point that draws nothing or moves the cursor: `\r`, ESC and other
     * controls, bidi overrides, zero-width marks, Hangul fillers, variation selectors. Newlines and
     * tabs stay, as they show as themselves.
     * @param text the model-written text.
     * @return [text], with each such code point replaced by `<U+XXXX>`.
     */
    internal fun revealHidden(text: String): String = buildString {
        text.codePoints().forEach { codePoint ->
            if (isHidden(codePoint)) append("<U+%04X>".format(codePoint)) else appendCodePoint(codePoint)
        }
    }

    /**
     * Replaces each run of more than [MAX_BLANK_LINES] blank lines with one `[N blank lines]` line,
     * so a short first line cannot push the rest of a command out of view.
     */
    internal fun collapseBlankLines(text: String): String {
        val shown = mutableListOf<String>()
        val blanks = mutableListOf<String>()
        fun flushBlanks() {
            if (blanks.size > MAX_BLANK_LINES) shown += "[${blanks.size} blank lines]" else shown += blanks
            blanks.clear()
        }
        text.lines().forEach { line ->
            if (line.isBlank()) {
                blanks += line
            } else {
                flushBlanks()
                shown += line
            }
        }
        flushBlanks()
        return shown.joinToString("\n")
    }

    /**
     * Holds each line of [text] left to right with an LRI…PDI isolate: a line opening with a Hebrew
     * or Arabic letter would otherwise be laid out right to left, its parts shown out of order.
     */
    internal fun leftToRight(text: String): String =
        text.lineSequence().joinToString("\n") { "$LTR_ISOLATE$it$POP_ISOLATE" }

    private fun isHidden(codePoint: Int): Boolean = when {
        codePoint == '\n'.code || codePoint == '\t'.code -> false
        Character.isISOControl(codePoint) -> true
        INVISIBLE_CODE_POINTS.any { codePoint in it } -> true
        else -> Character.getType(codePoint).toByte() in HIDDEN_TYPES
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
