package com.itsaky.androidide.plugins.aiagentclaude.backend

import android.content.SharedPreferences
import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentclaude.R
import com.itsaky.androidide.plugins.aiagentclaude.errors.ClaudeErrorFormatter
import com.itsaky.androidide.plugins.aiagentclaude.errors.ClaudeFailure
import com.itsaky.androidide.plugins.aiagentclaude.errors.ClaudeFailureMessages
import com.itsaky.androidide.plugins.aiagentclaude.errors.ClaudeHttpException
import com.itsaky.androidide.plugins.aiagentclaude.errors.ClaudeStreamStalledException
import com.itsaky.androidide.plugins.aiagentclaude.errors.CredentialFailureLog
import com.itsaky.androidide.plugins.aiagentclaude.errors.isCredentialProblem
import com.itsaky.androidide.plugins.aiagentclaude.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aiagentclaude.preferences.ClaudePreferences
import com.itsaky.androidide.plugins.aiagentclaude.prompt.ClaudeSystemPrompt
import com.itsaky.androidide.plugins.aiagentclaude.security.ApiKeyCache
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Tool-protocol tracing, under the tag suffix `ai-core` uses for the other half of the same run:
 * `adb logcat -s AiCore.AgentTrace:V AiAgentClaude.AgentTrace:V` reads a run end to end.
 *
 * Through [Log] rather than `context.logger`, which the host funnels into its own class's tag with
 * only a `[pluginId]` prefix — unfilterable, and so absent from a captured log of an agent run.
 */
private const val TAG = "$LOG_PREFIX.AgentTrace"

/**
 * Claude backend: Anthropic's Messages API, `POST /v1/messages`.
 *
 * What this class owns is the *conversation*: which model, which turns, what to do when the API
 * is overloaded or a turn ends with nothing to show. Sockets are [ClaudeHttpClient]'s, the request
 * shape is [ClaudeRequestBuilder]'s, how a stream becomes a turn is [TurnAssembler]'s, the
 * decrypted key is [ApiKeyCache]'s, and the wording of a failure is [ClaudeFailureMessages]'.
 *
 * Not an [EmbeddingBackend]: Anthropic offers no embeddings endpoint, and a backend that claimed
 * one would leave semantic search failing on every index build.
 */
