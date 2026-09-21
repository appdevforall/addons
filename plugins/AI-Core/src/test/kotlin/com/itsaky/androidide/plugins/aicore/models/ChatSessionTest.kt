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
    fun givenAnEmptySession_whenReadingItsTitle_thenThereIsNoneForTheUiToLabelItself() {
        val session = ChatSession()

        assertNull(session.displayTitle)
    }

    @Test
    fun givenAGeneratedTitle_whenReadingTheTitle_thenItReplacesTheFirstMessage() {
        val session = ChatSession(
            messages = listOf(ChatMessage(text = "how do i fix the gradle sync", sender = Sender.USER)),
            generatedTitle = "Fix Gradle sync"
        )

        assertEquals("Fix Gradle sync", session.displayTitle)
    }

    @Test
    fun givenALongFirstMessageAndNoGeneratedTitle_whenReadingTheTitle_thenItIsCutToTenWords() {
        val text = "please help me   fix the gradle\nsync that fails after I upgraded the android plugin"
        val session = ChatSession(messages = listOf(ChatMessage(text = text, sender = Sender.USER)))

        assertEquals("please help me fix the gradle sync that fails after…", session.displayTitle)
    }

    @Test
    fun givenAUserNameAndAGeneratedTitle_whenReadingTheTitle_thenTheUsersNameWins() {
        val session = ChatSession(name = "My build issue", generatedTitle = "Fix Gradle sync")

        assertEquals("My build issue", session.displayTitle)
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

        assertEquals("Hello, assistant!", session.displayTitle)
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
        assertEquals("First user message", session.displayTitle)
    }

    @Test
    fun givenARenamedSession_whenReadingItsTitle_thenTheNameWinsOverTheFirstMessage() {
        val session = ChatSession(
            messages = listOf(ChatMessage(text = "Hello, assistant!", sender = Sender.USER)),
            name = "Build failure",
        )

        assertEquals("Build failure", session.displayTitle)
    }

    @Test
    fun givenANameOfOnlyWhitespace_whenReadingTheTitle_thenTheFirstMessageIsUsedInstead() {
        val session = ChatSession(
            messages = listOf(ChatMessage(text = "Hello, assistant!", sender = Sender.USER)),
            name = "   ",
        )

        assertEquals("Hello, assistant!", session.displayTitle)
    }

    @Test
    fun givenARenamedSessionWithNoMessages_whenReadingTheTitle_thenItIsTheName() {
        val session = ChatSession(name = "Planning the refactor")

        assertEquals("Planning the refactor", session.displayTitle)
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
