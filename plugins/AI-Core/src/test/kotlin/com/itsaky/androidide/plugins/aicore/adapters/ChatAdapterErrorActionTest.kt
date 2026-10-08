package com.itsaky.androidide.plugins.aicore.adapters

import com.itsaky.androidide.plugins.aicore.models.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which button an error row offers, by sender (ADFA-6359). */
class ChatAdapterErrorActionTest {

    @Test
    fun givenToolErrorRow_whenChoosingAction_thenNoRetry() {
        assertNull(ChatAdapter.errorActionFor(Sender.TOOL))
    }

    @Test
    fun givenAgentErrorRow_whenChoosingAction_thenRetry() {
        assertEquals(ChatAdapter.ACTION_RETRY, ChatAdapter.errorActionFor(Sender.AGENT))
    }

    @Test
    fun givenSystemErrorRow_whenChoosingAction_thenOpenSettings() {
        assertEquals(ChatAdapter.ACTION_OPEN_SETTINGS, ChatAdapter.errorActionFor(Sender.SYSTEM))
    }
}
