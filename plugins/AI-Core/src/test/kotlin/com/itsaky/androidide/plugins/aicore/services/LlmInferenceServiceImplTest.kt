package com.itsaky.androidide.plugins.aicore.services

import com.itsaky.androidide.plugins.aicore.backends.AiBackend
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import com.itsaky.androidide.plugins.services.SharedServices
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CompletableFuture
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LlmInferenceServiceImplTest {

    private lateinit var service: LlmInferenceServiceImpl

    @Before
    fun setup() {
        // No AI Assistant PluginContext registered → no stored preference, so AUTO resolves
        // via the LOCAL default and what is installed, not a value left by another test.
        SharedServices.clear()
        service = LlmInferenceServiceImpl()
    }

    private fun mockBackend(
        backendId: String,
        available: Boolean = true,
        text: String = "generated",
        displayName: String = backendId,
    ) = mockk<LlmBackend> {
        every { getId() } returns backendId
        every { getName() } returns displayName
        every { isAvailable() } returns available
        every { generate(any(), any()) } returns CompletableFuture.completedFuture(
            LlmResponse.success(text, 1, 1)
        )
    }

    @Test
    fun testRegisterBackend() {
        val mockBackend = mockk<LlmBackend> {
            every { getId() } returns "test-backend"
            every { getName() } returns "Test Backend"
            every { isAvailable() } returns true
        }

        service.registerBackend(mockBackend)

        val backends = service.getAvailableBackends()
        assertEquals(1, backends.size)
        assertEquals("test-backend", backends[0].getId())
    }

    @Test
    fun givenSeveralBackends_whenListingThem_thenTheSelectorsOrderIsReturned() {
        // Listed by display name, not by the registry map's hash order, or a caller reading this
        // list disagrees with the settings selector about which backend comes first.
        service.registerBackend(mockBackend("zulu", displayName = "Alpha"))
        service.registerBackend(mockBackend("alpha", displayName = "Zulu"))

        val ids = service.getAvailableBackends().map { it.id }

        assertEquals(listOf("zulu", "alpha"), ids)
    }

    @Test
    fun testGetBackend() {
        val mockBackend = mockk<LlmBackend> {
            every { getId() } returns "test-backend"
            every { getName() } returns "Test Backend"
            every { isAvailable() } returns true
        }

        service.registerBackend(mockBackend)
        val backend = service.getBackend("test-backend")

        assertNotNull(backend)
        assertEquals("test-backend", backend!!.getId())
    }

    @Test
    fun testUnregisterBackend() {
        val mockBackend = mockk<LlmBackend> {
            every { getId() } returns "test-backend"
            every { getName() } returns "Test Backend"
            every { isAvailable() } returns true
        }

        service.registerBackend(mockBackend)
        service.unregisterBackend("test-backend")

        val backend = service.getBackend("test-backend")
        assertNull(backend)
    }

    @Test
    fun testIsBackendAvailable() {
        val mockBackend = mockk<LlmBackend> {
            every { getId() } returns "test-backend"
            every { getName() } returns "Test Backend"
            every { isAvailable() } returns true
        }

        service.registerBackend(mockBackend)
        assertTrue(service.isBackendAvailable("test-backend"))
        assertFalse(service.isBackendAvailable("nonexistent"))
    }

    @Test
    fun testGenerateCompletion() {
        val mockBackend = mockk<LlmBackend> {
            every { getId() } returns "test-backend"
            every { getName() } returns "Test Backend"
            every { isAvailable() } returns true
            every { generate(any(), any()) } returns CompletableFuture.completedFuture(
                LlmResponse.success("Generated text", 10, 100)
            )
        }

        service.registerBackend(mockBackend)
        val config = LlmConfig("test-backend")
        val future = service.generateCompletion("Test prompt", config)
        val response = future.get()

        assertTrue(response.success)
        assertEquals("Generated text", response.text)
    }

    @Test
    fun testAutoPrefersLocalWhenAvailable() {
        service.registerBackend(mockBackend("local", text = "from local"))
        service.registerBackend(mockBackend("gemini", text = "from gemini"))

        val config = LlmConfig(AiBackend.AUTO)
        val response = service.generateCompletion("prompt", config).get()

        assertTrue(response.success)
        assertEquals("from local", response.text)
        assertEquals("local", config.backendId) // sentinel normalized to the resolved id
    }

    @Test
    fun givenNothingStoredAndNoDefaultInstalled_whenAutoRouting_thenTheInstalledOneIsUsed() {
        // Nothing has been chosen and the default is not installed, so there is a choice to make.
        // Resolving by what is *installed* is what the settings screen and the chat label do, so
        // AUTO must agree — otherwise the AUTO consumers resolve to an unregistered id.
        service.registerBackend(mockBackend("gemini", text = "from gemini"))

        val config = LlmConfig(AiBackend.AUTO)
        val response = service.generateCompletion("prompt", config).get()

        assertTrue(response.success)
        assertEquals("from gemini", response.text)
        assertEquals("gemini", config.backendId)
    }

    @Test
    fun givenSelectedBackendUnavailable_whenAutoRouting_thenNoOtherBackendIsCalled() {
        // ADFA-5132: an unconfigured local model used to hand the prompt to whichever remote
        // provider had a key, sending the user's code to a third party they never chose.
        val local = mockBackend("local", available = false)
        val gemini = mockBackend("gemini", text = "from gemini")
        service.registerBackend(local)
        service.registerBackend(gemini)

        val config = LlmConfig(AiBackend.AUTO)
        val response = service.generateCompletion("prompt", config).get()

        assertFalse(response.success)
        // The verdict alone would pass even if the hop still happened somewhere downstream.
        verify(exactly = 0) { gemini.generate(any(), any()) }
    }

    @Test
    fun givenAnExplicitlyRequestedBackendThatIsUnavailable_whenGenerating_thenNoOtherBackendIsCalled() {
        // The AUTO path is not the only way in: a caller naming an unready backend outright must
        // fail on it too, or the hard gate would depend on which entry point the caller picked.
        val local = mockBackend("local", available = false)
        val gemini = mockBackend("gemini", text = "from gemini")
        service.registerBackend(local)
        service.registerBackend(gemini)

        val response = service.generateCompletion("prompt", LlmConfig("local")).get()

        assertFalse(response.success)
        verify(exactly = 0) { gemini.generate(any(), any()) }
        verify(exactly = 0) { local.generate(any(), any()) }
    }

    @Test
    fun givenAnExplicitlyRequestedBackendThatIsUnavailable_whenStreamingWithTools_thenItFailsAtOnce() {
        // The chat's own path: it stamps the resolved id into the config, so the request arrives
        // explicit rather than as AUTO.
        val local = object : RecordingBackend("local") {
            override fun isAvailable(): Boolean = false
        }
        val gemini = RecordingBackend("gemini")
        service.registerBackend(local)
        service.registerBackend(gemini)

        val errors = mutableListOf<String>()
        service.generateStreamingWithTools(
            "prompt",
            emptyList(),
            LlmConfig("local"),
            emptyList(),
            object : ToolStreamCallback {
                override fun onToken(token: String) = Unit
                override fun onToolCall(toolCall: ToolCallRequest) = Unit
                override fun onComplete(response: LlmResponse) = Unit
                override fun onError(error: String) {
                    errors.add(error)
                }
            },
        )

        assertEquals(1, errors.size)
        assertTrue("the error must name the backend the user selected", errors[0].contains("local"))
        assertTrue("no substitute may be streamed to", gemini.streamedPrompts.isEmpty())
    }

    @Test
    fun givenNothingStoredAndNoDefaultInstalled_whenAutoRouting_thenTheSelectorsOrderDecides() {
        // The tie-break is "first offered", so this has to be offered the selector's order — by
        // display name — and not the registry map's hash order, or AUTO routes to one backend
        // while the settings screen and the chat label name another.
        service.registerBackend(mockBackend("zulu", displayName = "Alpha", text = "from zulu"))
        service.registerBackend(mockBackend("alpha", displayName = "Zulu", text = "from alpha"))

        val config = LlmConfig(AiBackend.AUTO)
        val response = service.generateCompletion("prompt", config).get()

        assertEquals("from zulu", response.text)
        assertEquals("zulu", config.backendId)
    }

    @Test
    fun testAutoFailsWhenNoBackendAvailable() {
        val config = LlmConfig(AiBackend.AUTO)
        val response = service.generateCompletion("prompt", config).get()

        assertFalse(response.success)
    }

    @Test
    fun givenToolCapableBackend_whenStreamingWithTools_thenTheRequestReachesItIntact() {
        // Routing used to depend on `backend is GeminiBackend`. ai-core must hand the whole
        // request — history and tools included — to the backend and decide nothing itself.
        val backend = ToolRecordingBackend()
        service.registerBackend(backend)

        val history = List(2) { ChatMessage(ChatMessage.Role.USER, "turn $it") }
        val tools = listOf(ToolDefinition("read_file", "reads a file", emptyMap()))
        service.generateStreamingWithTools(
            "prompt", history, LlmConfig(backend.getId()), tools, toolCallbackSink()
        )

        assertEquals(listOf(2), backend.historySizes)
        assertEquals(listOf(1), backend.toolCounts)
        assertTrue("ai-core must not pre-empt the backend's own path", backend.streamedPrompts.isEmpty())
    }

    @Test
    fun givenAHistoryCapableBackend_whenStreamingWithTools_thenTheConversationIsKept() {
        // It declares no tool calling, so it must still get the turns rather than the last one alone.
        val backend = HistoryRecordingBackend()
        service.registerBackend(backend)

        val history = List(3) { ChatMessage(ChatMessage.Role.USER, "turn $it") }
        service.generateStreamingWithTools(
            "prompt", history, LlmConfig(backend.getId()), emptyList(), toolCallbackSink()
        )

        assertEquals(listOf(3), backend.historySizes)
        assertEquals(listOf("prompt"), backend.prompts)
        assertTrue("a history-capable backend must not be dropped to single-turn", backend.streamedPrompts.isEmpty())
    }

    @Test
    fun givenABackendWithNeitherCapability_whenStreamingWithTools_thenItStreamsTheLastTurnAlone() {
        val backend = RecordingBackend("plain")
        service.registerBackend(backend)

        service.generateStreamingWithTools(
            "prompt",
            listOf(ChatMessage(ChatMessage.Role.USER, "earlier")),
            LlmConfig(backend.getId()),
            emptyList(),
            toolCallbackSink(),
        )

        assertEquals(listOf("prompt"), backend.streamedPrompts)
    }

    @Test
    fun givenACancellableBackend_whenCancelling_thenItIsCancelled() {
        val backend = CancellableRecordingBackend("cancellable")
        service.registerBackend(backend)

        service.cancelGeneration()

        assertEquals(1, backend.cancelCount)
    }

    @Test
    fun givenOneBackendThatThrowsOnCancel_whenCancelling_thenTheOthersStillCancel() {
        // A wedged backend must not leave every other backend generating.
        service.registerBackend(object : RecordingBackend("throws"), CancellableBackend {
            override fun cancelStreaming(): Unit = throw IllegalStateException("wedged")
        })
        val healthy = CancellableRecordingBackend("healthy")
        service.registerBackend(healthy)

        service.cancelGeneration()

        assertEquals(1, healthy.cancelCount)
    }

    /** A backend implementing only what [LlmBackend] declares as abstract. */
    private open class RecordingBackend(private val backendId: String) : LlmBackend {

        val streamedPrompts = mutableListOf<String>()

        override fun getId(): String = backendId
        override fun getName(): String = backendId
        override fun isAvailable(): Boolean = true

        override fun generate(prompt: String, config: LlmConfig): CompletableFuture<LlmResponse> =
            CompletableFuture.completedFuture(LlmResponse.success("", 0, 0))

        override fun generateStreaming(prompt: String, config: LlmConfig, callback: StreamCallback) {
            streamedPrompts.add(prompt)
        }

        override fun generateWithHistory(
            history: List<ChatMessage>,
            prompt: String,
            config: LlmConfig
        ): CompletableFuture<LlmResponse> =
            CompletableFuture.completedFuture(LlmResponse.success("", 0, 0))
    }

    /** Mirrors a backend with native tool calling, which no shipped backend has yet. */
    private class ToolRecordingBackend : RecordingBackend("tools"), ToolCallingBackend {

        val historySizes = mutableListOf<Int>()
        val toolCounts = mutableListOf<Int>()

        override fun generateStreamingWithTools(
            prompt: String,
            history: List<ChatMessage>,
            config: LlmConfig,
            tools: List<ToolDefinition>,
            callback: ToolStreamCallback
        ) {
            historySizes.add(history.size)
            toolCounts.add(tools.size)
        }
    }

    /** Mirrors the shipped backends: multi-turn, but with no tool calls of its own. */
    private class HistoryRecordingBackend : RecordingBackend("history"), HistoryCapableBackend {

        val historySizes = mutableListOf<Int>()
        val prompts = mutableListOf<String>()

        override fun generateStreamingWithHistory(
            history: List<ChatMessage>,
            prompt: String,
            config: LlmConfig,
            callback: StreamCallback
        ) {
            historySizes.add(history.size)
            prompts.add(prompt)
        }
    }

    private class CancellableRecordingBackend(backendId: String) :
        RecordingBackend(backendId), CancellableBackend {

        var cancelCount = 0

        override fun cancelStreaming() {
            cancelCount++
        }
    }

    /** A [ToolStreamCallback] that records nothing; these tests assert on the backend instead. */
    private fun toolCallbackSink() = object : ToolStreamCallback {
        override fun onToken(token: String) = Unit
        override fun onToolCall(toolCall: ToolCallRequest) = Unit
        override fun onComplete(response: LlmResponse) = Unit
        override fun onError(error: String) = Unit
    }
}
