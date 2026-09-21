package com.itsaky.androidide.plugins.aicore.services

import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.aicore.backends.AiBackend
import com.itsaky.androidide.plugins.aicore.backends.BackendRegistry
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of LlmInferenceService.
 *
 * Backend-agnostic: it knows no concrete backend type. Backends are contributed by separate plugins
 * (ai-agent-local, ai-agent-gemini, …) that call [registerBackend] on activation, and each optional
 * behaviour — [ToolCallingBackend], [HistoryCapableBackend], [CancellableBackend] — is declared by
 * the interface a backend implements, which this asks for by type before calling.
 *
 * @param logger the owning plugin's log, or null in unit tests
 */
class LlmInferenceServiceImpl(private val logger: PluginLogger? = null) : LlmInferenceService {

    private val backends = ConcurrentHashMap<String, LlmBackend>()
    @Volatile private var currentGeneration: CompletableFuture<LlmResponse>? = null

    override fun registerBackend(backend: LlmBackend) {
        backends[backend.getId()] = backend
    }

    override fun unregisterBackend(backendId: String) {
        backends.remove(backendId)
    }

    /**
     * Every registered backend, in the order the settings selector lists them.
     *
     * Sorted here rather than left in this map's hash order, so a caller that lists them or takes
     * the first one gets the same answer on every launch instead of an arbitrary backend.
     *
     * @return the registered backends, sorted by display name, tolerating a backend that throws
     *   from its own accessor by sorting it under its id
     */
    override fun getAvailableBackends(): List<LlmBackend> =
        backends.entries
            .sortedBy { (id, backend) -> runCatching { backend.name }.getOrNull() ?: id }
            .map { (_, backend) -> backend }

    override fun getBackend(backendId: String): LlmBackend? {
        return backends[backendId]
    }

    override fun isBackendAvailable(backendId: String): Boolean {
        val backend = backends[backendId]
        return backend != null && backend.isAvailable()
    }

    override fun generateCompletion(prompt: String, config: LlmConfig): CompletableFuture<LlmResponse> {
        val effectiveId = resolveAndStamp(config)
        val backend = backends[effectiveId]
            ?: return CompletableFuture.completedFuture(
                LlmResponse.failure("Backend '$effectiveId' not found")
            )

        if (!backend.isAvailable()) {
            return CompletableFuture.completedFuture(
                LlmResponse.failure("Backend '$effectiveId' is not available")
            )
        }

        val future = backend.generate(prompt, config)
        currentGeneration = future
        return future
    }

    override fun generateStreaming(prompt: String, config: LlmConfig, callback: StreamCallback) {
        val effectiveId = resolveAndStamp(config)
        val backend = backends[effectiveId]
        if (backend == null) {
            callback.onError("Backend '$effectiveId' not found")
            return
        }

        if (!backend.isAvailable()) {
            callback.onError("Backend '$effectiveId' is not available")
            return
        }

        backend.generateStreaming(prompt, config, callback)
    }

    override fun generateWithHistory(
        history: List<ChatMessage>,
        prompt: String,
        config: LlmConfig
    ): CompletableFuture<LlmResponse> {
        val effectiveId = resolveAndStamp(config)
        val backend = backends[effectiveId]
            ?: return CompletableFuture.completedFuture(
                LlmResponse.failure("Backend '$effectiveId' not found")
            )

        if (!backend.isAvailable()) {
            return CompletableFuture.completedFuture(
                LlmResponse.failure("Backend '$effectiveId' is not available")
            )
        }

        val future = backend.generateWithHistory(history, prompt, config)
        currentGeneration = future
        return future
    }

    override fun generateStreamingWithTools(
        prompt: String,
        history: List<ChatMessage>,
        config: LlmConfig,
        tools: List<ToolDefinition>,
        callback: ToolStreamCallback
    ) {
        val effectiveId = resolveAndStamp(config)
        val backend = backends[effectiveId]
        if (backend == null) {
            callback.onError("Backend '$effectiveId' not found")
            return
        }

        if (!backend.isAvailable()) {
            callback.onError("Backend '$effectiveId' is not available")
            return
        }

        // Route by what the backend declares it can do, degrading one capability at a time.
        when (backend) {
            is ToolCallingBackend ->
                backend.generateStreamingWithTools(prompt, history, config, tools, callback)

            is HistoryCapableBackend ->
                backend.generateStreamingWithHistory(history, prompt, config, callback.asStream())

            else ->
                backend.generateStreaming(prompt, config, callback.asStream())
        }
    }

