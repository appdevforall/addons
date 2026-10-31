package com.itsaky.androidide.plugins.aicore.tool.handlers.edit

/**
 * Finds the lines of a file that a failed `old_string` was probably aiming at, so a rejection can
 * show them. A small model that guesses `old_string` and is told only "not found" guesses again;
 * shown the real line, it can copy it. Matching is by shared words, never by regex, for the same
 * reason as [FileTextMatcher].
 */
object NearMatchLines {

    /**
     * One candidate line.
     * @property number its 1-based line number.
     * @property text its exact text with no line ending, or the start of it when [cut].
     * @property cut whether the line goes on past [text].
     */
    data class Line(val number: Int, val text: String, val cut: Boolean = false)

    private const val MAX_LINES = 5
    private const val MAX_LINE_CHARS = 200

    /** Words matched per snippet; a huge `old_string` would otherwise cost words × file size. */
    private const val MAX_WORDS = 32
    private const val MIN_WORD_CHARS = 3

    /** Letters of any script, with their combining marks, so a localized string still matches. */
    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}_]+")

    /** Where an identifier such as `app_name` or `appName` splits into its parts. */
    private val PART_BREAK = Regex("_+|(?<=\\p{Ll})(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})")

    /** Scripts written without spaces, where a run of letters is a phrase rather than a word. */
    private val UNSPACED = setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI, Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR,
    )

    /**
     * The lines of [text] sharing the most distinct words with [snippet]. A word matches a whole
     * word or a part of an identifier, so `val` misses `evaluate` but `name` finds `app_name`; a
     * word in a script written without spaces matches anywhere in the line, as a part of a phrase.
     * @param text the file contents the edit was matched against.
     * @param snippet the `old_string` that was not found or changed nothing.
     * @return up to [MAX_LINES] lines, best match first and file order among equals; empty when
     *   the snippet has no word of [MIN_WORD_CHARS] or more characters or no line shares one.
     */
    fun find(text: String, snippet: String): List<Line> {
        val words = wordsOf(snippet).distinct().take(MAX_WORDS).toList()
        if (words.isEmpty()) return emptyList()
        val unspaced = words.filterTo(mutableSetOf(), ::isUnspaced)
        return text.lineSequence()
            .withIndex()
            .filter { (_, line) -> line.isNotBlank() }
            .map { (index, line) ->
                val lineWords = wordsOf(line).toSet()
                val lower = if (unspaced.isEmpty()) "" else line.lowercase()
                // One character short rather than half of one: a lone surrogate is not text.
                val shown = line.take(MAX_LINE_CHARS).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
                val score = words.count { if (it in unspaced) lower.contains(it) else it in lineWords }
                Line(index + 1, shown, shown.length < line.length) to score
            }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .take(MAX_LINES)
            .map { (line, _) -> line }
            .toList()
    }

    /** Each word of [text] and each part of an identifier, lowercased, of [MIN_WORD_CHARS] or more characters. */
    private fun wordsOf(text: String): Sequence<String> =
        WORD.findAll(text)
            .flatMap { word -> sequenceOf(word.value) + word.value.split(PART_BREAK) }
            .filter { it.length >= MIN_WORD_CHARS }
            .map { it.lowercase() }

    private fun isUnspaced(word: String): Boolean =
        word.codePoints().anyMatch { Character.UnicodeScript.of(it) in UNSPACED }

    /**
     * Renders [lines] for a rejection message, keeping leading and trailing spaces visible and a
     * tab told apart from them.
     * @param lines the result of [find].
     * @return the text to append to a rejection, or an empty string when there are no lines.
     */
    fun render(lines: List<Line>): String {
        if (lines.isEmpty()) return ""
        val body = lines.joinToString("\n") { line ->
            val shown = "line ${line.number}: \"${line.text.replace("\t", "\\t")}\""
            if (line.cut) "$shown (only the first $MAX_LINE_CHARS characters; the line goes on)" else shown
        }
        return "\nClosest lines in the file (the exact text is between the outer double quotes, " +
            "spaces included; a tab is shown as \\t):\n$body"
    }
}
