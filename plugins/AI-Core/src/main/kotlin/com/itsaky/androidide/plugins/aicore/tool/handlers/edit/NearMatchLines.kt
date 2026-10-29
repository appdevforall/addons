package com.itsaky.androidide.plugins.aicore.tool.handlers.edit

/**
 * Finds the lines of a file that a failed `old_string` was probably aiming at, so a rejection can
 * show them. A small model that guesses `old_string` and is told only "not found" guesses again;
 * shown the real line, it can copy it. Matching is by shared words, never by regex, for the same
 * reason as [FileTextMatcher].
 */
object NearMatchLines {

    /** One candidate line, with its 1-based number and its exact text (no line ending). */
    data class Line(val number: Int, val text: String)

    private const val MAX_LINES = 5
    private const val MAX_LINE_CHARS = 200
    private const val MIN_WORD_CHARS = 3
    private val WORD = Regex("[A-Za-z0-9_]{$MIN_WORD_CHARS,}")

    /**
     * The lines of [text] sharing the most distinct words with [snippet].
     * @param text the file contents the edit was matched against.
     * @param snippet the `old_string` that was not found or changed nothing.
     * @return up to [MAX_LINES] lines, best match first and file order among equals; empty when
     *   the snippet has no word of [MIN_WORD_CHARS] or more characters or no line shares one.
     */
    fun find(text: String, snippet: String): List<Line> {
        val words = WORD.findAll(snippet).map { it.value.lowercase() }.distinct().toList()
        if (words.isEmpty()) return emptyList()
        return text.lineSequence()
            .mapIndexed { index, raw -> Line(index + 1, raw.trimEnd('\r').take(MAX_LINE_CHARS)) }
            .filter { it.text.isNotBlank() }
            .map { line ->
                val lower = line.text.lowercase()
                line to words.count { lower.contains(it) }
            }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .take(MAX_LINES)
            .map { (line, _) -> line }
            .toList()
    }

    /**
     * Renders [lines] for a rejection message, keeping leading and trailing spaces visible.
     * @param lines the result of [find].
     * @return the text to append to a rejection, or an empty string when there are no lines.
     */
    fun render(lines: List<Line>): String {
        if (lines.isEmpty()) return ""
        val body = lines.joinToString("\n") { "line ${it.number}: \"${it.text}\"" }
        return "\nClosest lines in the file (the exact text is between the outer double quotes, " +
            "spaces included):\n$body"
    }
}
