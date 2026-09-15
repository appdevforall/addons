package com.itsaky.androidide.plugins.aicore.models

import java.text.SimpleDateFormat
import java.util.Date
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
 */
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
    val projectKey: String? = null
) {
    val title: String
        get() = messages.firstOrNull { it.sender == Sender.USER }?.text ?: "New Chat"

    val formattedDate: String
        get() = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(createdAt))
}
