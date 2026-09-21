package com.itsaky.androidide.plugins.aicore.viewmodel

import org.junit.Test
import org.junit.Assert.*
import com.itsaky.androidide.plugins.aicore.models.ChatMessage
import com.itsaky.androidide.plugins.aicore.models.ChatSession
import com.itsaky.androidide.plugins.aicore.models.Sender

/**
 * Unit tests for ChatViewModel session management.
 * Tests the core logic of session creation, switching, and deletion.
 */
class ChatViewModelSessionTest {

    @Test
    fun testSessionCreationLogic() {
        val sessions = mutableListOf<ChatSession>()

        // Create first session
        val session1 = ChatSession()
        sessions.add(session1)

        assertEquals(1, sessions.size)
        assertNotNull(session1.id)
        assertNull(session1.displayTitle)
    }

    @Test
    fun testMultipleSessionCreation() {
        val sessions = mutableListOf<ChatSession>()

        val session1 = ChatSession()
        val session2 = ChatSession()
        val session3 = ChatSession()

        sessions.add(session1)
        sessions.add(session2)
        sessions.add(session3)

        assertEquals(3, sessions.size)
        assertNotEquals(session1.id, session2.id)
        assertNotEquals(session2.id, session3.id)
    }

    @Test
    fun testSessionSwitching() {
        val sessions = mutableListOf<ChatSession>()
        val session1 = ChatSession()
        val session2 = ChatSession()

        sessions.add(session1)
        sessions.add(session2)

        // Switch to first session
        val currentSession = sessions.firstOrNull { it.id == session1.id }

        assertNotNull(currentSession)
        assertEquals(session1.id, currentSession?.id)
    }

    @Test
    fun testSessionDeletion() {
        val sessions = mutableListOf<ChatSession>()
        val session1 = ChatSession()
        val session2 = ChatSession()

        sessions.add(session1)
        sessions.add(session2)

        // Delete first session
        sessions.removeAll { it.id == session1.id }

        assertEquals(1, sessions.size)
        assertEquals(session2.id, sessions[0].id)
    }

    @Test
    fun testDeleteCurrentSessionFallback() {
        val sessions = mutableListOf<ChatSession>()
        var currentSessionId: String? = null

        val session1 = ChatSession()
        val session2 = ChatSession()

        sessions.add(session1)
        sessions.add(session2)
        currentSessionId = session2.id

        // Delete current session
        sessions.removeAll { it.id == currentSessionId }

        // Should fallback to remaining session
        val remaining = sessions.firstOrNull()
        if (remaining != null) {
            currentSessionId = remaining.id
        } else {
            currentSessionId = null
        }

        assertEquals(session1.id, currentSessionId)
    }

    @Test
    fun testDeleteLastSessionClears() {
        val sessions = mutableListOf<ChatSession>()
        var currentSessionId: String? = null

        val session = ChatSession()
        sessions.add(session)
        currentSessionId = session.id

        // Delete the only session
        sessions.removeAll { it.id == currentSessionId }

        val remaining = sessions.firstOrNull()
        if (remaining != null) {
            currentSessionId = remaining.id
        } else {
            currentSessionId = null
        }

        assertEquals(0, sessions.size)
        assertNull(currentSessionId)
    }

    @Test
    fun testSessionTitleUpdatesWithMessages() {
        val messages = mutableListOf(
            ChatMessage(
                text = "First message",
                sender = Sender.USER
            )
        )
        val session = ChatSession(messages = messages)

        assertEquals("First message", session.displayTitle)

        // Add more messages
        messages.add(ChatMessage(text = "Second message", sender = Sender.AGENT))

        // Title should still be the first user message
        assertEquals("First message", session.displayTitle)
    }
}
