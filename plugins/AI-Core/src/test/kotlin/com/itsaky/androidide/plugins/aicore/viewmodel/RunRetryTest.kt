package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.aicore.models.ChatMessage
import com.itsaky.androidide.plugins.aicore.models.MessageStatus
import com.itsaky.androidide.plugins.aicore.models.Sender
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [RunRetry], which picks the error rows that offer Retry. */
class RunRetryTest {

    private val olderError = ChatMessage(id = "e1", text = "old", sender = Sender.SYSTEM, status = MessageStatus.ERROR)
    private val prompt = ChatMessage(id = "p2", text = "second", sender = Sender.USER)
    private val setupError = ChatMessage(
        id = "s", text = "no key", sender = Sender.SYSTEM, status = MessageStatus.ERROR, isSetupError = true,
    )
    private val settingsError = ChatMessage(
        id = "k", text = "key refused", sender = Sender.SYSTEM, status = MessageStatus.ERROR, isSettingsError = true,
    )
    private val runError = ChatMessage(id = "e2", text = "down", sender = Sender.SYSTEM, status = MessageStatus.ERROR)
    private val messages = listOf(
        ChatMessage(id = "p1", text = "first", sender = Sender.USER), olderError, prompt, setupError, settingsError, runError,
    )

    @Test
    fun givenErrorsBeforeAndAfterTheLastPrompt_whenPicked_thenOnlyTheLastRunsRunErrorsOfferRetry() {
        assertEquals(setOf("e2"), RunRetry.retryableErrorIds(messages, "p2"))
    }

    @Test
    fun givenAForgottenRetryPoint_whenPicked_thenNoRowOffersRetry() {
        assertEquals(emptySet<String>(), RunRetry.retryableErrorIds(messages, null))
    }

    @Test
    fun givenAPromptNoLongerOnScreen_whenPicked_thenNoRowOffersRetry() {
        assertEquals(emptySet<String>(), RunRetry.retryableErrorIds(messages, "gone"))
    }
}
