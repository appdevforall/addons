package com.itsaky.androidide.plugins.aicore.tool.handlers.edit

/**
 * Locates the model's snippet literally, never by regex: a model-supplied pattern would throw on an
 * unbalanced bracket and can backtrack catastrophically in-process. A miss is retried in the file's
 * own line-ending convention, adapting the snippet and never the file's other lines.
 */
object FileTextMatcher {

    private const val CRLF = "\r\n"
    private const val LF = "\n"

    /** Outcome of [match]. */
    sealed interface Match {
        /**
         * The snippet was found.
         * @property oldString the snippet as it actually appears in the text — the original, or
         *   its line-ending-adapted form. Callers must replace with *this*, not their input.
         * @property newString the replacement, adapted the same way, so an edit cannot leave
         *   mixed line endings behind.
         * @property occurrences how many non-overlapping times [oldString] appears.
         * @property lineEndingsAdapted whether adaptation was needed (for tracing).
         */
        data class Found(
            val oldString: String,
            val newString: String,
            val occurrences: Int,
            val lineEndingsAdapted: Boolean,
        ) : Match

        /** The snippet is absent, in any line-ending convention. */
        object NotFound : Match
    }

    /**
     * Finds [oldString] in [text], retrying in the text's line-ending convention if needed.
     * @param text the file contents (an editor buffer or the on-disk copy).
     * @param oldString the snippet the model wants replaced.
     * @param newString what to put in its place (may be empty — a deletion).
     * @return the match, with both snippets in the text's own convention.
     */
    fun match(text: String, oldString: String, newString: String): Match {
        val direct = countOccurrences(text, oldString)
        if (direct > 0) {
            return Match.Found(oldString, newString, direct, lineEndingsAdapted = false)
        }
        // Single-line snippets have no line ending to get wrong, so there is nothing to retry.
        if (!oldString.contains(LF)) return Match.NotFound

        val adaptedOld = adaptTo(text, oldString) ?: return Match.NotFound
        val occurrences = countOccurrences(text, adaptedOld)
        if (occurrences == 0) return Match.NotFound
        return Match.Found(adaptedOld, adaptTo(text, newString) ?: newString, occurrences, true)
    }

    /**
     * Rewrites [snippet]'s line endings to the convention [text] uses.
     * @param text the file contents, whose convention wins.
     * @param snippet the snippet to convert.
     * @return the converted snippet, or null when [text]'s convention is already the snippet's
     *   (nothing to try) or is mixed (no single convention to convert to).
     */
    private fun adaptTo(text: String, snippet: String): String? {
        val textHasCrlf = text.contains(CRLF)
        // A lone LF outside a CRLF pair means mixed endings, where either conversion is a guess.
        val textHasBareLf = text.replace(CRLF, "").contains(LF)
        return when {
            textHasCrlf && !textHasBareLf && !snippet.contains('\r') -> toCrlf(snippet)
            !textHasCrlf && snippet.contains(CRLF) -> snippet.replace(CRLF, LF)
            else -> null
        }
    }

    private fun toCrlf(snippet: String): String = snippet.replace(CRLF, LF).replace(LF, CRLF)

    /**
     * Non-overlapping occurrence count of [needle] in [haystack].
     * @param haystack the text to search.
     * @param needle the text to look for.
     * @return how many times the snippet appears; 0 for an empty needle.
     */
    fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }
}
