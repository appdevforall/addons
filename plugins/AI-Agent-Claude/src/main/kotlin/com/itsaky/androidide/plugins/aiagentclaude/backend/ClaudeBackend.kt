package com.itsaky.androidide.plugins.aiagentclaude.backend

import android.content.SharedPreferences
import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentclaude.R
import com.itsaky.androidide.plugins.aiagentclaude.errors.ClaudeErrorFormatter
import com.itsaky.androidide.plugins.aiagentclaude.errors.ClaudeFailure
import com.itsaky.androidide.plugins.aiagentclaude.errors.ClaudeFailureMessages
import com.itsaky.androidide.plugins.aiagentclaude.errors.ClaudeHttpException
import com.itsaky.androidide.plugins.aiagentclaude.errors.CredentialFailureLog
import com.itsaky.androidide.plugins.aiagentclaude.errors.isCredentialProblem
import com.itsaky.androidide.plugins.aiagentclaude.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aiagentclaude.preferences.ClaudePreferences
import com.itsaky.androidide.plugins.aiagentclaude.prompt.ClaudeSystemPrompt
import com.itsaky.androidide.plugins.aiagentclaude.security.ApiKeyCache
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 * shape is [ClaudeRequestBuilder]'s, the decrypted key is [ApiKeyCache]'s, and the wording of a
 * failure is [ClaudeFailureMessages]'.
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

    @Volatile
    private var currentJob: Job? = null

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
                    reply.outcome.stopReason == "refusal" ->
                        future.complete(LlmResponse.failure(failureMessages.of(ClaudeFailure.Refused)))

                    reply.text.isBlank() ->
                        future.complete(LlmResponse.failure(failureMessages.of(emptyReplyFailure(reply.outcome))))

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
        currentJob = job
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
     * @param config supplies the system prompt, token cap and stop sequences
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
        currentJob = scope.launch {
            val keyStamp = storedKeyStamp()
            try {
                val startTime = System.currentTimeMillis()
                val model = getModelName()
                val conversation =
                    ClaudeRequestBuilder.conversation(history, prompt, config.systemPrompt)
                val body = ClaudeRequestBuilder.body(conversation, model, stream = true, config, tools)
                Log.i(
                    TAG,
                    "REQUEST | model=$model turns=${conversation.messages.length()} " +
                        "tools=${tools.size} " + tools.joinToString(",") { it.name }
                )

                val fullText = StringBuilder()
                var chunkCount = 0
                // A fresh accumulator per attempt, so a retried turn cannot report a call twice.
                val (outcome, accumulator) = withTransientRetry(delivered = { chunkCount > 0 }) {
                    val attemptCalls = ClaudeToolProtocol.CallAccumulator()
                    val attemptOutcome = streamOnce(body, ClaudeRequestBuilder.betas(model), attemptCalls) { chunk ->
                        chunkCount++
                        fullText.append(chunk)
                        callback.onToken(chunk)
                    }
                    attemptOutcome to attemptCalls
                }
                val calls = accumulator.requests()
                outcome.droppedCalls = accumulator.droppedCalls

                val finalText = fullText.toString()
                Log.i(
                    TAG,
                    "STREAM | model=${outcome.servedBy ?: model} chars=${finalText.length} " +
                        "chunks=$chunkCount calls=${calls.size} dropped=${outcome.droppedCalls} " +
                        "stop=${outcome.stopReason}"
                )

                // Checked before the calls: a declined turn's partial output is not an answer,
                // and a tool call inside it is not one the model stands behind.
                if (outcome.stopReason == "refusal") {
                    context.logger.warn("ClaudeBackend: the model declined this request")
                    callback.onError(failureMessages.of(ClaudeFailure.Refused))
                    return@launch
                }

                // Reported after the stream, so a call is never acted on before it is complete.
                for (call in calls) {
                    Log.i(
                        TAG,
                        "FUNCTION_CALL | tool=${call.name} " +
                            "args=${call.args.orEmpty().keys.joinToString(",")}"
                    )
                    callback.onToolCall(call)
                }

                // A turn that called a tool and said nothing is the normal agent turn, so only a
                // reply with neither text nor a call is empty.
                if (calls.isEmpty() && finalText.isBlank()) {
                    context.logger.warn(
                        "ClaudeBackend: stream produced no reply text " +
                            "(skipped=${outcome.skippedChunks}, " +
                            "thinkingBlocks=${outcome.thinkingBlocks}, " +
                            "droppedCalls=${outcome.droppedCalls}, " +
                            "stopReason=${outcome.stopReason})"
                    )
                    callback.onError(failureMessages.of(emptyReplyFailure(outcome)))
                } else {
                    val tokenCount = finalText.split("\\s+".toRegex()).size
                    context.logger.info("ClaudeBackend: Streamed ${finalText.length} chars in $chunkCount chunks, ~$tokenCount tokens")
                    callback.onComplete(LlmResponse.success(finalText, tokenCount, System.currentTimeMillis() - startTime))
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
    }

    /**
     * What one turn observed beyond the reply text itself.
     *
     * Collected so a turn that ends with no content can say *why* — the difference between a
     * model that only thought, a cap that cut it off, and a shape this parser does not understand.
     *
     * @param skippedChunks payloads the parser could not use
     * @param thinkingBlocks thinking blocks seen, which are never part of the reply
     * @param stopReason the `stop_reason` the API reported, if any
     * @param droppedCalls tool calls whose input never parsed, i.e. arrived half-written
     * @param servedBy the model `message_start` named, which a fallback can make another one
     */
    private data class StreamOutcome(
        var skippedChunks: Int = 0,
        var thinkingBlocks: Int = 0,
        var stopReason: String? = null,
        var droppedCalls: Int = 0,
        var servedBy: String? = null,
    )

    /**
     * Run [attempt], retrying an overloaded or rate-limited API per [TransientRetry].
     *
     * @param delivered true once [attempt] has shown the user anything; from then on a failure is
     *   final, since a retry would repeat what is already on screen
     * @return what the successful attempt returned
     */
    private suspend fun <T> withTransientRetry(
        delivered: () -> Boolean = { false },
        attempt: suspend () -> T,
    ): T {
        var retries = 0
        while (true) {
            try {
                return attempt()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val wait = if (delivered()) null else TransientRetry.delayMs(e, retries)
                if (wait == null) throw e
                retries++
                context.logger.warn(
                    "ClaudeBackend: ${(e as? ClaudeHttpException)?.statusCode} from the API; " +
                        "retry $retries of ${TransientRetry.MAX_RETRIES} in ${wait}ms"
                )
                delay(wait)
            }
        }
    }

    /**
     * POST [body] and feed each streamed text delta to [onText].
     *
     * Tokens already delivered before a mid-stream failure stay delivered.
     *
     * @param betas the `anthropic-beta` values [body] needs
     * @param accumulator collects the `tool_use` blocks the stream carries
     * @return what else the stream carried, for diagnosing an empty reply
     */
    private suspend fun streamOnce(
        body: JSONObject,
        betas: List<String>,
        accumulator: ClaudeToolProtocol.CallAccumulator,
        onText: (String) -> Unit
    ): StreamOutcome {
        val outcome = StreamOutcome()
        // Hoisted: the reader below is an ordinary lambda, with no suspend context of its own.
        val requestContext = coroutineContext
        var cancelHandle: DisposableHandle? = null
        try {
            postMessages(
                body = body,
                betas = betas,
                sse = true,
                onConnected = { conn ->
                    cancelHandle = requestContext[Job]?.invokeOnCompletion { cause ->
                        if (cause != null) conn.disconnect()
                    }
                },
            ) { reader ->
                for (line in reader.lineSequence()) {
                    requestContext.ensureActive()
                    when (val event = ClaudeStreamEvent.parse(line)) {
                        is ClaudeStreamEvent.Text -> onText(event.text)
                        is ClaudeStreamEvent.ToolStart -> accumulator.start(event.index, event.id, event.name)
                        is ClaudeStreamEvent.ToolInput -> accumulator.appendInput(event.index, event.partialJson)
                        ClaudeStreamEvent.ThinkingStarted -> outcome.thinkingBlocks++
                        is ClaudeStreamEvent.Started -> outcome.servedBy = event.model
                        is ClaudeStreamEvent.Stop -> outcome.stopReason = event.reason

                        // Text before the switch stays valid, so the turn simply continues.
                        is ClaudeStreamEvent.FallbackSwitch -> {
                            outcome.servedBy = event.toModel ?: outcome.servedBy
                            Log.i(TAG, "FALLBACK | continued on ${event.toModel}")
                        }

                        // A 200 whose stream carries the real error: raised as the HTTP failure it
                        // stands for, so it classifies and retries the same way.
                        is ClaudeStreamEvent.Failure -> throw ClaudeHttpException(
                            ClaudeHttpException.statusForStreamError(event.errorType),
                            JSONObject().put(
                                "error",
                                JSONObject().put("type", event.errorType).put("message", event.message)
                            ).toString(),
                        )

                        ClaudeStreamEvent.Done -> break
                        ClaudeStreamEvent.Ignored -> Unit

                        // One bad line must not abort a stream that is otherwise producing text.
                        is ClaudeStreamEvent.Malformed -> {
                            outcome.skippedChunks++
                            context.logger.warn("ClaudeBackend: skipping stream line: ${event.detail}")
                        }
                    }
                }
            }
        } finally {
            cancelHandle?.dispose()
        }
        return outcome
    }

    /**
     * Which empty-reply case [outcome] describes.
     *
     * Ordered by how actionable the advice is: a cap that cut the turn off has a cause the user
     * can see, while an unrecognised shape only has a log.
     */
    private fun emptyReplyFailure(outcome: StreamOutcome): ClaudeFailure = when {
        outcome.stopReason == "max_tokens" -> ClaudeFailure.TruncatedBeforeReply
        // Input that stops mid-JSON is a cut-off reply, whatever the API said stopped it.
        outcome.droppedCalls > 0 -> ClaudeFailure.TruncatedBeforeReply
        outcome.thinkingBlocks > 0 -> ClaudeFailure.ReasoningOnly
        else -> ClaudeFailure.EmptyReply(outcome.skippedChunks)
    }

    /** A whole reply read from a request that was not streamed. */
    private class BlockingReply(val text: String, val outcome: StreamOutcome)

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
        val body = ClaudeRequestBuilder.body(conversation, model, stream = false, config)
        return withTransientRetry {
            postMessages(body, ClaudeRequestBuilder.betas(model)) { reader ->
                parseBlockingReply(JSONObject(reader.readText()))
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
        val outcome = StreamOutcome(
            stopReason = response.optString("stop_reason").takeIf { it.isNotBlank() && it != "null" },
            servedBy = response.optString("model").takeIf { it.isNotBlank() },
        )
        val content = response.optJSONArray("content")
        val text = buildString {
            for (i in 0 until (content?.length() ?: 0)) {
                val block = content?.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "text" -> append(block.optString("text"))
                    "thinking", "redacted_thinking" -> outcome.thinkingBlocks++
                }
            }
        }
        return BlockingReply(text, outcome)
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
    internal fun listModels(): CompletableFuture<List<String>> = listModels(readApiKeyOrBlank())

    /**
     * List the models a caller-supplied key can use.
     *
     * Lets the settings pane check a just-typed key *before* it is persisted; the no-arg
     * [listModels] reads what is on disk. Nothing here touches the stored key or its cache.
     *
     * @param apiKey the candidate key; never logged
     */
    internal fun listModels(apiKey: String): CompletableFuture<List<String>> {
        val future = CompletableFuture<List<String>>()
        // close() cancels the scope, making launch a silent no-op; fail loudly instead.
        if (!scope.isActive) {
            future.completeExceptionally(IllegalStateException("Claude backend is closed"))
            return future
        }

        val job = scope.launch {
            try {
                val body = http.get(BASE_URL + ClaudeModelCatalog.PATH, apiKey.trim())
                val models = ClaudeModelCatalog.ids(body)
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

    /** The saved key, or blank when none is stored or it cannot be decrypted. */
    private fun readApiKeyOrBlank(): String = keyCache.read().orEmpty()

    /** Cancel any in-flight generation (user pressed Stop). */
    override fun cancelStreaming() {
        currentJob?.cancel()
        currentJob = null
    }

    /** Release all resources: cancel the backend scope, any in-flight request, and the key cache. */
    fun close() {
        currentJob?.cancel()
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
