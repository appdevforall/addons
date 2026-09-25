package com.itsaky.androidide.plugins.aicore.viewmodel

import android.content.Context
import android.content.SharedPreferences
import com.itsaky.androidide.plugins.services.LlmInferenceService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Stand-in project namespace; the digest itself is ProjectKey's business, not this file's. */
private const val TEST_PROJECT_KEY = "a1b2c3d4e5f60718"
private const val SESSIONS_KEY = "chat_sessions_$TEST_PROJECT_KEY"
private const val SESSION_ID = "s1"

/** Holds long enough to show a prompt waiting, far short of the title's own timeout. */
private const val HOLD_MS = 200L

/**
 * A prompt sent while the chat title is still being written queues behind it (ADFA-6214): the
 * title finishes, then the prompt runs, and nothing is cancelled to make room for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTitleQueueTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var llmService: LlmInferenceService
    private lateinit var titleResponse: CompletableFuture<LlmInferenceService.LlmResponse>

    /** Backs the preferences mock; concurrent because the persist scope writes from its own thread. */
    private val stored = ConcurrentHashMap<String, String>()

    @Before
    fun setUp() {
        // ChatViewModel's stateIn() calls run on viewModelScope, i.e. Dispatchers.Main.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = mockk(relaxed = true)
        sharedPreferences = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns sharedPreferences
        every { sharedPreferences.getString(any(), any()) } answers {
            stored[firstArg<String>()] ?: secondArg<String?>()
        }
        every { sharedPreferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        // A title that arrives only when the test completes it.
        titleResponse = CompletableFuture()
        llmService = mockk(relaxed = true)
        every { llmService.generateCompletion(any(), any()) } returns titleResponse
        stored[SESSIONS_KEY] =
            """[{"id":"$SESSION_ID","createdAt":1000,"projectKey":"$TEST_PROJECT_KEY","messages":[""" +
            """{"id":"m1","text":"explain this build script","sender":"USER","status":"SENT","timestamp":1},""" +
            """{"id":"m2","text":"it applies the plugin builder","sender":"AGENT","status":"SENT",""" +
            """"timestamp":2,"durationMs":1200}]}]"""
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = ChatViewModel { null }.apply {
        initializeStorage(context, TEST_PROJECT_KEY)
    }

    @Test
    fun givenATitleInFlight_whenAPromptIsQueued_thenItWaitsForTheTitleWithoutCancellingIt() {
        val viewModel = newViewModel()
        assertTrue(viewModel.requestTitleIfUntitled(llmService))

        val released = runBlocking { withTimeoutOrNull(HOLD_MS) { viewModel.awaitTitleRequest() } }

        assertNull(released)
        assertNull(viewModel.sessions.value.single { it.id == SESSION_ID }.generatedTitle)
        verify(exactly = 0) { llmService.cancelGeneration() }
    }

    @Test
    fun givenAQueuedPrompt_whenTheTitleArrives_thenThePromptIsReleasedAndTheTitleKept() {
        val viewModel = newViewModel()
        assertTrue(viewModel.requestTitleIfUntitled(llmService))

        titleResponse.complete(LlmInferenceService.LlmResponse.success("Build script walkthrough", 4, 10))
        runBlocking { viewModel.awaitTitleRequest() }

        assertFalse(SESSION_ID in viewModel.titlePending.value)
        assertEquals(
            "Build script walkthrough",
            viewModel.sessions.value.single { it.id == SESSION_ID }.generatedTitle,
        )
    }

    @Test
    fun givenNoTitleInFlight_whenAPromptIsSent_thenItRunsAtOnceAndNothingIsCancelled() {
        val viewModel = newViewModel()

        runBlocking { viewModel.awaitTitleRequest() }

        verify(exactly = 0) { llmService.cancelGeneration() }
    }
}
