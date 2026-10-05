package com.itsaky.androidide.plugins.aicore.viewmodel

import android.content.Context
import android.content.SharedPreferences
import com.itsaky.androidide.plugins.aicore.backends.AiBackend
import com.itsaky.androidide.plugins.aicore.models.AgentState
import com.itsaky.androidide.plugins.aicore.models.Sender
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.SHIPPED_ROOT
import com.itsaky.androidide.plugins.aicore.prompt.config.sharedPromptConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage.Role
import com.itsaky.androidide.plugins.services.SharedServices
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Stand-in project namespace; the digest itself is ProjectKey's business, not this file's. */
private const val TEST_PROJECT_KEY = "a1b2c3d4e5f60718"
private const val SESSIONS_KEY = "chat_sessions_$TEST_PROJECT_KEY"

/** Upper bound on waiting for work the ViewModel does on its own dispatchers. */
private const val WAIT_MS = 5_000L

/** A reply the agent loop reads as a finished turn: the terminal respond call. */
private const val RESPOND_REPLY =
    """<tool_call>{"tool":"respond","args":{"message":"done"}}</tool_call>"""

/**
 * Editing the newest prompt rewinds the conversation to just before it and runs the edited text
 * (ADFA-6276): the transcript, the model's history and the stored session all lose the original,
 * and nothing is discarded while a run is in flight or when the send is refused. Editing an older
 * prompt forks instead: the original stays as a version, and each version's history is its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelPromptEditTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    /** Backs the preferences mock; concurrent because the persist scope writes from its own thread. */
    private val stored = ConcurrentHashMap<String, String>()

    private val configScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        // ChatViewModel's stateIn() calls run on viewModelScope, i.e. Dispatchers.Main.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        SharedServices.clear()
        context = mockk(relaxed = true)
        sharedPreferences = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns sharedPreferences
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
        // A run renders its prompts from this config, which the plugin loads on activation.
        runBlocking { sharedPromptConfig.preload(configScope, DirectoryPromptConfigSource(SHIPPED_ROOT)).await() }
    }

    @After
    fun tearDown() {
        SharedServices.clear()
        Dispatchers.resetMain()
        sharedPromptConfig.clear()
        configScope.cancel()
    }

    @Test
    fun givenARestoredChat_whenRewindingToTheNewestPrompt_thenTranscriptAndHistoryEndBeforeIt() {
        seedTwoExchanges()
        val viewModel = newViewModel()

        assertTrue(viewModel.rewindTo("u2"))

        assertEquals(listOf("u1", "a1"), viewModel.messages.value.map { it.id })
        assertEquals(listOf("u1", "a1"), viewModel.sessions.value.single { it.id == "s1" }.messages.map { it.id })
        assertEquals(listOf(Role.USER, Role.ASSISTANT), viewModel.history.value.map { it.role })
        assertEquals(listOf("first prompt", "first reply"), viewModel.history.value.map { it.content })
    }

    @Test
    fun givenAnOlderPrompt_whenRewindingToIt_thenNothingChanges() {
        seedTwoExchanges()
        val viewModel = newViewModel()

        assertFalse(viewModel.rewindTo("u1"))

        assertEquals(listOf("u1", "a1", "u2", "a2"), viewModel.messages.value.map { it.id })
        assertEquals(4, viewModel.history.value.size)
    }

    @Test
    fun givenARewind_whenTheChatIsReopened_thenTheDiscardedPromptIsGone() {
        seedTwoExchanges()
        newViewModel().rewindTo("u2")

        // The write runs on the persist scope's own thread.
        awaitCondition { "second prompt" !in stored.getValue(SESSIONS_KEY) }
        val reopened = newViewModel()

        assertEquals(listOf("u1", "a1"), reopened.messages.value.map { it.id })
    }

    @Test
    fun givenNoBackend_whenEditingTheNewestPrompt_thenItIsRefusedAndNothingIsDiscarded() {
        seedTwoExchanges()
        val viewModel = newViewModel()

        val result = viewModel.editPrompt("u2", "second prompt, fixed")

        assertEquals(ChatViewModel.EditResult.REFUSED, result)
        assertTrue(viewModel.messages.value.any { it.id == "u2" })
        assertEquals(4, viewModel.history.value.size)
    }

    @Test
    fun givenARunInProgress_whenEditingItsPrompt_thenTheEditIsBlocked() {
        // A backend that never answers, so the run stays in flight.
        val viewModel = newViewModelWithBackend { }
        assertTrue(viewModel.sendMessage("build the app"))
        val prompt = awaitUserMessage(viewModel, "build the app")

        val result = viewModel.editPrompt(prompt, "build and run the app")

        assertEquals(ChatViewModel.EditResult.BUSY, result)
        assertFalse(viewModel.rewindTo(prompt))
        assertTrue(viewModel.messages.value.any { it.id == prompt })
        viewModel.stopProcessing(reason = "test")
    }

    @Test
    fun givenAFinishedRun_whenEditingItsPrompt_thenTheNextRunStartsFromBeforeIt() {
        val viewModel = newViewModelWithBackend { callback ->
            callback.onComplete(LlmInferenceService.LlmResponse.success(RESPOND_REPLY, 4, 10))
        }
        assertTrue(viewModel.sendMessage("build teh app"))
        val prompt = awaitUserMessage(viewModel, "build teh app")
        awaitCondition { viewModel.history.value.size == 2 && viewModel.agentState.value == AgentState.Idle }

        val result = viewModel.editPrompt(prompt, "build the app")

        assertEquals(ChatViewModel.EditResult.STARTED, result)
        awaitCondition {
            viewModel.history.value.firstOrNull()?.content == "build the app" &&
                viewModel.agentState.value == AgentState.Idle
        }
        assertEquals(listOf(Role.USER, Role.ASSISTANT), viewModel.history.value.map { it.role })
        assertEquals(
            listOf("build the app"),
            viewModel.messages.value.filter { it.sender == Sender.USER }.map { it.text },
        )
    }

    @Test
    fun givenTwoFinishedExchanges_whenEditingTheFirstPrompt_thenTheChatForksAndTheModelSeesOnlyTheNewBranch() {
        val viewModel = newViewModelWithBackend { callback ->
            callback.onComplete(LlmInferenceService.LlmResponse.success(RESPOND_REPLY, 4, 10))
        }
        val first = sendAndAwait(viewModel, "build the app")
        sendAndAwait(viewModel, "now run the tests")

        val result = viewModel.editPrompt(first, "build the release app")

        assertEquals(ChatViewModel.EditResult.STARTED, result)
        awaitCondition {
            viewModel.history.value.size == 2 && viewModel.agentState.value == AgentState.Idle
        }
        assertEquals(
            listOf("build the release app"),
            viewModel.messages.value.filter { it.sender == Sender.USER }.map { it.text },
        )
        assertEquals("build the release app", viewModel.history.value.first().content)
        val edited = viewModel.messages.value.first { it.sender == Sender.USER }.id
        assertEquals(ChatBranches.Position(1, 2), viewModel.promptVersions.value[edited])
    }

    @Test
    fun givenAForkedChat_whenSwitchingToTheOriginal_thenItsWholeBranchAndHistoryComeBack() {
        val viewModel = newViewModelWithBackend { callback ->
            callback.onComplete(LlmInferenceService.LlmResponse.success(RESPOND_REPLY, 4, 10))
        }
        val first = sendAndAwait(viewModel, "build the app")
        sendAndAwait(viewModel, "now run the tests")
        viewModel.editPrompt(first, "build the release app")
        awaitCondition {
            viewModel.history.value.size == 2 && viewModel.agentState.value == AgentState.Idle
        }
        val edited = viewModel.messages.value.first { it.sender == Sender.USER }.id

        assertTrue(viewModel.switchPromptVersion(edited, -1))

        assertEquals(
            listOf("build the app", "now run the tests"),
            viewModel.messages.value.filter { it.sender == Sender.USER }.map { it.text },
        )
        assertTrue(viewModel.history.value.none { it.content == "build the release app" })
        assertEquals(ChatBranches.Position(0, 2), viewModel.promptVersions.value[first])
        assertFalse(viewModel.switchPromptVersion(first, -1))
    }

    @Test
    fun givenAForkedChat_whenTheChatIsReopened_thenBothVersionsAreStillThere() {
        val viewModel = newViewModelWithBackend { callback ->
            callback.onComplete(LlmInferenceService.LlmResponse.success(RESPOND_REPLY, 4, 10))
        }
        val first = sendAndAwait(viewModel, "build the app")
        sendAndAwait(viewModel, "now run the tests")
        viewModel.editPrompt(first, "build the release app")
        awaitCondition {
            viewModel.history.value.size == 2 && viewModel.agentState.value == AgentState.Idle
        }
        viewModel.persistState()
        awaitCondition { "now run the tests" in stored.getValue(SESSIONS_KEY) }

        val reopened = newViewModel()

        val edited = reopened.messages.value.first { it.sender == Sender.USER }
        assertEquals("build the release app", edited.text)
        assertEquals(ChatBranches.Position(1, 2), reopened.promptVersions.value[edited.id])
    }

    @Test
    fun givenAForkedChat_whenClearingIt_thenNoVersionIsLeft() {
        val viewModel = newViewModelWithBackend { callback ->
            callback.onComplete(LlmInferenceService.LlmResponse.success(RESPOND_REPLY, 4, 10))
        }
        val first = sendAndAwait(viewModel, "build the app")
        sendAndAwait(viewModel, "now run the tests")
        viewModel.editPrompt(first, "build the release app")
        awaitCondition { viewModel.agentState.value == AgentState.Idle && viewModel.history.value.size == 2 }

        viewModel.clearMessages()

        assertTrue(viewModel.sessions.value.all { it.otherBranches.isNullOrEmpty() })
        assertTrue(viewModel.promptVersions.value.isEmpty())
    }

    @Test
    fun givenAnAgentMessage_whenEditingIt_thenItIsNotEditable() {
        seedTwoExchanges()
        val viewModel = newViewModel()

        assertEquals(ChatViewModel.EditResult.NOT_EDITABLE, viewModel.editPrompt("a1", "anything"))
        assertEquals(4, viewModel.messages.value.size)
    }

    @Test
    fun givenAComposerThatRestoresItsDraftOnTheCut_whenEditing_thenThePromptKeepsTheEditsFiles() {
        val viewModel = newViewModelWithBackend { callback ->
            callback.onComplete(LlmInferenceService.LlmResponse.success(RESPOND_REPLY, 4, 10))
        }
        val prompt = sendAndAwait(viewModel, "build the app")
        viewModel.setContextFiles(listOf(File("/project/Edited.kt")))
        // As ChatFragment does: once the edited prompt is gone, the draft's files come back.
        val composer = CoroutineScope(Dispatchers.Main).launch {
            viewModel.messages.collect { messages ->
                if (messages.none { it.id == prompt }) viewModel.setContextFiles(listOf(File("/project/Draft.kt")))
            }
        }

        assertEquals(ChatViewModel.EditResult.STARTED, viewModel.editPrompt(prompt, "build the release app"))

        composer.cancel()
        val edited = viewModel.messages.value.single { it.sender == Sender.USER }
        assertEquals(listOf(File("/project/Edited.kt").absolutePath), edited.contextFiles)
        awaitCondition { viewModel.agentState.value == AgentState.Idle }
    }

    @Test
    fun givenTheNewestPromptIsAVersion_whenEditingIt_thenItIsReplacedAndTheOriginalVersionStays() {
        val viewModel = newViewModelWithBackend { callback ->
            callback.onComplete(LlmInferenceService.LlmResponse.success(RESPOND_REPLY, 4, 10))
        }
        val first = sendAndAwait(viewModel, "build the app")
        sendAndAwait(viewModel, "now run the tests")
        viewModel.editPrompt(first, "build the release app")
        awaitCondition { viewModel.agentState.value == AgentState.Idle && viewModel.history.value.size == 2 }
        val version = viewModel.messages.value.single { it.sender == Sender.USER }.id

        assertEquals(ChatViewModel.EditResult.STARTED, viewModel.editPrompt(version, "build the debug app"))
        awaitCondition { viewModel.agentState.value == AgentState.Idle && viewModel.history.value.size == 2 }

        val replacement = viewModel.messages.value.single { it.sender == Sender.USER }
        assertEquals("build the debug app", replacement.text)
        assertEquals(ChatBranches.Position(1, 2), viewModel.promptVersions.value[replacement.id])
        val stored = viewModel.sessions.value.single().otherBranches.orEmpty()
        assertTrue(stored.none { it.id == version })
        assertTrue(viewModel.switchPromptVersion(replacement.id, -1))
        assertEquals(first, viewModel.messages.value.first().id)
    }

    @Test
    fun givenAForkedChat_whenSwitchingPastEitherEnd_thenNothingChanges() {
        val viewModel = newViewModelWithBackend { callback ->
            callback.onComplete(LlmInferenceService.LlmResponse.success(RESPOND_REPLY, 4, 10))
        }
        val first = sendAndAwait(viewModel, "build the app")
        sendAndAwait(viewModel, "now run the tests")
        viewModel.editPrompt(first, "build the release app")
        awaitCondition { viewModel.agentState.value == AgentState.Idle && viewModel.history.value.size == 2 }
        val edited = viewModel.messages.value.single { it.sender == Sender.USER }.id

        assertFalse(viewModel.switchPromptVersion(edited, 1))
        assertFalse(viewModel.switchPromptVersion(edited, -2))
        assertEquals(edited, viewModel.messages.value.first().id)
    }

    @Test
    fun givenARunInProgress_whenSwitchingVersions_thenTheSwitchIsBlocked() {
        var answer = true
        val viewModel = newViewModelWithBackend { callback ->
            if (answer) callback.onComplete(LlmInferenceService.LlmResponse.success(RESPOND_REPLY, 4, 10))
        }
        val first = sendAndAwait(viewModel, "build the app")
        sendAndAwait(viewModel, "now run the tests")
        answer = false

        viewModel.editPrompt(first, "build the release app")
        val edited = viewModel.messages.value.single { it.sender == Sender.USER }.id

        assertFalse(viewModel.switchPromptVersion(edited, -1))
        assertEquals(edited, viewModel.messages.value.first().id)
        viewModel.stopProcessing(reason = "test")
    }

    @Test
    fun givenAnEditStoppedAtOnce_whenTheRunEnds_thenThePromptHoldsTheForkAndTheChatStillSends() {
        var answer = true
        val viewModel = newViewModelWithBackend { callback ->
            if (answer) callback.onComplete(LlmInferenceService.LlmResponse.success(RESPOND_REPLY, 4, 10))
        }
        val first = sendAndAwait(viewModel, "build the app")
        sendAndAwait(viewModel, "now run the tests")
        answer = false

        viewModel.editPrompt(first, "build the release app")
        viewModel.stopProcessing(reason = "test")

        val edited = viewModel.messages.value.single { it.sender == Sender.USER }
        assertEquals("build the release app", edited.text)
        assertEquals(ChatBranches.Position(1, 2), viewModel.promptVersions.value[edited.id])
        answer = true
        awaitCondition { viewModel.sendMessage("and run it") }
    }

    /** Sends [text], waits for its run to finish, and returns the prompt's message id. */
    private fun sendAndAwait(viewModel: ChatViewModel, text: String): String {
        val before = viewModel.history.value.size
        assertTrue(viewModel.sendMessage(text))
        val id = awaitUserMessage(viewModel, text)
        awaitCondition {
            viewModel.history.value.size == before + 2 && viewModel.agentState.value == AgentState.Idle
        }
        return id
    }

    private fun newViewModel() = ChatViewModel { null }.apply {
        initializeStorage(context, TEST_PROJECT_KEY)
    }

    /**
     * A ViewModel whose selected backend is ready and generates by calling [reply] with the
     * turn's callback.
     */
    private fun newViewModelWithBackend(
        reply: (LlmInferenceService.ToolStreamCallback) -> Unit,
    ): ChatViewModel {
        val backend = mockk<LlmInferenceService.LlmBackend>(relaxed = true)
        every { backend.id } returns AiBackend.DEFAULT_ID
        every { backend.name } returns "Local LLM"
        every { backend.isAvailable } returns true
        val service = mockk<LlmInferenceService>(relaxed = true)
        every { service.availableBackends } returns listOf(backend)
        every { service.getBackend(any()) } returns backend
        // The title request after a first reply; refused, so the chat simply keeps its prompt.
        every { service.generateCompletion(any(), any()) } returns
            CompletableFuture.completedFuture(LlmInferenceService.LlmResponse.failure("no titles"))
        every { service.generateStreamingWithTools(any(), any(), any(), any(), any()) } answers {
            reply(arg(4))
        }
        SharedServices.register(LlmInferenceService::class.java, service)
        return newViewModel().apply {
            checkBackendAvailability()
            awaitCondition { isBackendAvailable.value }
        }
    }

    /** @return the id of the user message carrying [text], once the run has posted it. */
    private fun awaitUserMessage(viewModel: ChatViewModel, text: String): String {
        awaitCondition { viewModel.messages.value.any { it.sender == Sender.USER && it.text == text } }
        return viewModel.messages.value.first { it.sender == Sender.USER && it.text == text }.id
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${WAIT_MS}ms" }
            Thread.sleep(10)
        }
    }

    private fun seedTwoExchanges() {
        stored[SESSIONS_KEY] =
            """[{"id":"s1","createdAt":1000,"projectKey":"$TEST_PROJECT_KEY","messages":[""" +
            """{"id":"u1","text":"first prompt","sender":"USER","status":"SENT","timestamp":1},""" +
            """{"id":"a1","text":"first reply","sender":"AGENT","status":"COMPLETED",""" +
            """"timestamp":2,"durationMs":1200},""" +
            """{"id":"u2","text":"second prompt","sender":"USER","status":"SENT","timestamp":3},""" +
            """{"id":"a2","text":"second reply","sender":"AGENT","status":"COMPLETED",""" +
            """"timestamp":4,"durationMs":1200}]}]"""
    }
}
