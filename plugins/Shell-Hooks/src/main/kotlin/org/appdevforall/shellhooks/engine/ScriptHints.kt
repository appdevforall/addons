package org.appdevforall.shellhooks.engine

/**
 * A best-effort check, not a guarantee: marks the lines of a plain script ([ScriptReview]) that use
 * shell syntax which can make a script do something other than it seems, so the review dialog can
 * point at them. Plain text shows the reader every byte; it does not show how the shell parses them.
 *
 * Each line is judged on its own. Nothing is carried from one line to the next, and no quotes,
 * comments, here-documents or arithmetic are tracked, so no line of a script can change how the
 * lines after it are marked. A line is marked for each of these it contains, wherever it appears,
 * in a comment or string too:
 * - [Kind.HERE_DOCUMENT]: `<<`, which may start a here-document;
 * - [Kind.CONTINUATION]: a backslash as its last character, which in code joins the next line to it;
 * - [Kind.COMMAND]: a backtick or `$(`, which may run a command.
 *
 * That is the whole rule. It marks many lines that are harmless, and it says nothing about the many
 * other ways a line can surprise a reader (an alias, `IFS`, a function named like a builtin): a line
 * without a mark can still do anything. The dialog says so, and never states where a here-document
 * ends or whether its text is expanded.
 */
object ScriptHints {
    enum class Kind { HERE_DOCUMENT, CONTINUATION, COMMAND }

    /** Line numbers (1-based) listed in full for each kind; the rest are counted. */
    const val MAX_LISTED = 100

    /** The marked lines of a script, by kind, each list in line order. */
    data class Marks(
        val hereDocuments: List<Int>,
        val continuations: List<Int>,
        val commands: List<Int>,
    ) {
        val isEmpty: Boolean get() = hereDocuments.isEmpty() && continuations.isEmpty() && commands.isEmpty()

        /** Every marked line, for the `!` beside its number. */
        val lines: Set<Int> get() = (hereDocuments + continuations + commands).toSortedSet()

        fun of(kind: Kind): List<Int> =
            when (kind) {
                Kind.HERE_DOCUMENT -> hereDocuments
                Kind.CONTINUATION -> continuations
                Kind.COMMAND -> commands
            }
    }

    /** The kinds [line] is marked for: see [ScriptHints]. */
    fun kindsOf(line: String): Set<Kind> =
        buildSet {
            if (line.contains("<<")) add(Kind.HERE_DOCUMENT)
            if (line.endsWith('\\')) add(Kind.CONTINUATION)
            if (line.contains('`') || line.contains("$(")) add(Kind.COMMAND)
        }

    /** The marks for [lines], a script's lines without their newlines. */
    fun find(lines: List<String>): Marks {
        val byKind = Kind.entries.associateWith { mutableListOf<Int>() }
        lines.forEachIndexed { index, line ->
            for (kind in kindsOf(line)) byKind.getValue(kind).add(index + 1)
        }
        return Marks(
            hereDocuments = byKind.getValue(Kind.HERE_DOCUMENT),
            continuations = byKind.getValue(Kind.CONTINUATION),
            commands = byKind.getValue(Kind.COMMAND),
        )
    }

    /** The first [max] of [lines] to list, and how many more there are. */
    fun listed(
        lines: List<Int>,
        max: Int = MAX_LISTED,
    ): Pair<List<Int>, Int> = lines.take(max) to (lines.size - max).coerceAtLeast(0)
}
