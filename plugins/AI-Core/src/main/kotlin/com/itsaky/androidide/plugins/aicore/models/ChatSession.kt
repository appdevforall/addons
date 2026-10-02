package com.itsaky.androidide.plugins.aicore.models

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * One saved conversation.
 *
 * @property messages the transcript, immutable. A session inside the sessions StateFlow is
 *   replaced by a copy on every change rather than edited in place; mutating the list here would
 *   leave collectors holding a value that still compares equal to the one they already have.
 * @property projectKey namespace of the project this belongs to. Nullable because Gson builds
 *   instances through Unsafe and never runs Kotlin defaults, so a session written before this
 *   field existed deserializes as null however the property is declared; null means "legacy,
 *   project unknown" and is adopted by the reading project rather than discarded.
 * @property name the title the user gave this chat, or null while they have not renamed it.
 *   Nullable for the same reason as [projectKey]: a session stored before this field existed
 *   deserializes as null whatever default is declared here, and null is exactly "never renamed".
 * @property generatedTitle the title the selected backend wrote after the first reply, or null
 *   until then (and for sessions stored before this field, for the same Gson reason). Never
 *   overrides [name]; see ChatViewModel's title generation.
 */
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
    val projectKey: String? = null,
    val name: String? = null,
    val generatedTitle: String? = null
) {
    /**
     * What names this chat in the session list: the [name] the user gave it, otherwise the
     * [generatedTitle], otherwise its first user turn cut to [FALLBACK_TITLE_WORDS] words. Null for
     * a chat neither renamed nor started — the UI labels that one from strings.xml, out of reach here.
     */
    val displayTitle: String?
        get() = name?.takeIf { it.isNotBlank() }
            ?: generatedTitle?.takeIf { it.isNotBlank() }
            ?: messages.firstOrNull { it.sender == Sender.USER }?.text?.let(::firstWords)

    val formattedDate: String
        get() = DATE_FORMATTER.format(Instant.ofEpochMilli(createdAt))

    private companion object {
        /** Longest prompt excerpt that stands in for a title the backend did not write. */
        const val FALLBACK_TITLE_WORDS = 10

        private val WHITESPACE = Regex("\\s+")

        /**
         * [text] on one line, cut to [FALLBACK_TITLE_WORDS] words with an ellipsis when it was longer.
         * Split lazily: this runs per row on every streamed token, and a pasted prompt can be huge.
         */
        fun firstWords(text: String): String {
            val words = WHITESPACE.splitToSequence(text)
                .filter { it.isNotEmpty() }
                .take(FALLBACK_TITLE_WORDS + 1)
                .toList()
            if (words.size <= FALLBACK_TITLE_WORDS) return words.joinToString(" ")
            return words.take(FALLBACK_TITLE_WORDS).joinToString(" ") + "…"
        }

        /**
         * Immutable and thread-safe, unlike SimpleDateFormat, so one instance serves every row
         * instead of one being built per row — the session list re-renders on every streamed
         * token. Bound to the locale and zone in force when this class loads, which is the same
         * process lifetime over which the host rebuilds the plugin's Context on either changing.
         */
        val DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.getDefault())
                .withZone(ZoneId.systemDefault())
    }
}

/**
 * The order the chat history is shown and navigated in: newest first.
 *
 * One definition, because the stored list is in append order — oldest first — and anything that
 * reaches for "the first session" meaning "the top of the list" lands on the wrong end of it.
 * Stable, so sessions created in the same millisecond keep the order they were stored in.
 */
fun List<ChatSession>.newestFirst(): List<ChatSession> = sortedByDescending { it.createdAt }
