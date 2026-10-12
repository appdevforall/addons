package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.aicore.models.ChatMessage
import com.itsaky.androidide.plugins.aicore.models.MessageStatus

/**
 * Decides which error rows offer Retry. Retry re-runs the last prompt, so only that run's errors
 * can offer it; an older or restored one would re-run the wrong prompt, or nothing at all.
 */
internal object RunRetry {

    /**
     * @param messages the transcript, oldest first.
     * @param lastRunPromptId the prompt the last run posted; null once the retry point is forgotten.
     * @return the ids of the error rows after that prompt; empty when it is no longer on screen.
     */
    fun retryableErrorIds(messages: List<ChatMessage>, lastRunPromptId: String?): Set<String> {
        val start = lastRunPromptId?.let { id -> messages.indexOfFirst { it.id == id } } ?: return emptySet()
        if (start < 0) return emptySet()
        return messages.drop(start + 1)
            .filter { it.status == MessageStatus.ERROR && !it.isSetupError }
            .mapTo(mutableSetOf()) { it.id }
    }
}
