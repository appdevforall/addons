package com.itsaky.androidide.plugins.aicore.tool.sources

/**
 * Flattens the text a contributing plugin supplies, before it is shown or sent anywhere.
 *
 * Provider text reaches two places where a newline is enough to forge structure: a system prompt
 * assembled inside a backend plugin, and the approval dialog a user reads before allowing a call.
 * Both go through here, so the rule is stated once rather than per caller.
 */
object ContributedText {

    /** Max characters kept from a provider name shown in the UI; a dialog title is one line. */
    const val MAX_LABEL_CHARS = 60

    /**
     * Max characters kept from a provider description. Generous next to
     * [PromptToolBudget.MAX_DESCRIPTION_CHARS], which caps what the prompt sees: this is only the
     * ceiling that stops a megabyte of remote text reaching the approval dialog whole.
     */
    const val MAX_DETAIL_CHARS = 1_000

    /**
     * Collapses whitespace and control characters onto one line.
     * @param text the provider's own text.
     * @return the text, on one line, with runs of spaces collapsed.
     */
    fun flatten(text: String): String = text
        .map { if (it.isWhitespace() || it.isISOControl()) ' ' else it }
        .joinToString("")
        .replace(Regex(" +"), " ")
        .trim()

    /**
     * [text] flattened and capped, for a label with a line to spare rather than a paragraph.
     * @param text the provider's own text.
     * @param max the longest label to keep.
     * @return the label, ellipsised when it had to be cut.
     */
    fun label(text: String, max: Int = MAX_LABEL_CHARS): String {
        val flattened = flatten(text)
        return if (flattened.length > max) flattened.take(max).trimEnd() + "…" else flattened
    }
}
