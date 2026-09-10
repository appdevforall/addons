package com.itsaky.androidide.plugins.aicore.viewmodel

import android.content.Context
import android.content.SharedPreferences
import com.itsaky.androidide.plugins.aicore.managers.ChatStorageManager
import com.itsaky.androidide.plugins.aicore.models.ChatMessage
import com.itsaky.androidide.plugins.aicore.models.ChatSession
import com.itsaky.androidide.plugins.aicore.models.Sender
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Stand-in project namespace; the digest itself is ProjectKey's business, not this file's. */
private const val TEST_PROJECT_KEY = "a1b2c3d4e5f60718"
private const val KEY_SESSIONS = "chat_sessions_$TEST_PROJECT_KEY"
private const val KEY_CURRENT_ID = "current_session_id_$TEST_PROJECT_KEY"

/**
 * Unit tests for ChatViewModel storage initialization logic.
 * Tests the interaction between ChatViewModel and ChatStorageManager.
 *
 * Note: Full ViewModel lifecycle testing (including onCleared persistence)
 * would require PluginContext which is not available in unit tests.
 * These tests focus on the storage initialization and session loading logic.
 */
class ChatViewModelStorageTest {

    private lateinit var androidContext: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var storageManager: ChatStorageManager

    @Before
    fun setup() {
        // Mock Android components
        androidContext = mockk(relaxed = true)
        sharedPreferences = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        every { androidContext.getSharedPreferences("ai_assistant_chats", Context.MODE_PRIVATE) } returns sharedPreferences
        // A relaxed mock answers a String getter with "", not null, which would read as a stored
        // blob; nothing is stored unless a test says so.
        every { sharedPreferences.getString(any(), any()) } returns null
        every { sharedPreferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.apply() } returns Unit

        storageManager = ChatStorageManager(androidContext, TEST_PROJECT_KEY)
    }

    @Test
    fun testStorageManagerInitialization() {
        // Verify StorageManager can be created
        assertNotNull(storageManager)
        verify { androidContext.getSharedPreferences("ai_assistant_chats", Context.MODE_PRIVATE) }
    }

    @Test
    fun testLoadSessionsWhenEmpty() {
        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns null

        val sessions = storageManager.loadSessions()

        assertTrue(sessions.isEmpty())
    }

    @Test
    fun testLoadSessionsWithData() {
        val sessionJson = """
            [
                {
                    "id": "test-session",
                    "createdAt": 1000,
                    "messages": []
                }
            ]
        """.trimIndent()

        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns sessionJson

        val sessions = storageManager.loadSessions()

        assertEquals(1, sessions.size)
        assertEquals("test-session", sessions[0].id)
    }

    @Test
    fun testLoadCurrentSessionId() {
        every { sharedPreferences.getString(KEY_CURRENT_ID, null) } returns "session-123"

        val sessionId = storageManager.loadCurrentSessionId()

        assertEquals("session-123", sessionId)
    }

    @Test
    fun testSessionRestorationFlow() {
        // This tests the expected flow that ChatViewModel.loadSessions() should follow:
        // 1. Load sessions from storage
        // 2. Load current session ID
        // 3. If sessions exist, restore the current one or fallback to first
        // 4. If no sessions, create a new one

        val session1 = ChatSession(id = "session-1", createdAt = 1000)
        val session2 = ChatSession(id = "session-2", createdAt = 2000)
        val sessions = listOf(session1, session2)

        val sessionJson = """
            [
                {
                    "id": "session-1",
                    "createdAt": 1000,
                    "messages": []
                },
                {
                    "id": "session-2",
                    "createdAt": 2000,
                    "messages": []
                }
            ]
        """.trimIndent()

        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns sessionJson
        every { sharedPreferences.getString(KEY_CURRENT_ID, null) } returns "session-2"

        // Load sessions
        val loadedSessions = storageManager.loadSessions()
        assertEquals(2, loadedSessions.size)

        // Load current ID
        val currentId = storageManager.loadCurrentSessionId()
        assertEquals("session-2", currentId)

        // Find current session
        val currentSession = loadedSessions.firstOrNull { it.id == currentId }
        assertNotNull(currentSession)
        assertEquals("session-2", currentSession?.id)
    }

    @Test
    fun testSessionRestorationWithMissingCurrentId() {
        val sessionJson = """
            [
                {
                    "id": "session-1",
                    "createdAt": 1000,
                    "messages": []
                }
            ]
        """.trimIndent()

        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns sessionJson
        every { sharedPreferences.getString(KEY_CURRENT_ID, null) } returns "non-existent"

        val loadedSessions = storageManager.loadSessions()
        val currentId = storageManager.loadCurrentSessionId()

        // Should load sessions successfully
        assertEquals(1, loadedSessions.size)

        // Current ID doesn't match any session
        val currentSession = loadedSessions.firstOrNull { it.id == currentId }
        assertFalse(currentSession != null)

        // Fallback should be first session
        val fallbackSession = loadedSessions.firstOrNull()
        assertNotNull(fallbackSession)
        assertEquals("session-1", fallbackSession?.id)
    }

