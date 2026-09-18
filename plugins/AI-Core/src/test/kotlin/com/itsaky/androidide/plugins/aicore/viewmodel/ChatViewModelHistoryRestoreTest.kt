package com.itsaky.androidide.plugins.aicore.viewmodel

import android.content.Context
import android.content.SharedPreferences
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage.Role
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Stand-in project namespaces; the digest itself is ProjectKey's business, not this file's. */
private const val TEST_PROJECT_KEY = "a1b2c3d4e5f60718"
private const val OTHER_PROJECT_KEY = "0f1e2d3c4b5a6978"

private const val SESSIONS_PREFIX = "chat_sessions_"
private const val CURRENT_ID_PREFIX = "current_session_id_"
private const val SESSIONS_KEY = SESSIONS_PREFIX + TEST_PROJECT_KEY
private const val CURRENT_ID_KEY = CURRENT_ID_PREFIX + TEST_PROJECT_KEY

/** The single global keys every release before per-project history wrote to. */
private const val LEGACY_SESSIONS_KEY = "chat_sessions"
private const val LEGACY_CURRENT_ID_KEY = "current_session_id"

/** Mirrors ChatViewModel.MAX_RESTORED_HISTORY, which is private to it. */
private const val MAX_RESTORED_HISTORY = 40

