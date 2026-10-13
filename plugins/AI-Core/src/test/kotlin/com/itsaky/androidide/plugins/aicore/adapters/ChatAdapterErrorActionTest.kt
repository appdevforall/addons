package com.itsaky.androidide.plugins.aicore.adapters

import com.itsaky.androidide.plugins.aicore.models.ChatMessage
import com.itsaky.androidide.plugins.aicore.models.MessageStatus
import com.itsaky.androidide.plugins.aicore.models.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which button an error row offers, by sender, denial and setup (ADFA-6359, ADFA-6345). */
class ChatAdapterErrorActionTest {

    private fun errorRow(sender: Sender, isDenial: Boolean = false, isSetupError: Boolean = false) =
        ChatMessage(
            text = "failed",
            sender = sender,
            status = MessageStatus.ERROR,
            isDenial = isDenial,
            isSetupError = isSetupError
        )

    @Test
    fun givenToolErrorRow_whenChoosingAction_thenNoRetry() {
        assertNull(ChatAdapter.errorActionFor(errorRow(Sender.TOOL), retryable = true))
    }

    @Test
    fun givenDeniedToolRow_whenChoosingAction_thenRetry() {
        val row = errorRow(Sender.TOOL, isDenial = true)
        assertEquals(ChatAdapter.ACTION_RETRY, ChatAdapter.errorActionFor(row, retryable = true))
    }

    @Test
    fun givenAgentErrorRow_whenChoosingAction_thenRetry() {
        assertEquals(ChatAdapter.ACTION_RETRY, ChatAdapter.errorActionFor(errorRow(Sender.AGENT), retryable = true))
    }

    @Test
    fun givenSystemRunErrorRow_whenChoosingAction_thenRetry() {
        assertEquals(ChatAdapter.ACTION_RETRY, ChatAdapter.errorActionFor(errorRow(Sender.SYSTEM), retryable = true))
    }

    @Test
    fun givenSetupErrorRow_whenChoosingAction_thenOpenSettings() {
        val row = errorRow(Sender.SYSTEM, isSetupError = true)
        assertEquals(ChatAdapter.ACTION_OPEN_SETTINGS, ChatAdapter.errorActionFor(row, retryable = false))
    }

    @Test
    fun givenErrorOutsideLastRun_whenChoosingAction_thenNoRetry() {
        assertNull(ChatAdapter.errorActionFor(errorRow(Sender.AGENT), retryable = false))
    }
}