    @Test
    fun testSessionPersistenceFlow() {
        // This tests the expected flow that ChatViewModel.onCleared() should follow:
        // 1. Save all sessions
        // 2. Save current session ID

        val message = ChatMessage(text = "Test message", sender = Sender.USER)
        val session = ChatSession(
            id = "persist-test",
            createdAt = 5000,
            messages = mutableListOf(message)
        )
        val sessions = listOf(session)

        // Save sessions
        storageManager.saveSessions(sessions)
        verify { editor.putString(KEY_SESSIONS, any()) }
        verify { editor.apply() }

        // Save current session ID
        storageManager.saveCurrentSessionId("persist-test")
        verify { editor.putString(KEY_CURRENT_ID, "persist-test") }
        verify(atLeast = 2) { editor.apply() }
    }

    @Test
    fun testEmptySessionsCreatesNewSession() {
        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns "[]"

        val sessions = storageManager.loadSessions()

        // Should be empty, indicating ViewModel should create a new session
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun testSessionWithMessagesRestoration() {
        val sessionJson = """
            [
                {
                    "id": "msg-session",
                    "createdAt": 1000,
                    "messages": [
                        {
                            "id": "msg-1",
                            "text": "Hello",
                            "sender": "USER",
                            "status": "SENT",
                            "timestamp": 1000
                        },
                        {
                            "id": "msg-2",
                            "text": "Hi there",
                            "sender": "AGENT",
                            "status": "COMPLETED",
                            "timestamp": 2000,
                            "durationMs": 500
                        }
                    ]
                }
            ]
        """.trimIndent()

        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns sessionJson

        val sessions = storageManager.loadSessions()

        assertEquals(1, sessions.size)
        assertEquals(2, sessions[0].messages.size)
        assertEquals("Hello", sessions[0].messages[0].text)
        assertEquals(Sender.USER, sessions[0].messages[0].sender)
        assertEquals("Hi there", sessions[0].messages[1].text)
        assertEquals(Sender.AGENT, sessions[0].messages[1].sender)
    }

    @Test
    fun testStorageHandlesCorruptedData() {
        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns "{invalid json"

        val sessions = storageManager.loadSessions()

        // Should handle error gracefully and return empty list
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun testMultipleSessionsPersistence() {
        val session1 = ChatSession(id = "s1", createdAt = 1000)
        val session2 = ChatSession(id = "s2", createdAt = 2000)
        val session3 = ChatSession(id = "s3", createdAt = 3000)

        storageManager.saveSessions(listOf(session1, session2, session3))

        verify { editor.putString(KEY_SESSIONS, any()) }
        verify { editor.apply() }
    }


    // ---- Per-project isolation, as ChatViewModel.initializeStorage drives it (ADFA-5583) ----

    @Test
    fun givenProjectSwitch_whenReloading_thenOnlyTheNewProjectsSessionsCome() {
        val otherProjectKey = "0f0e0d0c0b0a0908"
        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns
            """[{"id": "alpha-chat", "createdAt": 1000, "messages": [], "projectKey": "$TEST_PROJECT_KEY"}]"""
        every { sharedPreferences.getString("chat_sessions_$otherProjectKey", null) } returns
            """[{"id": "beta-chat", "createdAt": 2000, "messages": [], "projectKey": "$otherProjectKey"}]"""

        val alpha = storageManager.loadSessions()
        val beta = ChatStorageManager(androidContext, otherProjectKey).loadSessions()

        assertEquals(listOf("alpha-chat"), alpha.map { it.id })
        assertEquals(listOf("beta-chat"), beta.map { it.id })
    }

    @Test
    fun givenOutgoingProject_whenPersistedBeforeSwitch_thenItsSessionsGoToItsOwnKey() {
        // ChatFragment persists the outgoing project first, so the last turn is not lost on a swap.
        storageManager.saveSessions(listOf(ChatSession(id = "alpha-chat", projectKey = TEST_PROJECT_KEY)))
        storageManager.saveCurrentSessionId("alpha-chat")

        verify { editor.putString(KEY_SESSIONS, match { it.contains("alpha-chat") }) }
        verify { editor.putString(KEY_CURRENT_ID, "alpha-chat") }
    }

    @Test
    fun givenEmptyNamespace_whenLoading_thenNothingComesBackAndTheViewModelStartsFresh() {
        every { sharedPreferences.getString("chat_sessions_0f0e0d0c0b0a0908", null) } returns null

        val sessions = ChatStorageManager(androidContext, "0f0e0d0c0b0a0908").loadSessions()

        assertTrue(sessions.isEmpty())
    }
}