class ClaudeBackend(
    private val context: PluginContext
) : HistoryCapableBackend, CancellableBackend, ConfigurableBackend, ToolCallingBackend {

    private val scope = CoroutineScope(Dispatchers.IO)

    private val http = ClaudeHttpClient()

    private val keyCache =
        ApiKeyCache(::claudePrefs, ClaudePreferences.KEY_API_KEY, context.logger, scope)

    private val failureMessages = ClaudeFailureMessages(context)

    /**
     * Where a refused credential is left for the settings pane to report, so a key problem is not
     * only readable in a transcript the user has already navigated away from.
     */
    private val credentialFailures = CredentialFailureLog(::claudePrefs)

    /**
     * One streamed turn in flight: its coroutine, and its socket once connected.
     *
     * The socket is held so Stop can close it at once. Cancelling the coroutine alone does not: a
     * reader blocked on the stream only sees the cancellation when the next line arrives, and
     * until then the model keeps generating, and billing, for a turn nobody will read.
     */
    private class ActiveStream {
        @Volatile var job: Job? = null
        @Volatile var connection: HttpURLConnection? = null
    }

    /**
     * Every streamed turn in flight. A set rather than one field, so a request from another
     * plugin cannot overwrite the agent's turn, which Stop would then fail to cancel. Requests
     * that are not streamed are not here: Stop is for the stream, and those are cancelled through
     * their own futures.
     */
    private val activeStreams = ConcurrentHashMap.newKeySet<ActiveStream>()

    companion object {
        /** Backend id, as persisted by AI Core when the user selects this backend. */
        const val BACKEND_ID = "claude"

        /** Default model. Editable on this backend's settings pane. */
        const val DEFAULT_MODEL = "claude-opus-5-5"

        /** The Claude API. Fixed: there is no compatible third-party server to point this at. */
        const val BASE_URL = "https://api.anthropic.com/v1"

        /** Messages endpoint, appended to [BASE_URL]. */
        private const val MESSAGES_PATH = "/messages"
    }

    /** This plugin's own settings, written by its settings pane and read here at request time. */
    private fun claudePrefs(): SharedPreferences? = try {
        ClaudePreferences.of(context)
    } catch (e: Exception) {
        context.logger.error("ClaudeBackend: Error getting preferences", e)
        null
    }

    /** The model to request, or the default when nothing is stored. */
    private fun getModelName(): String =
        claudePrefs()?.getString(ClaudePreferences.KEY_MODEL, DEFAULT_MODEL)
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_MODEL

    /**
     * What the last live catalog said [model] accepts, or null when it has not described it.
     * Written by the settings pane, which is the only place the catalog is fetched.
     */
    private fun knownCapabilities(model: String): ModelCapabilities? {
        val stored = claudePrefs()?.getString(ClaudePreferences.KEY_MODEL_CAPABILITIES, null)
        return ClaudeModelTraits.lookup(model, ClaudeModelCatalog.decodeCapabilities(stored))
    }

    /**
     * Decrypt the stored key off-thread now, so a main-thread [isAvailable] can't report "no key"
     * for a key that is there. Called once, on activation.
     */
    fun warmKeyCache() = keyCache.warm()

    override fun getId(): String = BACKEND_ID

    /** Falls back to a literal: an empty name would be an unlabelled row in the selector. */
    override fun getName(): String = configLabel(R.string.claude_backend_name, fallback = "Claude")

    /**
     * Resolves a label against this plugin's own resources, degrading rather than throwing —
     * [getName] is called across the plugin boundary.
     *
     * @param fallback returned when the lookup fails
     */
    private fun configLabel(resId: Int, fallback: String = ""): String = try {
        context.androidContext.getString(resId)
    } catch (e: Exception) {
        context.logger.error("ClaudeBackend: could not resolve label $resId", e)
        fallback
    }

    /**
     * Written for a large cloud model; see [ClaudeSystemPrompt] for why the wording belongs here
     * rather than with the caller.
     */
    override fun getSystemPrompt(request: SystemPromptRequest): String =
        ClaudeSystemPrompt.build(request)

    /** Null: this backend sends no `temperature`, which current Claude models reject outright. */
    override fun getDefaultTemperature(): Float? = null

    /**
     * This backend draws its own settings, so the consumer needs no knowledge of API keys or
     * model catalogs.
     */
    override fun getSettingsFragmentClassName(): String =
        "com.itsaky.androidide.plugins.aiagentclaude.settings.ClaudeSettingsFragment"

    /** Available once a key is stored: the Claude API has no anonymous access. */
    override fun isAvailable(): Boolean {
        val hasKey = readApiKeyOrBlank().isNotBlank()
        context.logger.debug("ClaudeBackend.isAvailable() - API key configured: $hasKey")
        return hasKey
    }

    override fun generate(prompt: String, config: LlmConfig): CompletableFuture<LlmResponse> =
        generateBlocking(emptyList(), prompt, config)

    override fun generateWithHistory(
        history: List<ChatMessage>,
        prompt: String,
        config: LlmConfig
    ): CompletableFuture<LlmResponse> {
        context.logger.info("ClaudeBackend.generateWithHistory() called with ${history.size} messages")
        return generateBlocking(history, prompt, config)
    }

    /**
     * One request that is not streamed, completing with the whole reply.
     *
     * These are the small jobs — chat titles, inline suggestions — so [ClaudeRequestBuilder] keeps
     * the caller's budget and asks for low effort, rather than an agent turn's.
     *
     * @param history the conversation so far, oldest first
     * @param prompt the current user turn
     * @param config its system prompt becomes the top-level `system`
     */
    private fun generateBlocking(
        history: List<ChatMessage>,
        prompt: String,
        config: LlmConfig,
    ): CompletableFuture<LlmResponse> {
        val future = CompletableFuture<LlmResponse>()

        val job = scope.launch {
            val keyStamp = storedKeyStamp()
            try {
                val startTime = System.currentTimeMillis()
                val conversation =
                    ClaudeRequestBuilder.conversation(history, prompt, config.systemPrompt)
                val reply = requestReply(conversation, config)

                when {
                    reply.stopReason == "refusal" ->
                        future.complete(LlmResponse.failure(failureMessages.of(ClaudeFailure.Refused)))

                    reply.text.isBlank() ->
                        future.complete(LlmResponse.failure(failureMessages.of(reply.emptyFailure())))

                    else -> {
                        val tokenCount = reply.text.split("\\s+".toRegex()).size  // Approximate
                        context.logger.info("ClaudeBackend: Generated ${reply.text.length} chars, ~$tokenCount tokens")
                        future.complete(
                            LlmResponse.success(reply.text, tokenCount, System.currentTimeMillis() - startTime)
                        )
                    }
                }
            } catch (e: CancellationException) {
                future.cancel(true)
                throw e
            } catch (e: Exception) {
                context.logger.error("ClaudeBackend: Error generating response", e)
                future.complete(LlmResponse.failure(formatErrorMessage(e, keyStamp)))
            }
        }
        future.cancelJobOnCancel(job)

        return future
    }

    override fun generateStreaming(
        prompt: String,
        config: LlmConfig,
        callback: StreamCallback
    ) {
        streamTurn(emptyList(), prompt, config, emptyList(), callback.asToolCallback())
    }

    /**
     * Streams a reply for a multi-turn conversation, sending [history] as real `messages[]` turns.
     *
     * @param history the conversation so far, oldest first
     * @param prompt the current user turn
     * @param config its system prompt becomes the top-level `system`
     * @param callback receives tokens, completion, and errors
     */
    override fun generateStreamingWithHistory(
        history: List<ChatMessage>,
        prompt: String,
        config: LlmConfig,
        callback: StreamCallback
    ) {
        streamTurn(history, prompt, config, emptyList(), callback.asToolCallback())
    }

    /**
     * Streams a turn with [tools] declared to the API, reporting each `tool_use` block through
     * [ToolStreamCallback.onToolCall].
     *
     * This is the path the agent takes. Declaring the tools is what stops the model writing a call
     * as prose the caller has to parse back: the input arrives already structured, so a file whose
     * contents contain quotes or newlines can no longer break the call carrying it (ADFA-5410).
     *
     * @param prompt the current user turn
     * @param history the conversation so far, oldest first
     * @param config its system prompt becomes the top-level `system`
     * @param tools the tools to declare; an empty list streams plain text
     * @param callback receives tokens, tool calls, completion, and errors
     */
    override fun generateStreamingWithTools(
        prompt: String,
        history: List<ChatMessage>,
        config: LlmConfig,
        tools: List<ToolDefinition>,
        callback: ToolStreamCallback
    ) {
        streamTurn(history, prompt, config, tools, callback)
    }

    /**
     * Adapts a plain stream callback to the tool-aware one [streamTurn] takes.
     *
     * @return a [ToolStreamCallback] that forwards every event and reports no tool calls
     */
    private fun StreamCallback.asToolCallback(): ToolStreamCallback = object : ToolStreamCallback {
        override fun onToken(token: String) = this@asToolCallback.onToken(token)
        override fun onToolCall(request: ToolCallRequest) = Unit
        override fun onComplete(response: LlmResponse) = this@asToolCallback.onComplete(response)
        override fun onError(error: String) = this@asToolCallback.onError(error)
    }

    /**
     * Streams one Messages request.
     *
     * @param history the conversation so far, oldest first
     * @param prompt the current user turn
     * @param config supplies the system prompt and stop sequences
     * @param tools the tools to declare, or empty to stream plain text
     * @param callback receives tokens, tool calls, completion, and errors
     */
    private fun streamTurn(
        history: List<ChatMessage>,
        prompt: String,
        config: LlmConfig,
        tools: List<ToolDefinition>,
        callback: ToolStreamCallback
    ) {
        val stream = ActiveStream()
        // Lazy, so the job is on record before it can run: a Stop that lands in between would
        // otherwise find a stream with nothing to cancel.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val keyStamp = storedKeyStamp()
            try {
                val startTime = System.currentTimeMillis()
                val model = getModelName()
                val conversation =
                    ClaudeRequestBuilder.conversation(history, prompt, config.systemPrompt)
                val body = ClaudeRequestBuilder.body(
                    conversation, model, stream = true, config, tools, knownCapabilities(model)
                )
                Log.i(
                    TAG,
                    "REQUEST | model=$model turns=${conversation.messages.length()} " +
                        "tools=${tools.size} " + tools.joinToString(",") { it.name }
                )

                var attempt: TurnAssembler? = null
                val turn = TransientRetry.run(
                    delivered = { (attempt?.chunks ?: 0) > 0 },
                    onRetry = { e, retry, wait ->
                        context.logger.warn(
                            "ClaudeBackend: ${(e as? ClaudeHttpException)?.statusCode} from the API; " +
                                "retry $retry of ${TransientRetry.MAX_RETRIES} in ${wait}ms"
                        )
                    },
                ) {
                    // A fresh assembler per attempt, so a retried turn cannot report a call twice.
                    TurnAssembler { chunk -> callback.onToken(chunk) }.also { assembler ->
                        attempt = assembler
                        streamOnce(body, ClaudeRequestBuilder.betas(model), stream, assembler)
                    }
                }
                val result = turn.finish()
                Log.i(
                    TAG,
                    "STREAM | model=${turn.servedBy ?: model} chunks=${turn.chunks} " +
                        "dropped=${turn.droppedCalls} discardedAtFallback=${turn.discardedAtFallback} " +
                        "stop=${turn.stopReason}"
                )

                when (result) {
                    TurnAssembler.Result.Refused -> {
                        context.logger.warn("ClaudeBackend: the model declined this request")
                        callback.onError(failureMessages.of(ClaudeFailure.Refused))
                    }

                    is TurnAssembler.Result.Empty -> {
                        context.logger.warn(
                            "ClaudeBackend: stream produced no reply text " +
                                "(skipped=${turn.skippedLines}, thinkingBlocks=${turn.thinkingBlocks}, " +
                                "droppedCalls=${turn.droppedCalls}, stopReason=${turn.stopReason})"
                        )
                        callback.onError(failureMessages.of(result.failure))
                    }

                    is TurnAssembler.Result.Reply -> {
                        // Reported after the stream, so a call is never acted on before it is complete.
                        for (call in result.calls) {
                            Log.i(
                                TAG,
                                "FUNCTION_CALL | tool=${call.name} " +
                                    "args=${call.args.orEmpty().keys.joinToString(",")}"
                            )
                            callback.onToolCall(call)
                        }
                        val tokenCount = result.text.split("\\s+".toRegex()).size
                        context.logger.info(
                            "ClaudeBackend: Streamed ${result.text.length} chars in ${turn.chunks} chunks, ~$tokenCount tokens"
                        )
                        callback.onComplete(
                            LlmResponse.success(result.text, tokenCount, System.currentTimeMillis() - startTime)
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                Log.e(TAG, "STREAM | failed: ${e.message}", e)
                context.logger.error("ClaudeBackend: Error in streaming", e)
                callback.onError(formatErrorMessage(e, keyStamp))
            }
        }
        stream.job = job
        activeStreams += stream
        // On completion rather than in a finally: a job in an already-closed scope never runs.
        job.invokeOnCompletion { activeStreams -= stream }
        job.start()
    }

    /**
     * POST [body] and feed each line of the event stream to [turn].
     *
     * Tokens already delivered before a mid-stream failure stay delivered.
     *
     * @param betas the `anthropic-beta` values [body] needs
     * @param stream where the live socket is published, so Stop can close it
     * @throws ClaudeStreamStalledException when the stream goes silent past the read timeout
     */
    private suspend fun streamOnce(
        body: JSONObject,
        betas: List<String>,
        stream: ActiveStream,
        turn: TurnAssembler,
    ) {
        // Hoisted: the reader below is an ordinary lambda, with no suspend context of its own.
        val requestContext = coroutineContext
        try {
            postMessages(
                body = body,
                betas = betas,
                sse = true,
                onConnected = { conn -> stream.connection = conn },
            ) { reader ->
                try {
                    for (line in reader.lineSequence()) {
                        requestContext.ensureActive()
                        val event = ClaudeStreamEvent.parse(line)
                        if (event is ClaudeStreamEvent.Malformed) {
                            context.logger.warn("ClaudeBackend: skipping stream line: ${event.detail}")
                        }
                        if (event is ClaudeStreamEvent.FallbackSwitch) {
                            Log.i(TAG, "FALLBACK | continued on ${event.toModel}")
                        }
                        if (!turn.accept(event)) break
                    }
                } catch (e: SocketTimeoutException) {
                    // The status line said 2xx, so this is the API going quiet, not the network.
                    throw ClaudeStreamStalledException(e)
                }
            }
        } finally {
            stream.connection = null
        }
    }

    /** A whole reply read from a request that was not streamed. */
    private class BlockingReply(val text: String, val stopReason: String?, val thinkingBlocks: Int) {
        /** Which empty-reply case this is; the stream's rules, applied to a whole response. */
        fun emptyFailure(): ClaudeFailure = when {
            stopReason == "max_tokens" -> ClaudeFailure.TruncatedBeforeReply
            thinkingBlocks > 0 -> ClaudeFailure.ReasoningOnly
            else -> ClaudeFailure.EmptyReply(0)
        }
    }

    /**
     * POST [conversation] without streaming and read back the reply.
     *
     * @param config supplies the token cap and stop sequences
     */
    private suspend fun requestReply(
        conversation: ClaudeRequestBuilder.Conversation,
        config: LlmConfig,
    ): BlockingReply {
        val model = getModelName()
        val body = ClaudeRequestBuilder.body(
            conversation, model, stream = false, config, known = knownCapabilities(model)
        )
        return TransientRetry.run {
            postMessages(body, ClaudeRequestBuilder.betas(model)) { reader ->
                val text = try {
                    reader.readText()
                } catch (e: SocketTimeoutException) {
                    throw ClaudeStreamStalledException(e)
                }
                parseBlockingReply(JSONObject(text))
            }
        }
    }

    /**
     * Reads a whole Messages response.
     *
     * Content is read by block `type`, never by position: a response can open with thinking or
     * fallback blocks before its text.
     */
    private fun parseBlockingReply(response: JSONObject): BlockingReply {
        val content = response.optJSONArray("content")
        var thinkingBlocks = 0
        val text = buildString {
            for (i in 0 until (content?.length() ?: 0)) {
                val block = content?.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "text" -> append(block.optString("text"))
                    "thinking", "redacted_thinking" -> thinkingBlocks++
                }
            }
        }
        return BlockingReply(
            text = text,
            stopReason = response.optString("stop_reason").takeIf { it.isNotBlank() && it != "null" },
            thinkingBlocks = thinkingBlocks,
        )
    }

    /**
     * POST [body] to the Messages endpoint with the stored credential.
     *
     * Every generation goes through here, streaming or not, which is why the recorded refusal is
     * cleared here — on the status line, since a 2xx is the API accepting the key whether or not
     * the body that follows is read to the end, or cancelled, or dropped mid-stream.
     *
     * @param betas the `anthropic-beta` values [body] needs
     * @param sse true to ask for the server-sent-events stream
     * @param onConnected receives the live connection, so the caller can disconnect it on cancel
     * @return whatever [readResponse] produced
     */
    private fun <T> postMessages(
        body: JSONObject,
        betas: List<String>,
        sse: Boolean = false,
        onConnected: (HttpURLConnection) -> Unit = {},
        readResponse: (BufferedReader) -> T,
    ): T = http.post(
        url = BASE_URL + MESSAGES_PATH,
        apiKey = readApiKeyOrBlank(),
        workspaceId = storedWorkspaceId(),
        body = body,
        betas = betas,
        sse = sse,
        readTimeoutMs = if (sse) ClaudeHttpClient.STREAM_READ_TIMEOUT_MS else ClaudeHttpClient.BLOCKING_READ_TIMEOUT_MS,
        onConnected = onConnected,
        onAccepted = { credentialFailures.clear() },
        readResponse = readResponse,
    )

    /**
     * List the models available with the stored key.
     *
     * Completes exceptionally on a network/API failure — an HTTP one as a [ClaudeHttpException], so
     * the caller can tell a refused key from an unreachable API.
     */
    internal fun listModels(): CompletableFuture<List<ClaudeModelCatalog.Entry>> =
        listModels(readApiKeyOrBlank(), storedWorkspaceId())

    /**
     * List the models a caller-supplied key can use, with what each accepts.
     *
     * Lets the settings pane check a just-typed key *before* it is persisted; the no-arg
     * [listModels] reads what is on disk. Nothing here touches the stored key or its cache.
     *
     * @param apiKey the candidate key; never logged
     * @param workspaceId the candidate workspace for a key that belongs to none, or null
     */
    internal fun listModels(
        apiKey: String,
        workspaceId: String?,
    ): CompletableFuture<List<ClaudeModelCatalog.Entry>> {
        val future = CompletableFuture<List<ClaudeModelCatalog.Entry>>()
        // close() cancels the scope, making launch a silent no-op; fail loudly instead.
        if (!scope.isActive) {
            future.completeExceptionally(IllegalStateException("Claude backend is closed"))
            return future
        }

        val job = scope.launch {
            try {
                val body = http.get(BASE_URL + ClaudeModelCatalog.PATH, apiKey.trim(), workspaceId)
                val models = ClaudeModelCatalog.entries(body)
                context.logger.info("ClaudeBackend: the API offers ${models.size} models")
                future.complete(models)
            } catch (e: CancellationException) {
                future.cancel(true)
                throw e
            } catch (e: Exception) {
                context.logger.warn("ClaudeBackend: model listing failed: ${e.message}")
                future.completeExceptionally(e)
            }
        }
        future.cancelJobOnCancel(job)

        return future
    }

    /**
     * When the stored key was saved, or 0 when none is stored.
     *
     * Read into a local at the top of each request and carried to that request's error handler: a
     * field on the backend is overwritten by any other key read before the refusal lands.
     */
    private fun storedKeyStamp(): Long =
        claudePrefs()?.getLong(ClaudePreferences.KEY_API_KEY_TIMESTAMP, 0L) ?: 0L

    /** The saved workspace id, or null when the key needs none. Checked again where it is sent. */
    private fun storedWorkspaceId(): String? =
        claudePrefs()?.getString(ClaudePreferences.KEY_WORKSPACE_ID, null)

    /** The saved key, or blank when none is stored or it cannot be decrypted. */
    private fun readApiKeyOrBlank(): String = keyCache.read().orEmpty()

    /**
     * Stop every streamed turn in flight (user pressed Stop): cancel its coroutine and close its
     * socket, so the API stops generating now rather than at the next line it sends.
     *
     * Safe on the main thread, which is where AI Core calls it: the socket is closed on a thread
     * of its own, since closing a TLS connection writes to the network.
     */
    override fun cancelStreaming() {
        for (stream in activeStreams) {
            stream.job?.cancel()
            stream.connection?.let(::disconnectInBackground)
        }
    }

    private fun disconnectInBackground(connection: HttpURLConnection) {
        Thread({ runCatching { connection.disconnect() } }, "$LOG_PREFIX.disconnect").start()
    }

    /** Release all resources: stop every stream, cancel the backend scope, drop the key cache. */
    fun close() {
        cancelStreaming()
        scope.cancel()
        keyCache.clear()
    }

    /**
     * Turn a failure into one user-facing sentence.
     *
     * [ClaudeErrorFormatter] decides *what* went wrong; the wording comes from `strings.xml`. The
     * raw HTTP error body stays on the logged exception and must never reach the transcript.
     *
     * @param keyStamp when the key this request read was saved, so a refusal landing after a later
     *   save or clear is not reported against a credential that was never tried
     */
    private fun formatErrorMessage(e: Exception, keyStamp: Long): String {
        val failure = ClaudeErrorFormatter.classify(
            error = e,
            modelName = getModelName(),
            hasApiKey = readApiKeyOrBlank().isNotBlank(),
        )
        val message = failureMessages.of(failure)
        // Only a credential failure is recorded: any other reason says nothing about the key, and
        // filing it as one would send the user off to replace a key that works.
        if (failure.isCredentialProblem) credentialFailures.record(failure, keyStamp)
        return message
    }
}

/**
 * Cancel [job] when this future is cancelled by its caller.
 *
 * [CompletableFuture.cancel] only flips the future's own state, so without this a caller that gives
 * up leaves the HTTP fetch running to completion for a result nobody will read.
 *
 * @param job the coroutine producing this future's value
 */
private fun <T> CompletableFuture<T>.cancelJobOnCancel(job: Job) {
    whenComplete { _, _ -> if (isCancelled) job.cancel() }
}
