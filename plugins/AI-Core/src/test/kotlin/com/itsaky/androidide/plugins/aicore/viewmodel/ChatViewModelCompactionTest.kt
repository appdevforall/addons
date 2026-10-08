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
import io.mockk.verify
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

/** Upper bound on waiting for work the ViewModel does on its own dispatchers. */
private const val WAIT_MS = 5_000L

/** A reply the agent loop reads as a finished turn: the terminal respond call. */
private const val RESPOND_REPLY =
    """<tool_call>{"tool":"respond","args":{"message":"done"}}</tool_call>"""

/** Past 80% of the local backend's budget on its own, so the next send compacts first. */
private val LARGE_PROMPT = "x".repeat(HistoryCompactor.LOCAL_BUDGET_TOKENS * HistoryCompactor.CHARS_PER_TOKEN)

/**
 * A chat near its backend's context limit is summarized before the next send, and a failed run is
 * rolled back rather than resent with every retry (ADFA-6345).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelCompactionTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    /** Backs the preferences mock; concurrent because the persist scope writes from its own thread. */
    private val stored = ConcurrentHashMap<String, String>()

    private val configScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The history each model turn was sent with, in order. */
    private val sentHistories = CopyOnWriteArrayList<List<LlmInferenceService.ChatMessage>>()

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
    fun givenABackendError_whenTheRunFails_thenTheHistoryKeepsItsPreRunSize() {
        val (viewModel, _) = newViewModelWithBackend(summary = null) { callback ->
            callback.onError("prompt is too long")
        }

        send(viewModel, "Capital of new york")
        send(viewModel, "Capital of new york")

        assertEquals(emptyList<LlmInferenceService.ChatMessage>(), viewModel.history.value)
        // The resend went out on the same history, not one grown by the failed turn.
        assertEquals(listOf(emptyList<LlmInferenceService.ChatMessage>(), emptyList()), sentHistories.toList())
    }

    @Test
    fun givenAHistoryOverEightyPercentOfTheBudget_whenSending_thenItIsSummarizedFirst() {
        val (viewModel, service) = newViewModelWithBackend(summary = "The user pasted a large file.") { callback ->
            callback.onComplete(LlmInferenceService.LlmResponse.success(RESPOND_REPLY, 4, 10))
        }
        send(viewModel, LARGE_PROMPT)

        send(viewModel, "and now?")

        verify(exactly = 1) { service.generateWithHistory(any(), any(), any()) }
        val summaryTurn = "Summary of the earlier conversation:\nThe user pasted a large file."
        assertEquals(listOf(Role.USER to summaryTurn), sentHistories.last().map { it.role to it.content })
        assertEquals(summaryTurn, viewModel.history.value.first().content)
        assertEquals(summaryTurn, viewModel.sessions.value.single().compaction?.summary)
        // Compaction rewrites the model's history only; the transcript keeps the summarized prompt.
        assertTrue(viewModel.messages.value.any { it.sender == Sender.USER && it.text == LARGE_PROMPT })
        assertTrue(viewModel.sessions.value.single().messages.any { it.text == LARGE_PROMPT })
        val prompt = viewModel.messages.value.last { it.sender == Sender.USER }
        assertEquals(prompt.id, viewModel.sessions.value.single().compaction?.firstMessageId)
    }

    @Test
    fun givenASummarizerThatFails_whenSendingPastTheBudget_thenTheHistoryFallsBackToTheRestoreCap() {
        val (viewModel, _) = newViewModelWithBackend(summary = null) { callback ->
            callback.onComplete(LlmInferenceService.LlmResponse.success(RESPOND_REPLY, 4, 10))
        }
        send(viewModel, LARGE_PROMPT)

        send(viewModel, "and now?")

        val sent = sentHistories.last()
        assertTrue(sent.isNotEmpty())
        assertFalse(sent.any { it.content.contains(LARGE_PROMPT) })
        assertEquals(null, viewModel.sessions.value.single().compaction)
    }

    @Test
    fun givenAHistoryUnderTheBudget_whenSending_thenNothingIsSummarized() {
        val (viewModel, service) = newViewModelWithBackend(summary = "unused") { callback ->
            callback.onComplete(LlmInferenceService.LlmResponse.success(RESPOND_REPLY, 4, 10))
        }
        send(viewModel, "first")

        send(viewModel, "second")

        verify(exactly = 0) { service.generateWithHistory(any(), any(), any()) }
        assertEquals("first", sentHistories.last().first().content)
    }

    @Test
    fun givenARunThatFailed_whenRetried_thenTheHistoryIsSummarizedFirstWhateverItsSize() {
        val failNext = AtomicBoolean(false)
        val (viewModel, service) = newViewModelWithBackend(summary = "The user said first.") { callback ->
            if (failNext.getAndSet(false)) {
                callback.onError("This conversation is longer than the model can read.")
            } else {
                callback.onComplete(LlmInferenceService.LlmResponse.success(RESPOND_REPLY, 4, 10))
            }
        }
        send(viewModel, "first")
        failNext.set(true)
        send(viewModel, "second")

        retry(viewModel)

        verify(exactly = 1) { service.generateWithHistory(any(), any(), any()) }
        val summaryTurn = "Summary of the earlier conversation:\nThe user said first."
        assertEquals(listOf(Role.USER to summaryTurn), sentHistories.last().map { it.role to it.content })
    }

    @Test
    fun givenARunThatFailed_whenSentAgainWithoutRetry_thenNothingIsSummarized() {
        val failNext = AtomicBoolean(false)
        val (viewModel, service) = newViewModelWithBackend(summary = "unused") { callback ->
            if (failNext.getAndSet(false)) {
                callback.onError("network down")
            } else {
                callback.onComplete(LlmInferenceService.LlmResponse.success(RESPOND_REPLY, 4, 10))
            }
        }
        send(viewModel, "first")
        failNext.set(true)
        send(viewModel, "second")

        send(viewModel, "third")

        verify(exactly = 0) { service.generateWithHistory(any(), any(), any()) }
    }

    /** Taps Retry on the last run and waits for the run it starts to finish. */
    private fun retry(viewModel: ChatViewModel) {
        val turns = sentHistories.size
        viewModel.retryLastRun()
        awaitCondition { sentHistories.size == turns + 1 && viewModel.agentState.value !is AgentState.Processing }
        awaitCondition { viewModel.canChangePrompts.value || viewModel.agentState.value is AgentState.Error }
    }

    /** Sends [text] and waits for its run to finish. */
    private fun send(viewModel: ChatViewModel, text: String) {
        val turns = sentHistories.size
        awaitCondition { viewModel.sendMessage(text) }
        awaitCondition { sentHistories.size == turns + 1 && viewModel.agentState.value !is AgentState.Processing }
        awaitCondition { viewModel.canChangePrompts.value || viewModel.agentState.value is AgentState.Error }
    }

    /**
     * A ViewModel whose local backend is ready, generates by calling [reply] with the turn's
     * callback, and summarizes with [summary], or fails to when it is null.
     */
    private fun newViewModelWithBackend(
        summary: String?,
        reply: (LlmInferenceService.ToolStreamCallback) -> Unit,
    ): Pair<ChatViewModel, LlmInferenceService> {
        val backend = mockk<LlmInferenceService.LlmBackend>(relaxed = true)
        every { backend.id } returns AiBackend.LOCAL_ID
        every { backend.name } returns "Local LLM"
        every { backend.isAvailable } returns true
        val service = mockk<LlmInferenceService>(relaxed = true)
        every { service.availableBackends } returns listOf(backend)
        every { service.getBackend(any()) } returns backend
        // The title request after a first reply; refused, so the chat simply keeps its prompt.
        every { service.generateCompletion(any(), any()) } returns
            CompletableFuture.completedFuture(LlmInferenceService.LlmResponse.failure("no titles"))
        every { service.generateWithHistory(any(), any(), any()) } returns CompletableFuture.completedFuture(
            summary?.let { LlmInferenceService.LlmResponse.success(it, 4, 10) }
                ?: LlmInferenceService.LlmResponse.failure("prompt is too long")
        )
        every { service.generateStreamingWithTools(any(), any(), any(), any(), any()) } answers {
            sentHistories += arg<List<LlmInferenceService.ChatMessage>>(1).toList()
            reply(arg(4))
        }
        SharedServices.register(LlmInferenceService::class.java, service)
        val viewModel = ChatViewModel { null }.apply {
            initializeStorage(context, TEST_PROJECT_KEY)
            checkBackendAvailability()
            awaitCondition { isBackendAvailable.value }
        }
        return viewModel to service
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${WAIT_MS}ms" }
            Thread.sleep(10)
        }
    }
}
