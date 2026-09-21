package com.itsaky.androidide.plugins.aicore.viewmodel

/**
 * Builds the request that asks the selected backend to name a chat, and cleans what comes back.
 *
 * Kept free of Android and of the service, so the parts that decide what the user sees as a title
 * are unit-testable. The prompt is model-facing, so it stays here rather than in strings.xml.
 */
internal object ChatTitle {

    /**
     * Cloud models that reason before answering count that reasoning against this cap, so it is
     * sized for them; a local model stops at end-of-turn long before reaching it.
     */
    const val MAX_TOKENS = 512

    const val TEMPERATURE = 0.2f

    /** A title the backend has not produced by then is abandoned; the chat keeps its fallback. */
    const val TIMEOUT_MS = 30_000L

    /** Longest excerpt of each side of the exchange sent to the model; the gist is at the start. */
    const val EXCERPT_CHARS = 1_000

    const val MAX_WORDS = 8

    /** One toolbar line; longer still ellipsizes there, but the sidebar row wraps nothing. */
    const val MAX_CHARS = 60

    const val SYSTEM_PROMPT =
        "You name chat conversations between a developer and a coding assistant. " +
            "Reply with a short title of 2 to 6 words that says what the developer wants, " +
            "in the developer's language. Plain text only: no quotes, no markdown, " +
            "no trailing punctuation, no explanation."

    private val THINKING = Regex("(?s)<think>.*?</think>")
    private val LABEL = Regex("^(?i)title\\s*:\\s*")
    private val EDGE_MARKS = Regex("^[\\s#*_`\"'“”‘’>-]+|[\\s*_`\"'“”‘’.,;:!-]+$")
    private val WHITESPACE = Regex("\\s+")

    /**
     * @param userText the conversation's first user message.
     * @param replyText the agent's reply to it.
     * @return the prompt, ending on `Title:` so a completion-style model answers with just that.
     */
    fun prompt(userText: String, replyText: String): String = buildString {
        append("Conversation:\n")
        append("Developer: ").append(userText.trim().take(EXCERPT_CHARS)).append('\n')
        append("Assistant: ").append(replyText.trim().take(EXCERPT_CHARS)).append("\n\n")
        append("Title:")
    }

    /**
     * Reduces a model's answer to a title: its first non-empty line, without reasoning blocks, a
     * `Title:` label, quotes, markdown or trailing punctuation, capped at [MAX_WORDS] and [MAX_CHARS].
     *
     * @param raw the backend's reply text.
     * @return the title, or null when nothing usable is left.
     */
    fun sanitize(raw: String): String? {
        val line = raw.replace(THINKING, "")
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        val cleaned = line.stripEdgeMarks()
            .replace(LABEL, "")
            .stripEdgeMarks()
            .replace(WHITESPACE, " ")
        if (cleaned.isEmpty()) return null
        val words = cleaned.split(' ')
        val capped = if (words.size > MAX_WORDS) words.take(MAX_WORDS).joinToString(" ") else cleaned
        return capped.take(MAX_CHARS).trimEnd().ifEmpty { null }
    }

    private fun String.stripEdgeMarks(): String = replace(EDGE_MARKS, "")
}
