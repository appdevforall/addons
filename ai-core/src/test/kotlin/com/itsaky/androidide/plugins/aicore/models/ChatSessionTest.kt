package com.itsaky.androidide.plugins.aicore.models

import org.junit.Test
import org.junit.Assert.*

class ChatSessionTest {

    @Test
    fun testChatSessionCreation() {
        val session = ChatSession()

        assertNotNull(session.id)
        assertTrue(session.id.isNotEmpty())
        assertTrue(session.createdAt > 0)
        assertTrue(session.messages.isEmpty())
    }

    @Test
    fun testChatSessionWithCustomId() {
        val customId = "test-session-123"
        val session = ChatSession(id = customId)

        assertEquals(customId, session.id)
    }

    @Test
    fun testChatSessionWithCustomCreatedAt() {
        val customTime = 1234567890L
        val session = ChatSession(createdAt = customTime)

        assertEquals(customTime, session.createdAt)
    }

    @Test
    fun testChatSessionTitle_NewChat() {
        val session = ChatSession()

        assertEquals("New Chat", session.title)
    }

    @Test
    fun testChatSessionTitle_WithUserMessage() {
        val messages = mutableListOf(
            ChatMessage(
                text = "Hello, assistant!",
                sender = Sender.USER
            )
        )
        val session = ChatSession(messages = messages)

        assertEquals("Hello, assistant!", session.title)
    }

    @Test
    fun testChatSessionTitle_WithMultipleMessages() {
        val messages = mutableListOf(
            ChatMessage(
                text = "First user message",
                sender = Sender.USER
            ),
            ChatMessage(
                text = "Agent response",
                sender = Sender.AGENT
            ),
            ChatMessage(
                text = "Second user message",
                sender = Sender.USER
            )
        )
        val session = ChatSession(messages = messages)

        // Should get the first user message
        assertEquals("First user message", session.title)
    }

    @Test
    fun testChatSessionFormattedDate() {
        val session = ChatSession()

        assertNotNull(session.formattedDate)
        assertFalse(session.formattedDate.isEmpty())
        // Check format contains expected pattern like "Jan 01, 2024"
        assertTrue(session.formattedDate.contains(","))
    }

    @Test
    fun givenASession_whenAddingAMessage_thenTheCopyCarriesItAndTheOriginalIsUntouched() {
        val session = ChatSession()
        val message = ChatMessage(
            text = "Test message",
            sender = Sender.USER
        )

        val updated = session.copy(messages = session.messages + message)

        assertTrue(session.messages.isEmpty())
        assertEquals(1, updated.messages.size)
        assertEquals(message, updated.messages[0])
    }
}