    /**
     * Adapts a tool callback to the plain streaming one, for a backend that reports no tool calls.
     *
     * @return a [StreamCallback] forwarding every event to this callback
     */
    private fun ToolStreamCallback.asStream(): StreamCallback = object : StreamCallback {
        override fun onToken(token: String) = this@asStream.onToken(token)
        override fun onComplete(response: LlmResponse) = this@asStream.onComplete(response)
        override fun onError(error: String) = this@asStream.onError(error)
    }

    override fun getEmbeddings(text: String, backendId: String): CompletableFuture<FloatArray> {
        // Stub implementation - embeddings not needed for Phase 3
        return CompletableFuture.completedFuture(FloatArray(0))
    }

    /**
     * Resolves where a request routes and writes that id back into [config], so every entry point
     * hands the backend the id it actually ran on rather than the [AiBackend.AUTO] sentinel.
     *
     * @param config the request's config; its `backendId` is replaced by the resolved id
     * @return the backend id to route to
     */
    private fun resolveAndStamp(config: LlmConfig): String {
        val effectiveId = effectiveBackendId(config.backendId)
        config.backendId = effectiveId
        return effectiveId
    }

    /**
     * Resolves the backend id a request should run on. An explicit id is returned unchanged;
     * [AiBackend.AUTO] resolves to the selected backend the same way the settings screen and the
     * chat's status line resolve it, so all three name one backend.
     *
     * Availability is deliberately not consulted: stepping past an unready backend to another that
     * happens to answer would send the prompt to a provider the user did not choose, so an unready
     * selection has to fail here instead.
     *
     * @param requestedId the id from [LlmConfig.backendId]
     * @return the id to route to, unavailable or not, so the caller's "not found"/"not available"
     *   error names the backend the user actually selected
     */
    private fun effectiveBackendId(requestedId: String): String {
        if (requestedId != AiBackend.AUTO) return requestedId
        val storedId = getPreferredBackendId()
        // Falling back to the stored id keeps the failure naming the backend the user selected,
        // rather than the default one, when its plugin is no longer installed.
        return AiBackend.preferredId(storedId, installedIdsInSelectorOrder())
            ?: storedId
            ?: AiBackend.DEFAULT_ID
    }

    /**
     * Installed ids in the order the settings selector lists them, which is by display name.
     *
     * With nothing stored yet, [AiBackend.preferredId] falls back to the first id offered, so the
     * order decides the answer. Handing it this map's own keys would hand it a hash order, and AUTO
     * would route to a backend other than the one the selector and the chat's status line name.
     *
     * @return every registered id, sorted by display name, tolerating a backend that throws from
     *   its own accessor by sorting it under its id
     */
    private fun installedIdsInSelectorOrder(): List<String> =
        backends.entries
            .map { (id, backend) -> id to (runCatching { backend.name }.getOrNull() ?: id) }
            .sortedBy { (_, displayName) -> displayName }
            .map { (id, _) -> id }

    /**
     * The backend the user selected on the Agent settings screen.
     *
     * Published so a backend can find out whether it is the active one without reading this
     * plugin's preferences — see [LlmInferenceService.getPreferredBackendId].
     *
     * Read through the registry rather than from the preference file directly, so "nothing has been
     * chosen" means the same here as it does on the settings screen; resolution turns on that
     * distinction now, and reading the file twice let the two answer differently on a blank value.
     *
     * @return the selected backend id, or null when nothing has been chosen yet
     */
    override fun getPreferredBackendId(): String? = BackendRegistry.selectedId()

    override fun cancelGeneration() {
        currentGeneration?.cancel(true)
        currentGeneration = null

        // One backend refusing to cancel must not leave the others generating.
        backends.values.filterIsInstance<CancellableBackend>().forEach { backend ->
            try {
                backend.cancelStreaming()
            } catch (e: Throwable) {
                logger?.error("A backend failed to cancel its generation", e)
            }
        }
    }
}
