package com.itsaky.androidide.plugins.aicore.viewmodel

import android.content.Context
import android.content.SharedPreferences
import com.itsaky.androidide.plugins.aicore.models.ChatTranscript
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Stand-in project namespace; the digest itself is ProjectKey's business, not this file's. */
private const val TEST_PROJECT_KEY = "a1b2c3d4e5f60718"

private const val SESSIONS_KEY = "chat_sessions_$TEST_PROJECT_KEY"
private const val CURRENT_ID_KEY = "current_session_id_$TEST_PROJECT_KEY"

/**
 * What the session list in the Agent tab drives (ADFA-6007): renaming a conversation, and deleting
 * one without ever leaving the project with nowhere to type.
 *
 * Every assertion goes through a second ViewModel built on the same stored blob wherever the
 * acceptance criterion says "survives an IDE restart" — an in-memory list agreeing with itself
 * would prove nothing about what was written.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelSessionListTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    /**
     * Backs the preferences mock, so a write is visible to the next read. Concurrent because the
     * persist scope writes from its own thread.
     */
    private val stored = ConcurrentHashMap<String, String>()

    @Before
    fun setUp() {
        // ChatViewModel's stateIn() calls run on viewModelScope, i.e. Dispatchers.Main.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = mockk(relaxed = true)
        sharedPreferences = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns sharedPreferences
        // A relaxed mock answers a String getter with "", which would read as a stored blob.
        every { sharedPreferences.getString(any(), any()) } answers {
            stored[firstArg<String>()] ?: secondArg<String?>()
        }
        every { sharedPreferences.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            val key = firstArg<String>()
            val value = secondArg<String?>()
            if (value == null) stored.remove(key) else stored[key] = value
            editor
        }
        every { editor.remove(any()) } answers {
            stored.remove(firstArg<String>())
            editor
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun givenASession_whenRenamed_thenTheListShowsTheNameInPlaceOfTheFirstMessage() {
        seed(session("s1", message("m1", "why does the build fail", "USER")))
        val viewModel = restoredViewModel()

        viewModel.renameSession("s1", "Build failure")

        assertEquals("Build failure", viewModel.sessions.value.single { it.id == "s1" }.displayTitle)
    }

    @Test
    fun givenARename_whenTheIdeIsRestarted_thenTheNameIsStillThere() {
        seed(session("s1", message("m1", "why does the build fail", "USER")))
        restoredViewModel().renameSession("s1", "Build failure")
        awaitStoredSessions { it.contains("Build failure") }

        val reopened = restoredViewModel()

        assertEquals("Build failure", reopened.sessions.value.single { it.id == "s1" }.name)
    }

    @Test
    fun givenARenamedSession_whenRenamedToBlank_thenItFallsBackToItsFirstMessage() {
        seed(session("s1", message("m1", "why does the build fail", "USER")))
        val viewModel = restoredViewModel()
        viewModel.renameSession("s1", "Build failure")

        viewModel.renameSession("s1", "   ")

        val session = viewModel.sessions.value.single { it.id == "s1" }
        assertNull(session.name)
        assertEquals("why does the build fail", session.displayTitle)
    }

    @Test
    fun givenAnUnknownSession_whenRenamed_thenNothingIsRenamed() {
        seed(session("s1", message("m1", "why does the build fail", "USER")))
        val viewModel = restoredViewModel()

        viewModel.renameSession("nope", "Build failure")

        assertTrue(viewModel.sessions.value.all { it.name == null })
    }

    @Test
    fun givenTwoSessions_whenOneIsDeleted_thenTheOtherBecomesTheCurrentOne() {
        seed(
            session("s1", message("m1", "deleted conversation", "USER")),
            session("s2", message("m2", "surviving conversation", "USER")),
        )
        val viewModel = restoredViewModel()

        viewModel.deleteSession("s1")

        assertEquals(listOf("s2"), viewModel.sessions.value.map { it.id })
        assertEquals("s2", viewModel.currentSessionId.value)
    }

    @Test
    fun givenSeveralSessions_whenTheNewestActiveOneIsDeleted_thenTheNextNewestBecomesCurrent() {
        seedAged()
        val viewModel = restoredViewModel()
        viewModel.switchToSession("newest")

        viewModel.deleteSession("newest")

        // Not "oldest": the stored list is in append order, the list the user sees is not.
        assertEquals("newer", viewModel.currentSessionId.value)
    }

    @Test
    fun givenSeveralSessions_whenAMiddleActiveOneIsDeleted_thenTheNextOlderBecomesCurrent() {
        seedAged()
        val viewModel = restoredViewModel()
        viewModel.switchToSession("newer")

        viewModel.deleteSession("newer")

        assertEquals("older", viewModel.currentSessionId.value)
    }

    @Test
    fun givenSeveralSessions_whenTheOldestActiveOneIsDeleted_thenTheNextNewerBecomesCurrent() {
        seedAged()
        val viewModel = restoredViewModel()
        viewModel.switchToSession("oldest")

        viewModel.deleteSession("oldest")

        // Nothing below it in the list, so the neighbour is the row above.
        assertEquals("older", viewModel.currentSessionId.value)
    }

    @Test
    fun givenAnInactiveSessionDeleted_whenTheListIsRead_thenTheCurrentOneIsUntouched() {
        seedAged()
        val viewModel = restoredViewModel()
        viewModel.switchToSession("newest")

        viewModel.deleteSession("oldest")

        assertEquals("newest", viewModel.currentSessionId.value)
    }

    @Test
    fun givenNoStoredCurrentSession_whenStorageIsBound_thenTheNewestIsRestored() {
        seedAged()

        val viewModel = restoredViewModel()

        assertEquals("newest", viewModel.currentSessionId.value)
    }

    @Test
    fun givenSeveralSelected_whenDeletedTogether_thenOnlyThoseGoAndTheRestSurvive() {
        seedAged()
        val viewModel = restoredViewModel()
        viewModel.switchToSession("oldest")

        viewModel.deleteSessions(setOf("newest", "older"))

        assertEquals(setOf("newer", "oldest"), viewModel.sessions.value.map { it.id }.toSet())
        // The live one was not among them, so the user stays where they were.
        assertEquals("oldest", viewModel.currentSessionId.value)
    }

    @Test
    fun givenTheActiveSessionAmongSeveralSelected_whenDeleted_thenTheNearestSurvivorBecomesCurrent() {
        seedAged()
        val viewModel = restoredViewModel()
        viewModel.switchToSession("newest")

        viewModel.deleteSessions(setOf("newest", "newer"))

        // Not "newer", which is going too: the successor has to be picked from what survives.
        assertEquals("older", viewModel.currentSessionId.value)
    }

    @Test
    fun givenEverySessionSelected_whenDeleted_thenOneEmptySessionTakesTheirPlace() {
        seedAged()
        val viewModel = restoredViewModel()

        viewModel.deleteSessions(setOf("oldest", "older", "newer", "newest"))

        val session = viewModel.sessions.value.single()
        assertTrue(session.messages.isEmpty())
        assertEquals(TEST_PROJECT_KEY, session.projectKey)
        assertEquals(session.id, viewModel.currentSessionId.value)
    }

    @Test
    fun givenNothingSelected_whenDeletingSelection_thenTheHistoryIsUntouched() {
        seedAged()
        val viewModel = restoredViewModel()

        viewModel.deleteSessions(emptySet())

        assertEquals(4, viewModel.sessions.value.size)
    }

    @Test
    fun givenIdsNamingNoSession_whenDeletingSelection_thenTheyAreIgnored() {
        seedAged()
        val viewModel = restoredViewModel()

        viewModel.deleteSessions(setOf("older", "never-existed"))

        assertEquals(setOf("oldest", "newer", "newest"), viewModel.sessions.value.map { it.id }.toSet())
    }

    @Test
    fun givenSeveralDeletedTogether_whenTheIdeIsRestarted_thenTheyAreStillGone() {
        seedAged()
        val viewModel = restoredViewModel()

        viewModel.deleteSessions(setOf("newest", "older"))
        awaitStoredSessions { !it.contains("\"newest\"") && !it.contains("\"older\"") }

        assertEquals(setOf("newer", "oldest"), restoredViewModel().sessions.value.map { it.id }.toSet())
    }

    @Test
    fun givenTheOnlySession_whenItIsDeleted_thenOneEmptySessionTakesItsPlace() {
        seed(session("s1", message("m1", "only conversation", "USER")))
        val viewModel = restoredViewModel()

        viewModel.deleteSession("s1")

        val session = viewModel.sessions.value.single()
        assertNotEquals("s1", session.id)
        assertTrue(session.messages.isEmpty())
        // Bound to the project, or the next restore drops it as another project's.
        assertEquals(TEST_PROJECT_KEY, session.projectKey)
        assertEquals(session.id, viewModel.currentSessionId.value)
        assertTrue(viewModel.messages.value.isEmpty())
    }

    @Test
    fun givenTheOnlySessionDeleted_whenTheIdeIsRestarted_thenTheReplacementIsWhatComesBack() {
        seed(session("s1", message("m1", "only conversation", "USER")))
        val viewModel = restoredViewModel()
        viewModel.deleteSession("s1")
        val replacementId = viewModel.sessions.value.single().id
        awaitStoredSessions { !it.contains("only conversation") }

        val reopened = restoredViewModel()

        assertEquals(listOf(replacementId), reopened.sessions.value.map { it.id })
        assertEquals(replacementId, reopened.currentSessionId.value)
    }

    @Test
    fun givenANewChatStarted_whenTheEarlierOneIsLookedFor_thenItIsStillInTheList() {
        seed(session("s1", message("m1", "the earlier conversation", "USER")))
        val viewModel = restoredViewModel()

        viewModel.createNewSession()

        assertEquals(2, viewModel.sessions.value.size)
        assertEquals(
            listOf("the earlier conversation"),
            viewModel.sessions.value.single { it.id == "s1" }.messages.map { it.text },
        )
        assertNotEquals("s1", viewModel.currentSessionId.value)
    }

    @Test
    fun givenTheCurrentChatCleared_whenTheListIsRead_thenItIsTheSameSessionWithNothingInIt() {
        seed(session("s1", message("m1", "the conversation", "USER")))
        val viewModel = restoredViewModel()

        viewModel.clearMessages()

        // Clear Chat empties the thread the user is in; New chat is what starts another.
        assertEquals(listOf("s1"), viewModel.sessions.value.map { it.id })
        assertTrue(viewModel.sessions.value.single().messages.isEmpty())
    }

    @Test
    fun givenAnExportedChat_whenImported_thenItIsAddedAsANewCurrentChatAndTheOriginalIsUntouched() {
        seed(session("s1", message("m1", "the original", "USER")))
        val viewModel = restoredViewModel()
        val original = viewModel.sessions.value.single()

        viewModel.importSession(ChatTranscript.parse(ChatTranscript.export(original), null))

        assertEquals(2, viewModel.sessions.value.size)
        assertEquals(original, viewModel.sessions.value.single { it.id == "s1" })
        val imported = viewModel.sessions.value.single { it.id != "s1" }
        assertEquals(listOf("the original"), imported.messages.map { it.text })
        assertEquals(TEST_PROJECT_KEY, imported.projectKey)
        assertEquals(imported.id, viewModel.currentSessionId.value)
        assertEquals(listOf("the original"), viewModel.messages.value.map { it.text })
    }

    @Test
    fun givenAnImportedChat_whenTheIdeIsRestarted_thenItIsStillInTheList() {
        seed(session("s1", message("m1", "the original", "USER")))
        val viewModel = restoredViewModel()
        val exported = ChatTranscript.export(viewModel.sessions.value.single())
        viewModel.importSession(ChatTranscript.parse(exported, null))
        val importedId = viewModel.currentSessionId.value!!
        awaitStoredSessions { it.contains(importedId) }

        assertTrue(restoredViewModel().sessions.value.any { it.id == importedId })
    }

    /**
     * Blocks until the persist scope, which writes from a thread of its own, has landed a sessions
     * blob satisfying [predicate]. Polled rather than verified on the mock, so what the assertion
     * afterwards reads back is the same store an IDE restart would.
     *
     * @param predicate what the stored blob has to say before the test may go on.
     */
    private fun awaitStoredSessions(predicate: (String) -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (stored[SESSIONS_KEY]?.let(predicate) == true) return
            Thread.sleep(10)
        }
        fail("the stored sessions blob never matched: ${stored[SESSIONS_KEY]}")
    }

    /** Builds a ViewModel whose storage is already bound to the test project's namespace. */
    private fun restoredViewModel(): ChatViewModel =
        ChatViewModel { null }.also { it.initializeStorage(context, TEST_PROJECT_KEY) }

    /** Four sessions a millisecond apart, stored oldest-first as the real blob is. */
    private fun seedAged() = seed(
        session("oldest", createdAt = 1_000),
        session("older", createdAt = 2_000),
        session("newer", createdAt = 3_000),
        session("newest", createdAt = 4_000),
    )

    /** Publishes [sessions] as this project's stored blob, leaving no session selected. */
    private fun seed(vararg sessions: String) {
        stored[SESSIONS_KEY] = sessions.joinToString(",", prefix = "[", postfix = "]")
        stored.remove(CURRENT_ID_KEY)
    }

    /** @param createdAt when the chat was started; the history list is ordered by it. */
    private fun session(id: String, vararg messages: String, createdAt: Long = 1_000): String =
        """{"id":"$id","createdAt":$createdAt,"projectKey":"$TEST_PROJECT_KEY",""" +
            """"messages":[${messages.joinToString(",")}]}"""

    private fun message(id: String, text: String, sender: String): String =
        """{"id":"$id","text":"$text","sender":"$sender","status":"SENT","timestamp":1}"""
}