/**
 * A restored conversation must reach the model, not just the screen: switching or reloading used to
 * hand the UI its transcript and the LLM an empty array (ADFA-5584).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelHistoryRestoreTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    /**
     * Backs the preferences mock, so a write is visible to the next read. The legacy migration
     * writes the blob it adopts and reads it straight back, which a stubbed getter cannot show.
     * Concurrent because the persist scope writes from its own thread.
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
    fun givenARestoredSession_whenLoadingStorage_thenHistoryCarriesTheUserAndAgentTurns() {
        seed(
            session(
                "s1",
                message("m1", "what does this build script do", "USER"),
                message("m2", "it applies the plugin builder", "AGENT"),
            )
        )

        val history = restoredViewModel().history.value

        assertEquals(listOf(Role.USER, Role.ASSISTANT), history.map { it.role })
        assertEquals(
            listOf("what does this build script do", "it applies the plugin builder"),
            history.map { it.content },
        )
    }

    @Test
    fun givenSystemAndToolMessages_whenRestoring_thenTheyAreExcludedFromHistory() {
        seed(
            session(
                "s1",
                message("m1", "list the files", "USER"),
                message("m2", "Running list_files", "SYSTEM"),
                message("m3", "app/build.gradle.kts", "TOOL"),
                message("m4", "there is one module", "AGENT"),
            )
        )

        val history = restoredViewModel().history.value

        assertEquals(listOf(Role.USER, Role.ASSISTANT), history.map { it.role })
        assertEquals(listOf("list the files", "there is one module"), history.map { it.content })
    }

    @Test
    fun givenAnUnfinishedAgentTurn_whenRestoring_thenItIsExcludedFromHistory() {
        seed(
            session(
                "s1",
                message("m1", "build the app", "USER"),
                // Zero duration is what finalizeInProgressMessages stamps on a turn Stop cut off.
                message("m2", "the build succ", "AGENT", durationMs = 0),
                // Null is the streaming bubble a persist wrote out before process death.
                message("m3", "the build suc", "AGENT", durationMs = null),
                message("m4", "the build succeeded", "AGENT"),
            )
        )

        val history = restoredViewModel().history.value

        assertEquals(listOf("build the app", "the build succeeded"), history.map { it.content })
    }

    @Test
    fun givenATurnRenderedForDisplay_whenRestoring_thenTheModelsOwnTextIsUsed() {
        seed(
            session(
                "s1",
                message("m1", "delete the file", "USER"),
                message("m2", "The action failed", "AGENT", historyText = "deleted it"),
            )
        )

        val history = restoredViewModel().history.value

        assertEquals(listOf("delete the file", "deleted it"), history.map { it.content })
    }

    @Test
    fun givenABlankHistoryText_whenRestoring_thenTheTurnIsExcludedFromHistory() {
        seed(
            session(
                "s1",
                message("m1", "delete the file", "USER"),
                // A native respond call after a failed tool: no text part, so the bubble is the
                // localized placeholder and the model's own text is empty.
                message("m2", "The action failed", "AGENT", historyText = ""),
            )
        )

        val history = restoredViewModel().history.value

        assertEquals(listOf("delete the file"), history.map { it.content })
    }

    @Test
    fun givenANewestTurnOverTheWholeBudget_whenRestoring_thenItIsTruncatedToTheBudget() {
        seed(
            session(
                "s1",
                message("m1", "older turn", "USER"),
                message("m2", "x".repeat(8_001), "AGENT"),
            )
        )

        val history = restoredViewModel().history.value

        // Dropping it would hand the model nothing at all, the regression this restore removes;
        // exempting it whole would leave every later send over the budget.
        assertEquals(1, history.size)
        assertEquals(Role.ASSISTANT, history.single().role)
        assertEquals(8_000, history.single().content.length)
    }

    @Test
    fun givenTurnsOverTheCharacterBudget_whenRestoring_thenOnlyTheNewestFit() {
        val long = "x".repeat(3_000)
        seed(
            session(
                "s1",
                message("m1", long, "USER"),
                message("m2", long, "AGENT"),
                message("m3", long, "USER"),
            )
        )

        val history = restoredViewModel().history.value

        // The oldest turn is what the budget drops; the two nearest the next message survive.
        assertEquals(listOf(Role.ASSISTANT, Role.USER), history.map { it.role })
    }

    @Test
    fun givenABlankTurn_whenRestoring_thenItIsExcludedFromHistory() {
        seed(
            session(
                "s1",
                message("m1", "hello", "USER"),
                message("m2", "   ", "AGENT"),
            )
        )

        val history = restoredViewModel().history.value

        assertEquals(listOf("hello"), history.map { it.content })
    }

    @Test
    fun givenMoreThanFortyEligibleMessages_whenRestoring_thenOnlyTheLastFortyAreKept() {
        val messages = (1..50).map {
            message("m$it", "turn $it", if (it % 2 == 1) "USER" else "AGENT")
        }
        seed(session("s1", *messages.toTypedArray()))

        val history = restoredViewModel().history.value

        assertEquals(MAX_RESTORED_HISTORY, history.size)
        // takeLast, not take: the newest turns are the ones worth the context window.
        assertEquals("turn 11", history.first().content)
        assertEquals("turn 50", history.last().content)
    }

    @Test
    fun givenTwoSessions_whenSwitchingBetweenThem_thenHistoryFollowsTheVisibleTranscript() {
        seed(
            session("s1", message("m1", "session one fact", "USER")),
            session("s2", message("m2", "session two fact", "USER")),
        )
        val viewModel = restoredViewModel()

        viewModel.switchToSession("s2")
        val second = viewModel.history.value.map { it.content }
        viewModel.switchToSession("s1")
        val first = viewModel.history.value.map { it.content }

        assertEquals(listOf("session two fact"), second)
        assertEquals(listOf("session one fact"), first)
    }

    @Test
    fun givenTwoProjects_whenStorageMovesToTheOther_thenHistoryFollowsTheOpenProject() {
        seed(session("s1", message("m1", "project one fact", "USER")))
        seedProject(
            OTHER_PROJECT_KEY,
            session(
                "s2",
                message("m2", "project two fact", "USER"),
                projectKey = OTHER_PROJECT_KEY,
            ),
        )
        val viewModel = restoredViewModel()
        val first = viewModel.history.value.map { it.content }

        viewModel.initializeStorage(context, OTHER_PROJECT_KEY)

        assertEquals(listOf("project one fact"), first)
        assertEquals(listOf("project two fact"), viewModel.history.value.map { it.content })
        // The outgoing project's transcript is gone from the UI too, not just from the model.
        assertTrue(viewModel.sessions.value.none { it.id == "s1" })
    }

    @Test
    fun givenASessionStampedWithAnotherProject_whenRestoring_thenItIsExcludedFromHistory() {
        seed(
            session("s1", message("m1", "this project's fact", "USER")),
            session(
                "s2",
                message("m2", "another project's fact", "USER"),
                projectKey = OTHER_PROJECT_KEY,
            ),
        )
        val viewModel = restoredViewModel()

        assertTrue(viewModel.sessions.value.none { it.id == "s2" })
        assertEquals(listOf("this project's fact"), viewModel.history.value.map { it.content })
    }

    @Test
    fun givenLegacyGlobalHistory_whenTheFirstProjectOpens_thenItsTurnsReachHistory() {
        // Written before per-project history existed: one global blob, sessions with no stamp.
        stored[LEGACY_SESSIONS_KEY] = blob(
            session("s1", message("m1", "pre-upgrade fact", "USER"), projectKey = null),
            session("s2", message("m2", "last conversation fact", "USER"), projectKey = null),
        )
        stored[LEGACY_CURRENT_ID_KEY] = "s2"

        val viewModel = restoredViewModel()

        // The legacy current id migrates too, so the user's last conversation is the restored one.
        assertEquals(listOf("last conversation fact"), viewModel.history.value.map { it.content })
        assertEquals(listOf("s1", "s2"), viewModel.sessions.value.map { it.id })
    }

    @Test
    fun givenLegacyHistoryAlreadyClaimed_whenASecondProjectOpens_thenItsHistoryIsEmpty() {
        stored[LEGACY_SESSIONS_KEY] = blob(
            session("s1", message("m1", "pre-upgrade fact", "USER"), projectKey = null)
        )
        restoredViewModel()

        val second = restoredViewModel(OTHER_PROJECT_KEY)

        // Claiming is once and for all: the blob belongs to whichever project upgraded first.
        assertTrue(second.history.value.isEmpty())
        assertTrue(second.sessions.value.none { it.id == "s1" })
    }

    @Test
    fun givenLegacyHistoryAndAnExistingProjectBlob_whenRestoring_thenTheProjectBlobWins() {
        seed(session("s1", message("m1", "this project's fact", "USER")))
        stored[LEGACY_SESSIONS_KEY] = blob(
            session("s2", message("m2", "pre-upgrade fact", "USER"), projectKey = null)
        )

        val viewModel = restoredViewModel()

        assertEquals(listOf("this project's fact"), viewModel.history.value.map { it.content })
        assertTrue(viewModel.sessions.value.none { it.id == "s2" })
    }

    @Test
    fun givenTheCurrentSessionCleared_whenPersisting_thenTheSessionIsWrittenEmpty() {
        seed(session("s1", message("m1", "remember-this", "USER")))
        val viewModel = restoredViewModel()

        viewModel.clearMessages()

        assertTrue(viewModel.messages.value.isEmpty())
        assertTrue(viewModel.history.value.isEmpty())
        // The session's own list too, or the next sync or restore brings the cleared chat back.
        assertTrue(viewModel.sessions.value.single { it.id == "s1" }.messages.isEmpty())
        // Writes land on the persist scope's own thread, so this waits rather than racing it.
        verify(timeout = 2_000) {
            editor.putString(SESSIONS_KEY, match { !it.contains("remember-this") })
        }
    }

    @Test
    fun givenTheCurrentSessionDeleted_whenAnotherRemains_thenHistoryMatchesIt() {
        seed(
            session("s1", message("m1", "deleted conversation", "USER")),
            session("s2", message("m2", "surviving conversation", "USER")),
        )
        val viewModel = restoredViewModel()

        viewModel.deleteSession("s1")

        assertEquals(listOf("surviving conversation"), viewModel.history.value.map { it.content })
    }

    @Test
    fun givenTheLastSessionDeleted_whenAnEmptyOneReplacesIt_thenHistoryIsEmpty() {
        seed(session("s1", message("m1", "only conversation", "USER")))
        val viewModel = restoredViewModel()

        viewModel.deleteSession("s1")

        // The replacement is empty, so the deleted conversation must not still be in the context.
        assertTrue(viewModel.history.value.isEmpty())
        // A null current session would render every later message and store none of them.
        assertEquals(1, viewModel.sessions.value.size)
        assertTrue(viewModel.sessions.value.single().messages.isEmpty())
    }

    /** Builds a ViewModel whose storage is already bound to [projectKey]'s namespace. */
    private fun restoredViewModel(projectKey: String = TEST_PROJECT_KEY): ChatViewModel =
        ChatViewModel { null }.also { it.initializeStorage(context, projectKey) }

    /** Publishes [sessions] as this project's stored blob, selecting the first one. */
    private fun seed(vararg sessions: String) = seedProject(TEST_PROJECT_KEY, *sessions)

    /** Publishes [sessions] as [projectKey]'s stored blob, leaving no session selected. */
    private fun seedProject(projectKey: String, vararg sessions: String) {
        stored[SESSIONS_PREFIX + projectKey] = blob(*sessions)
        stored.remove(CURRENT_ID_PREFIX + projectKey)
    }

    private fun blob(vararg sessions: String): String =
        sessions.joinToString(",", prefix = "[", postfix = "]")

    /** A stored session; [projectKey] null omits the stamp, as a pre-namespace release wrote it. */
    private fun session(
        id: String,
        vararg messages: String,
        projectKey: String? = TEST_PROJECT_KEY,
    ): String {
        val stamp = projectKey?.let { """"projectKey":"$it",""" } ?: ""
        return """{"id":"$id","createdAt":1000,$stamp""" +
            """"messages":[${messages.joinToString(",")}]}"""
    }

    private fun message(
        id: String,
        text: String,
        sender: String,
        status: String = "SENT",
        // A finished agent turn is stamped with one; only a cut-off bubble has null or zero.
        durationMs: Long? = if (sender == "AGENT") 1_200 else null,
        historyText: String? = null,
    ): String {
        val duration = durationMs?.let { ""","durationMs":$it""" } ?: ""
        val history = historyText?.let { ""","historyText":"$it"""" } ?: ""
        return """{"id":"$id","text":"$text","sender":"$sender","status":"$status",""" +
            """"timestamp":1$duration$history}"""
    }
}
