package com.itsaky.androidide.plugins.aicore.viewmodel

import android.content.Context
import android.content.SharedPreferences
import com.itsaky.androidide.plugins.aicore.backends.AiBackend
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.SHIPPED_ROOT
import com.itsaky.androidide.plugins.aicore.prompt.config.sharedPromptConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
private const val OTHER_SESSION_ID = "s2"

/** Holds long enough to show a prompt waiting, far short of the title's own timeout. */
private const val HOLD_MS = 200L

/** Upper bound on waiting for work the ViewModel does on its own dispatchers. */
private const val WAIT_MS = 5_000L

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

    /** Runs the prompt config load that activation starts on device; the title is worded from it. */
    private val configScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        // Like the real service: the global cancel reaches whatever call is current, here the title.
        every { llmService.cancelGeneration() } answers { titleResponse.cancel(true); Unit }
        SharedServices.register(LlmInferenceService::class.java, llmService)
        runBlocking { sharedPromptConfig.preload(configScope, DirectoryPromptConfigSource(SHIPPED_ROOT)).await() }
        stored[SESSIONS_KEY] =
            """[{"id":"$SESSION_ID","createdAt":1000,"projectKey":"$TEST_PROJECT_KEY","messages":[""" +
            """{"id":"m1","text":"explain this build script","sender":"USER","status":"SENT","timestamp":1},""" +
            """{"id":"m2","text":"it applies the plugin builder","sender":"AGENT","status":"SENT",""" +
            """"timestamp":2,"durationMs":1200}]}]"""
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        SharedServices.clear()
        sharedPromptConfig.clear()
        configScope.cancel()
    }

    private fun newViewModel() = ChatViewModel { null }.apply {
        initializeStorage(context, TEST_PROJECT_KEY)
    }

    /** A ViewModel whose selected backend is ready; its chat turns never answer. */
    private fun newViewModelWithBackend(): ChatViewModel {
        val backend = mockk<LlmInferenceService.LlmBackend>(relaxed = true)
        every { backend.id } returns AiBackend.DEFAULT_ID
        every { backend.isAvailable } returns true
        every { llmService.availableBackends } returns listOf(backend)
        every { llmService.getBackend(any()) } returns backend
        return newViewModel().apply {
            checkBackendAvailability()
            val deadline = System.currentTimeMillis() + WAIT_MS
            while (!isBackendAvailable.value) {
                check(System.currentTimeMillis() < deadline) { "backend not ready within ${WAIT_MS}ms" }
                Thread.sleep(10)
            }
        }
    }

    /** The title was cancelled, so a late answer is dropped and the chat keeps its prompt's words. */
    private fun assertTitleDroppedForFallback(viewModel: ChatViewModel) {
        titleResponse.complete(LlmInferenceService.LlmResponse.success("Build script walkthrough", 4, 10))
        runBlocking { viewModel.awaitTitleRequest() }

        val session = viewModel.sessions.value.single { it.id == SESSION_ID }
        assertNull(session.generatedTitle)
        assertEquals("explain this build script", session.displayTitle)
        // The title's own job is cancelled; the global cancel would reach other plugins' calls.
        verify(exactly = 0) { llmService.cancelGeneration() }
    }

    @Test
    fun givenATitleInFlight_whenAPromptIsQueued_thenItWaitsForTheTitleWithoutCancellingIt() {
        val viewModel = newViewModel()
        assertTrue(viewModel.requestTitleIfUntitled(llmService, "explain this build script"))

        val released = runBlocking { withTimeoutOrNull(HOLD_MS) { viewModel.awaitTitleRequest() } }

        assertNull(released)
        assertNull(viewModel.sessions.value.single { it.id == SESSION_ID }.generatedTitle)
        verify(exactly = 0) { llmService.cancelGeneration() }
    }

    @Test
    fun givenAQueuedPrompt_whenTheTitleArrives_thenThePromptIsReleasedAndTheTitleKept() {
        val viewModel = newViewModel()
        assertTrue(viewModel.requestTitleIfUntitled(llmService, "explain this build script"))

        titleResponse.complete(LlmInferenceService.LlmResponse.success("Build script walkthrough", 4, 10))
        runBlocking { viewModel.awaitTitleRequest() }

        assertFalse(SESSION_ID in viewModel.titlePending.value)
        assertEquals(
            "Build script walkthrough",
            viewModel.sessions.value.single { it.id == SESSION_ID }.generatedTitle,
        )
    }

    @Test
    fun givenATitleInFlight_whenTheChatIsCleared_thenTheRequestIsDroppedAndItsTitleNeverLands() {
        val viewModel = newViewModel()
        assertTrue(viewModel.requestTitleIfUntitled(llmService, "explain this build script"))

        viewModel.clearMessages()
        titleResponse.complete(LlmInferenceService.LlmResponse.success("Build script walkthrough", 4, 10))
        val released = runBlocking { withTimeoutOrNull(HOLD_MS) { viewModel.awaitTitleRequest() } }

        assertEquals(Unit, released)
        assertNull(viewModel.sessions.value.single { it.id == SESSION_ID }.generatedTitle)
    }

    @Test
    fun givenATitleInFlight_whenSwitchingChats_thenItsSpinnerStopsAndItsTitleNeverLands() {
        stored[SESSIONS_KEY] = stored.getValue(SESSIONS_KEY).removeSuffix("]") +
            """,{"id":"$OTHER_SESSION_ID","createdAt":500,"projectKey":"$TEST_PROJECT_KEY","messages":[]}]"""
        val viewModel = newViewModel()
        assertTrue(viewModel.requestTitleIfUntitled(llmService, "explain this build script"))

        viewModel.switchToSession(OTHER_SESSION_ID)

        assertFalse(SESSION_ID in viewModel.titlePending.value)
        assertTitleDroppedForFallback(viewModel)
    }

    @Test
    fun givenATitleInFlight_whenStopIsPressed_thenItsSpinnerStopsAndItsTitleNeverLands() {
        val viewModel = newViewModel()
        assertTrue(viewModel.requestTitleIfUntitled(llmService, "explain this build script"))

        viewModel.stopProcessing(reason = "test")

        assertFalse(SESSION_ID in viewModel.titlePending.value)
        assertTitleDroppedForFallback(viewModel)
    }

    @Test
    fun givenATitleInFlight_whenTheBackendRefuses_thenTheChatIsNamedAfterItsPrompt() {
        val viewModel = newViewModel()
        assertTrue(viewModel.requestTitleIfUntitled(llmService, "explain this build script"))

        titleResponse.complete(LlmInferenceService.LlmResponse.failure("no titles"))
        runBlocking { viewModel.awaitTitleRequest() }

        assertFalse(SESSION_ID in viewModel.titlePending.value)
        assertEquals("explain this build script", viewModel.sessions.value.single { it.id == SESSION_ID }.displayTitle)
    }

    @Test
    fun givenATitleInFlight_whenTheBackendAnswersBlank_thenTheChatIsNamedAfterItsPrompt() {
        val viewModel = newViewModel()
        assertTrue(viewModel.requestTitleIfUntitled(llmService, "explain this build script"))

        titleResponse.complete(LlmInferenceService.LlmResponse.success("  \n ", 1, 10))
        runBlocking { viewModel.awaitTitleRequest() }

        assertFalse(SESSION_ID in viewModel.titlePending.value)
        assertEquals("explain this build script", viewModel.sessions.value.single { it.id == SESSION_ID }.displayTitle)
    }

    @Test
    fun givenATitleInFlight_whenTheRequestThrows_thenTheChatIsNamedAfterItsPrompt() {
        val viewModel = newViewModel()
        assertTrue(viewModel.requestTitleIfUntitled(llmService, "explain this build script"))

        titleResponse.completeExceptionally(IllegalStateException("backend gone"))
        runBlocking { viewModel.awaitTitleRequest() }

        assertFalse(SESSION_ID in viewModel.titlePending.value)
        assertEquals("explain this build script", viewModel.sessions.value.single { it.id == SESSION_ID }.displayTitle)
    }

    @Test
    fun givenANewChat_whenItsFirstPromptIsSent_thenTheAgentRunsOnlyAfterTheTitleSettles() {
        stored[SESSIONS_KEY] =
            """[{"id":"$SESSION_ID","createdAt":1000,"projectKey":"$TEST_PROJECT_KEY","messages":[]}]"""
        val viewModel = newViewModelWithBackend()

        assertTrue(viewModel.sendMessage("add a dark mode toggle"))
        Thread.sleep(HOLD_MS)

        verify(exactly = 0) { llmService.generateStreamingWithTools(any(), any(), any(), any(), any()) }
        assertTrue(SESSION_ID in viewModel.titlePending.value)

        titleResponse.complete(LlmInferenceService.LlmResponse.success("Dark mode toggle", 4, 10))

        verify(timeout = WAIT_MS) { llmService.generateStreamingWithTools(any(), any(), any(), any(), any()) }
        assertEquals("Dark mode toggle", viewModel.sessions.value.single { it.id == SESSION_ID }.generatedTitle)
        viewModel.stopProcessing(reason = "test")
    }

    @Test
    fun givenAnEmptyChat_whenItsFirstPromptIsTitled_thenTheTitleIsAskedFromThatPromptAlone() {
        stored[SESSIONS_KEY] =
            """[{"id":"$SESSION_ID","createdAt":1000,"projectKey":"$TEST_PROJECT_KEY","messages":[]}]"""
        val viewModel = newViewModel()

        assertTrue(viewModel.requestTitleIfUntitled(llmService, "add a dark mode toggle"))

        assertTrue(SESSION_ID in viewModel.titlePending.value)
        verify { llmService.generateCompletion(match { it.contains("add a dark mode toggle") }, any()) }
    }

    @Test
    fun givenNoTitleInFlight_whenAPromptIsSent_thenItRunsAtOnceAndNothingIsCancelled() {
        val viewModel = newViewModel()

        runBlocking { viewModel.awaitTitleRequest() }

        verify(exactly = 0) { llmService.cancelGeneration() }
    }
}
