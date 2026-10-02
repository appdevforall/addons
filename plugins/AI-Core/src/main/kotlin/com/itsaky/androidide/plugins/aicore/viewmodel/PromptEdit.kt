package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.aicore.models.ChatMessage
import com.itsaky.androidide.plugins.aicore.models.Sender

/**
 * Decides what an edited prompt does: the newest one is rewound and replaced, an older one forks a
 * new branch (see [ChatBranches]).
 *
 * Kept free of Android and of the ViewModel's run state, so the rules the Edit action follows are
 * unit-testable on their own.
 */
internal object PromptEdit {

    /**
     * Whether [messageId] is the newest user prompt, the one an edit replaces instead of forking.
     *
     * @param messages the transcript, oldest first.
     */
    fun isLatestPrompt(messages: List<ChatMessage>, messageId: String): Boolean =
        messages.lastOrNull { it.sender == Sender.USER }?.id == messageId

    /**
     * The transcript as it stood just before the newest prompt [messageId] was sent.
     *
     * @param messages the transcript, oldest first.
     * @param messageId the prompt being edited.
     * @return every message before it, or null when [messageId] is not the newest prompt.
     */
    fun messagesBefore(messages: List<ChatMessage>, messageId: String): List<ChatMessage>? {
        if (!isLatestPrompt(messages, messageId)) return null
        return messages.subList(0, messages.indexOfFirst { it.id == messageId }).toList()
    }
}
