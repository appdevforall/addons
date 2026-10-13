package com.itsaky.androidide.plugins.aicore.adapters

import com.itsaky.androidide.plugins.aicore.models.ChatMessage
import com.itsaky.androidide.plugins.aicore.models.MessageStatus
import com.itsaky.androidide.plugins.aicore.models.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which button an error row offers, by sender and denial (ADFA-6359). */
class ChatAdapterErrorActionTest {

    private fun errorRow(sender: Sender, isDenial: Boolean = false) =
        ChatMessage(text = "failed", sender = sender, status = MessageStatus.ERROR, isDenial = isDenial)

    @Test
    fun givenToolErrorRow_whenChoosingAction_thenNoRetry() {
        assertNull(ChatAdapter.errorActionFor(errorRow(Sender.TOOL)))
    }

    @Test
    fun givenDeniedToolRow_whenChoosingAction_thenRetry() {
        assertEquals(ChatAdapter.ACTION_RETRY, ChatAdapter.errorActionFor(errorRow(Sender.TOOL, isDenial = true)))
    }

    @Test
    fun givenAgentErrorRow_whenChoosingAction_thenRetry() {
        assertEquals(ChatAdapter.ACTION_RETRY, ChatAdapter.errorActionFor(errorRow(Sender.AGENT)))
    }

    @Test
    fun givenSystemErrorRow_whenChoosingAction_thenOpenSettings() {
        assertEquals(ChatAdapter.ACTION_OPEN_SETTINGS, ChatAdapter.errorActionFor(errorRow(Sender.SYSTEM)))
    }
}
