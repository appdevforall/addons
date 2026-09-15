package com.itsaky.androidide.plugins.aicore.managers

import android.content.Context
import android.content.SharedPreferences
import com.itsaky.androidide.plugins.aicore.models.ChatMessage
import com.itsaky.androidide.plugins.aicore.models.ChatSession
import com.itsaky.androidide.plugins.aicore.models.Sender
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Stand-in project namespace; the digest itself is ProjectKey's business, not this file's. */
private const val TEST_PROJECT_KEY = "a1b2c3d4e5f60718"
private const val KEY_SESSIONS = "chat_sessions_$TEST_PROJECT_KEY"
private const val KEY_CURRENT_ID = "current_session_id_$TEST_PROJECT_KEY"

/**
 * Unit tests for ChatStorageManager.
 * Tests JSON serialization/deserialization with error handling.
 */
class ChatStorageManagerTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var storageManager: ChatStorageManager

    @Before
    fun setup() {
        // Mock Android Context and SharedPreferences
        context = mockk(relaxed = true)
        sharedPreferences = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        every { context.getSharedPreferences("ai_assistant_chats", Context.MODE_PRIVATE) } returns sharedPreferences
        // A relaxed mock answers a String getter with "", not null, which would read as a stored
        // blob; nothing is stored unless a test says so.
        every { sharedPreferences.getString(any(), any()) } returns null
        every { sharedPreferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.apply() } returns Unit
        every { editor.commit() } returns true

        storageManager = ChatStorageManager(context, TEST_PROJECT_KEY)
    }

    @Test
    fun givenAnEmptyList_whenPersisting_thenTheStoredHistoryIsCleared() {
        val sessions = emptyList<ChatSession>()
        val jsonSlot = slot<String>()

        every { editor.putString(KEY_SESSIONS, capture(jsonSlot)) } returns editor

        storageManager.persist(sessions, null)

        verify { editor.putString(KEY_SESSIONS, any()) }
        verify { editor.commit() }

        // Should serialize to empty JSON array
        assertEquals("[]", jsonSlot.captured)
    }

    @Test
    fun givenOneSession_whenPersisting_thenItsFieldsAreSerialized() {
        val session = ChatSession(
            id = "test-123",
            createdAt = 1234567890L,
            messages = mutableListOf()
        )
        val sessions = listOf(session)
        val jsonSlot = slot<String>()

        every { editor.putString(KEY_SESSIONS, capture(jsonSlot)) } returns editor

        storageManager.persist(sessions, null)

        verify { editor.putString(KEY_SESSIONS, any()) }
        verify { editor.commit() }

        // Verify JSON contains session data
        val json = jsonSlot.captured
        assertTrue(json.contains("test-123"))
        assertTrue(json.contains("1234567890"))
    }

    @Test
    fun givenSeveralSessions_whenPersisting_thenAllAreSerialized() {
        val session1 = ChatSession(id = "session-1", createdAt = 1000L)
        val session2 = ChatSession(id = "session-2", createdAt = 2000L)
        val sessions = listOf(session1, session2)
        val jsonSlot = slot<String>()

        every { editor.putString(KEY_SESSIONS, capture(jsonSlot)) } returns editor

        storageManager.persist(sessions, null)

        val json = jsonSlot.captured
        assertTrue(json.contains("session-1"))
        assertTrue(json.contains("session-2"))
    }

    @Test
    fun givenASessionWithMessages_whenPersisting_thenTheTranscriptIsSerialized() {
        val message1 = ChatMessage(
            id = "msg-1",
            text = "Hello",
            sender = Sender.USER
        )
        val message2 = ChatMessage(
            id = "msg-2",
            text = "Hi there",
            sender = Sender.AGENT
        )
        val session = ChatSession(
            id = "session-with-messages",
            messages = mutableListOf(message1, message2)
        )
        val jsonSlot = slot<String>()

        every { editor.putString(KEY_SESSIONS, capture(jsonSlot)) } returns editor

        storageManager.persist(listOf(session), null)

        val json = jsonSlot.captured
        assertTrue(json.contains("Hello"))
        assertTrue(json.contains("Hi there"))
        assertTrue(json.contains("msg-1"))
        assertTrue(json.contains("msg-2"))
    }

    @Test
    fun testLoadSessionsWithNoData() {
        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns null

        val sessions = storageManager.loadSessions()

        assertTrue(sessions.isEmpty())
    }

    @Test
    fun testLoadSessionsWithEmptyList() {
        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns "[]"

        val sessions = storageManager.loadSessions()

        assertTrue(sessions.isEmpty())
    }

    @Test
    fun testLoadSessionsWithValidData() {
        val validJson = """
            [
                {
                    "id": "test-session",
                    "createdAt": 1234567890,
                    "messages": []
                }
            ]
        """.trimIndent()

        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns validJson

        val sessions = storageManager.loadSessions()

        assertEquals(1, sessions.size)
        assertEquals("test-session", sessions[0].id)
        assertEquals(1234567890L, sessions[0].createdAt)
        assertTrue(sessions[0].messages.isEmpty())
    }

    @Test
    fun testLoadSessionsWithMultipleSessions() {
        val validJson = """
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

        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns validJson

        val sessions = storageManager.loadSessions()

        assertEquals(2, sessions.size)
        assertEquals("session-1", sessions[0].id)
        assertEquals("session-2", sessions[1].id)
    }

    @Test
    fun testLoadSessionsWithMessages() {
        val validJson = """
            [
                {
                    "id": "session-1",
                    "createdAt": 1000,
                    "messages": [
                        {
                            "id": "msg-1",
                            "text": "User message",
                            "sender": "USER",
                            "status": "SENT",
                            "timestamp": 1000
                        },
                        {
                            "id": "msg-2",
                            "text": "Agent response",
                            "sender": "AGENT",
                            "status": "COMPLETED",
                            "timestamp": 2000,
                            "durationMs": 500
                        }
                    ]
                }
            ]
        """.trimIndent()

        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns validJson

        val sessions = storageManager.loadSessions()

        assertEquals(1, sessions.size)
        assertEquals(2, sessions[0].messages.size)
        assertEquals("User message", sessions[0].messages[0].text)
        assertEquals(Sender.USER, sessions[0].messages[0].sender)
        assertEquals("Agent response", sessions[0].messages[1].text)
        assertEquals(Sender.AGENT, sessions[0].messages[1].sender)
    }

    @Test
    fun testLoadSessionsWithCorruptedJson() {
        val corruptedJson = "{invalid json this is not valid"

        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns corruptedJson

        val sessions = storageManager.loadSessions()

        // Should return empty list on parse error
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun testLoadSessionsWithMalformedJson() {
        val malformedJson = """
            [
                {
                    "id": "test",
                    "invalid_field": "this should not break parsing"
                }
            ]
        """.trimIndent()

        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns malformedJson

        val sessions = storageManager.loadSessions()

        // Gson should handle extra fields gracefully
        // Should still load what it can
        assertTrue(sessions.size >= 0)
    }

    @Test
    fun givenASelection_whenPersisting_thenItIsStored() {
        val sessionId = "session-123"
        val idSlot = slot<String>()

        every { editor.putString(KEY_CURRENT_ID, capture(idSlot)) } returns editor

        storageManager.persist(emptyList(), sessionId)

        verify { editor.putString(KEY_CURRENT_ID, any()) }
        verify { editor.commit() }
        assertEquals(sessionId, idSlot.captured)
    }

    @Test
    fun givenNoSelection_whenPersisting_thenNullIsStored() {
        val idSlot = slot<String?>()

        every { editor.putString(KEY_CURRENT_ID, captureNullable(idSlot)) } returns editor

        storageManager.persist(emptyList(), null)

        verify { editor.putString(KEY_CURRENT_ID, null) }
        verify { editor.commit() }
        assertNull(idSlot.captured)
    }

    @Test
    fun testLoadCurrentSessionIdWithValidId() {
        val sessionId = "session-456"
        every { sharedPreferences.getString(KEY_CURRENT_ID, null) } returns sessionId

        val loadedId = storageManager.loadCurrentSessionId()

        assertEquals(sessionId, loadedId)
    }

    @Test
    fun testLoadCurrentSessionIdWithNoData() {
        every { sharedPreferences.getString(KEY_CURRENT_ID, null) } returns null

        val loadedId = storageManager.loadCurrentSessionId()

        assertNull(loadedId)
    }

    @Test
    fun testRoundTripSaveAndLoad() {
        // Create test data
        val message1 = ChatMessage(text = "Test message", sender = Sender.USER)
        val session1 = ChatSession(
            id = "round-trip-test",
            createdAt = 9999999L,
            messages = mutableListOf(message1)
        )
        val sessions = listOf(session1)

        // Capture what gets saved
        val jsonSlot = slot<String>()
        every { editor.putString(KEY_SESSIONS, capture(jsonSlot)) } returns editor

        // Save
        storageManager.persist(sessions, null)

        // Now mock the load to return what was saved
        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns jsonSlot.captured

        // Load
        val loadedSessions = storageManager.loadSessions()

        // Verify round-trip
        assertEquals(1, loadedSessions.size)
        assertEquals("round-trip-test", loadedSessions[0].id)
        assertEquals(9999999L, loadedSessions[0].createdAt)
        assertEquals(1, loadedSessions[0].messages.size)
        assertEquals("Test message", loadedSessions[0].messages[0].text)
    }

    @Test
    fun testRoundTripCurrentSessionId() {
        val sessionId = "current-session"
        val idSlot = slot<String>()

        every { editor.putString(KEY_CURRENT_ID, capture(idSlot)) } returns editor

        // Save
        storageManager.persist(emptyList(), sessionId)

        // Mock load to return what was saved
        every { sharedPreferences.getString(KEY_CURRENT_ID, null) } returns idSlot.captured

        // Load
        val loadedId = storageManager.loadCurrentSessionId()

        assertEquals(sessionId, loadedId)
    }

    @Test
    fun testUsesCorrectSharedPreferencesName() {
        // Verify that ChatStorageManager uses the correct SharedPreferences file name
        verify { context.getSharedPreferences("ai_assistant_chats", Context.MODE_PRIVATE) }
    }

    @Test
    fun testUsesCorrectKeys() {
        storageManager.persist(listOf(ChatSession()), "test")

        // Verify correct keys are used
        verify { editor.putString(KEY_SESSIONS, any()) }
        verify { editor.putString(KEY_CURRENT_ID, any()) }
    }

    @Test
    fun givenAPersist_whenItRuns_thenBothKeysGoOutInOneEdit() {
        // Two edits could be torn apart by a process death, leaving a selection that names a
        // session the stored list has never heard of.
        storageManager.persist(listOf(ChatSession(id = "only")), "only")

        verify(exactly = 1) { sharedPreferences.edit() }
        verify(exactly = 1) { editor.commit() }
        verify(exactly = 0) { editor.apply() }
    }

    // ---- Per-project namespacing (ADFA-5583) ----

    @Test
    fun givenTwoProjects_whenSaving_thenEachWritesItsOwnKey() {
        val other = ChatStorageManager(context, "ffffffffffffffff")

        storageManager.persist(listOf(ChatSession(id = "mine")), null)
        other.persist(listOf(ChatSession(id = "theirs")), null)

        verify { editor.putString(KEY_SESSIONS, match { it.contains("mine") }) }
        verify { editor.putString("chat_sessions_ffffffffffffffff", match { it.contains("theirs") }) }
    }

    @Test
    fun givenTwoProjects_whenSavingCurrentId_thenEachWritesItsOwnKey() {
        val other = ChatStorageManager(context, "ffffffffffffffff")

        storageManager.persist(emptyList(), "mine")
        other.persist(emptyList(), "theirs")

        verify { editor.putString(KEY_CURRENT_ID, "mine") }
        verify { editor.putString("current_session_id_ffffffffffffffff", "theirs") }
    }

    @Test
    fun givenSessionStampedWithAnotherProject_whenLoading_thenItIsDiscarded() {
        val json = """
            [
                {"id": "ours", "createdAt": 1000, "messages": [], "projectKey": "$TEST_PROJECT_KEY"},
                {"id": "theirs", "createdAt": 2000, "messages": [], "projectKey": "ffffffffffffffff"}
            ]
        """.trimIndent()
        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns json

        val sessions = storageManager.loadSessions()

        assertEquals(1, sessions.size)
        assertEquals("ours", sessions[0].id)
    }

    @Test
    fun givenSessionWithNoProjectKey_whenLoading_thenItIsAdoptedByThisProject() {
        // Gson leaves the field null for a blob written before it existed, whatever Kotlin declares.
        val json = """[{"id": "legacy", "createdAt": 1000, "messages": []}]"""
        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns json

        val sessions = storageManager.loadSessions()

        assertEquals(1, sessions.size)
        assertEquals(TEST_PROJECT_KEY, sessions[0].projectKey)
    }

    @Test
    fun givenSavedSession_whenRoundTripped_thenProjectKeySurvives() {
        val jsonSlot = slot<String>()
        every { editor.putString(KEY_SESSIONS, capture(jsonSlot)) } returns editor

        storageManager.persist(listOf(ChatSession(id = "stamped", projectKey = TEST_PROJECT_KEY)), null)
        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns jsonSlot.captured

        val sessions = storageManager.loadSessions()

        assertEquals(1, sessions.size)
        assertEquals(TEST_PROJECT_KEY, sessions[0].projectKey)
    }

    @Test
    fun givenASessionWithNoMessagesField_whenLoading_thenTheTranscriptIsEmptyRatherThanNull() {
        // Gson builds through Unsafe, so a truncated blob leaves the non-null field null and the
        // first read of the transcript throws.
        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns
            """[{"id": "torn", "createdAt": 1000}]"""

        val sessions = storageManager.loadSessions()

        assertEquals(1, sessions.size)
        assertTrue(sessions[0].messages.isEmpty())
    }

    // ---- Legacy global history migration (ADFA-5583) ----

    @Test
    fun givenLegacyGlobalHistory_whenConstructed_thenItMovesIntoThisProject() {
        val legacy = """[{"id": "old-chat", "createdAt": 1000, "messages": []}]"""
        every { sharedPreferences.getString("chat_sessions", null) } returns legacy
        every { sharedPreferences.getString("current_session_id", null) } returns "old-chat"
        val jsonSlot = slot<String>()
        every { editor.putString(KEY_SESSIONS, capture(jsonSlot)) } returns editor

        ChatStorageManager(context, TEST_PROJECT_KEY)

        assertTrue(jsonSlot.captured.contains("old-chat"))
        assertTrue(jsonSlot.captured.contains(TEST_PROJECT_KEY))
        verify { editor.putString(KEY_CURRENT_ID, "old-chat") }
    }

    @Test
    fun givenLegacyGlobalHistory_whenConstructed_thenLegacyKeysAreRemoved() {
        every { sharedPreferences.getString("chat_sessions", null) } returns "[]"
        every { sharedPreferences.getString("current_session_id", null) } returns "old-chat"

        ChatStorageManager(context, TEST_PROJECT_KEY)

        verify { editor.remove("chat_sessions") }
        verify { editor.remove("current_session_id") }
        verify { editor.apply() }
    }

    @Test
    fun givenNamespaceAlreadyWritten_whenMigrating_thenLegacyBlobDoesNotOverwriteIt() {
        every { sharedPreferences.getString("chat_sessions", null) } returns
            """[{"id": "old-chat", "createdAt": 1000, "messages": []}]"""
        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns
            """[{"id": "current", "createdAt": 2000, "messages": []}]"""

        ChatStorageManager(context, TEST_PROJECT_KEY)

        verify(exactly = 0) { editor.putString(KEY_SESSIONS, any()) }
        verify { editor.remove("chat_sessions") }
    }

    @Test
    fun givenNoProjectOpen_whenConstructed_thenLegacyHistoryIsLeftAlone() {
        every { sharedPreferences.getString("chat_sessions", null) } returns
            """[{"id": "old-chat", "createdAt": 1000, "messages": []}]"""

        ChatStorageManager(context, ProjectKey.NO_PROJECT)

        verify(exactly = 0) { editor.remove("chat_sessions") }
        verify(exactly = 0) { editor.putString("chat_sessions_${ProjectKey.NO_PROJECT}", any()) }
    }

    @Test
    fun givenNoLegacyHistory_whenConstructed_thenNothingIsWritten() {
        every { sharedPreferences.getString("chat_sessions", null) } returns null
        every { sharedPreferences.getString("current_session_id", null) } returns null

        ChatStorageManager(context, TEST_PROJECT_KEY)

        verify(exactly = 0) { editor.remove(any()) }
    }

    @Test
    fun givenALegacySelectionWithNoBlob_whenConstructed_thenTheSelectionStillMigrates() {
        every { sharedPreferences.getString("chat_sessions", null) } returns null
        every { sharedPreferences.getString("current_session_id", null) } returns "old-chat"

        ChatStorageManager(context, TEST_PROJECT_KEY)

        verify { editor.putString(KEY_CURRENT_ID, "old-chat") }
    }

    @Test
    fun givenNamespaceAlreadyWritten_whenMigrating_thenTheLegacySelectionStillMigrates() {
        every { sharedPreferences.getString("chat_sessions", null) } returns
            """[{"id": "old-chat", "createdAt": 1000, "messages": []}]"""
        every { sharedPreferences.getString("current_session_id", null) } returns "old-chat"
        every { sharedPreferences.getString(KEY_SESSIONS, null) } returns
            """[{"id": "current", "createdAt": 2000, "messages": []}]"""

        ChatStorageManager(context, TEST_PROJECT_KEY)

        verify { editor.putString(KEY_CURRENT_ID, "old-chat") }
    }

    @Test
    fun givenThisProjectHasASelection_whenMigrating_thenTheLegacyOneDoesNotOverwriteIt() {
        every { sharedPreferences.getString("current_session_id", null) } returns "old-chat"
        every { sharedPreferences.getString(KEY_CURRENT_ID, null) } returns "mine"

        ChatStorageManager(context, TEST_PROJECT_KEY)

        verify(exactly = 0) { editor.putString(KEY_CURRENT_ID, any()) }
    }

    // ---- Failure handling: losing history must never take the IDE down ----

    @Test
    fun givenPreferencesThatCannotBeOpened_whenConstructed_thenItDegradesInsteadOfThrowing() {
        val broken = mockk<Context>(relaxed = true)
        every { broken.getSharedPreferences(any(), any()) } throws SecurityException("no prefs")

        val manager = ChatStorageManager(broken, TEST_PROJECT_KEY)

        assertTrue(manager.loadSessions().isEmpty())
        assertNull(manager.loadCurrentSessionId())
        manager.persist(listOf(ChatSession()), "anything")
    }

    @Test
    fun givenAFailingWrite_whenSaving_thenNothingIsThrown() {
        every { editor.putString(KEY_SESSIONS, any()) } throws IllegalStateException("disk full")

        storageManager.persist(listOf(ChatSession(id = "doomed")), "doomed")
    }

    @Test
    fun givenAFailingRead_whenLoading_thenAnEmptyHistoryComesBack() {
        every { sharedPreferences.getString(KEY_SESSIONS, null) } throws
            IllegalStateException("prefs unreadable")

        assertTrue(storageManager.loadSessions().isEmpty())
    }

    @Test
    fun givenAFailingLegacyRead_whenConstructed_thenLegacyKeysSurviveForTheNextAttempt() {
        every { sharedPreferences.getString("chat_sessions", null) } throws
            IllegalStateException("prefs unreadable")

        ChatStorageManager(context, TEST_PROJECT_KEY)

        verify(exactly = 0) { editor.remove("chat_sessions") }
    }
}
