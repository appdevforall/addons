package com.itsaky.androidide.plugins.aiagentopenai.backend

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentopenai.R
import com.itsaky.androidide.plugins.aiagentopenai.errors.CredentialFailureLog
import com.itsaky.androidide.plugins.aiagentopenai.errors.OpenAiErrorFormatter
import com.itsaky.androidide.plugins.aiagentopenai.errors.OpenAiFailure
import com.itsaky.androidide.plugins.aiagentopenai.errors.OpenAiFailureMessages
import com.itsaky.androidide.plugins.aiagentopenai.errors.OpenAiHttpException
import com.itsaky.androidide.plugins.aiagentopenai.errors.isCredentialProblem
import com.itsaky.androidide.plugins.aiagentopenai.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aiagentopenai.preferences.OpenAiPreferences
import com.itsaky.androidide.plugins.aiagentopenai.prompt.OpenAiSystemPrompt
import com.itsaky.androidide.plugins.aiagentopenai.security.ApiKeyCache
import com.itsaky.androidide.plugins.aiagentopenai.settings.BaseUrlPolicy
import com.itsaky.androidide.plugins.aiagentopenai.settings.BaseUrlResult
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool-protocol tracing, under the tag suffix `ai-core` uses for the other half of the same run:
 * `adb logcat -s AiCore.AgentTrace:V AiAgentOpenAi.AgentTrace:V` reads a run end to end.
 *
 * Through [Log] rather than `context.logger`, which the host funnels into its own class's tag with
 * only a `[pluginId]` prefix — unfilterable, and so absent from a captured log of an agent run.
 */
private const val TAG = "$LOG_PREFIX.AgentTrace"

/**
 * OpenAI-compatible backend: one transport for every server that speaks `chat/completions`.
 *
 * The base URL is a setting, defaulting to OpenAI's own API. Across OpenAI, Ollama, LM Studio,
 * OpenRouter and llama-server the auth header, request JSON, SSE framing and error shape are
 * identical — only the host changes — so this is one backend rather than one per provider.
 *
 * What this class owns is the *conversation*: which model, which turns, what to do when a server
 * rejects a parameter or answers nothing. Sockets are [OpenAiHttpClient]'s, the decrypted key is
 * [ApiKeyCache]'s, and the wording of a failure is [OpenAiFailureMessages]'.
 */
class OpenAiBackend(
    private val context: PluginContext
) : HistoryCapableBackend, CancellableBackend, ConfigurableBackend, ToolCallingBackend {

    private val scope = CoroutineScope(Dispatchers.IO)

    private val http = OpenAiHttpClient()

    private val keyCache =
        ApiKeyCache(::openAiPrefs, OpenAiPreferences.KEY_API_KEY, context.logger, scope)

    private val failureMessages = OpenAiFailureMessages(context, ::getBaseUrl)

    /**
     * Where a refused credential is left for the settings pane to report, so a key problem is not
     * only readable in a transcript the user has already navigated away from.
     */
    private val credentialFailures = CredentialFailureLog(::openAiPrefs)

    @Volatile
    private var currentJob: Job? = null

    /**
     * Base URL that answered a tool declaration with a refusal, so the next turn does not pay the
     * same round trip. Keyed by the URL itself, so pointing the setting elsewhere re-probes.
     */
    @Volatile
    private var toolsRejectedBy: String? = null

    companion object {
        /** Backend id, as persisted by AI Core when the user selects this backend. */
        const val BACKEND_ID = "openai"

        /** Default model, matching the default base URL. Editable on this backend's settings pane. */
        const val DEFAULT_MODEL = "gpt-5"

        /** Chat endpoint, appended to the configured base URL. */
        private const val CHAT_COMPLETIONS_PATH = "/chat/completions"

        /** Model-catalog endpoint. Optional: many compatible servers do not implement it. */
        private const val MODELS_PATH = "/models"
    }

    /** This plugin's own settings, written by its settings pane and read here at request time. */
    private fun openAiPrefs(): SharedPreferences? = try {
        OpenAiPreferences.of(context)
    } catch (e: Exception) {
        context.logger.error("OpenAiBackend: Error getting preferences", e)
        null
    }

    /**
     * The configured server, normalized, falling back to OpenAI's own API.
     *
     * Re-normalized on read rather than trusted: a value written by an older build has not been
     * through the policy.
     */
    private fun getBaseUrl(): String {
        val stored = openAiPrefs()?.getString(OpenAiPreferences.KEY_BASE_URL, null)
        val accepted = BaseUrlPolicy.normalize(stored) as? BaseUrlResult.Accepted
        return accepted?.url ?: BaseUrlPolicy.DEFAULT_BASE_URL
    }

    /** The model to request, or the default when nothing is stored. */
    private fun getModelName(): String =
        openAiPrefs()?.getString(OpenAiPreferences.KEY_MODEL, DEFAULT_MODEL)
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_MODEL

    /**
     * Decrypt the stored key off-thread now, so a main-thread [isAvailable] can't report "no key"
     * for a key that is there. Called once, on activation.
     */
    fun warmKeyCache() = keyCache.warm()

    override fun getId(): String = BACKEND_ID

    /** Falls back to a literal: an empty name would be an unlabelled row in the selector. */
    override fun getName(): String = configLabel(R.string.openai_backend_name, fallback = "OpenAI")

    /**
     * Resolves a label against this plugin's own resources, degrading rather than throwing —
     * [getName] is called across the plugin boundary.
     *
     * @param fallback returned when the lookup fails
     */
    private fun configLabel(resId: Int, fallback: String = ""): String = try {
        context.androidContext.getString(resId)
    } catch (e: Exception) {
        context.logger.error("OpenAiBackend: could not resolve label $resId", e)
        fallback
    }

    /**
     * Written for a large cloud model; see [OpenAiSystemPrompt] for why the wording belongs here
     * rather than with the caller.
     */
    override fun getSystemPrompt(request: SystemPromptRequest): String =
        OpenAiSystemPrompt.build(request)

    /**
     * Room to plan, matching the high-autonomy prompt this backend asks for — or null for a
     * reasoning model, several of which reject `temperature` outright.
     */
    override fun getDefaultTemperature(): Float? =
        if (RequestTuning.isReasoningModel(getModelName())) null else 0.7f

    /**
     * This backend draws its own settings, so the consumer needs no knowledge of API keys, base
     * URLs or server presets.
     */
    override fun getSettingsFragmentClassName(): String =
        "com.itsaky.androidide.plugins.aiagentopenai.settings.OpenAiSettingsFragment"

    /**
     * Available when the server can plausibly be called.
     *
     * A key is required only for OpenAI's own API. For any other base URL a non-blank URL is
     * enough: local Ollama and LM Studio need no credential, and demanding one would leave this
     * backend permanently "not available" for the users who asked for a custom server.
     */
    override fun isAvailable(): Boolean {
        val baseUrl = getBaseUrl()
        if (!BaseUrlPolicy.requiresApiKey(baseUrl)) {
            context.logger.debug("OpenAiBackend.isAvailable() - custom server configured: $baseUrl")
            return true
        }
        // Origin-checked, or the backend would report itself ready on a key it will not send.
        val apiKey = readApiKeyOrBlank()
        context.logger.debug("OpenAiBackend.isAvailable() - API key configured: ${apiKey.isNotBlank()}")
        return apiKey.isNotBlank()
    }

    override fun generate(prompt: String, config: LlmConfig): CompletableFuture<LlmResponse> {
        val future = CompletableFuture<LlmResponse>()

        currentJob = scope.launch {
            val keyStamp = storedKeyStamp()
            try {
                val startTime = System.currentTimeMillis()
                context.logger.info("OpenAiBackend: Generating response for prompt (${prompt.length} chars)")

                val messages = OpenAiRequestBuilder.messages(emptyList(), prompt, config.systemPrompt)
                val text = requestText(messages, config)

                if (text.isBlank()) {
                    future.complete(LlmResponse.failure(failureMessages.of(OpenAiFailure.Failed(null))))
                } else {
                    val tokenCount = text.split("\\s+".toRegex()).size  // Approximate token count
                    context.logger.info("OpenAiBackend: Generated ${text.length} chars, ~$tokenCount tokens")
                    future.complete(LlmResponse.success(text, tokenCount, System.currentTimeMillis() - startTime))
                }
            } catch (e: CancellationException) {
                future.cancel(true)
                throw e
            } catch (e: Exception) {
                context.logger.error("OpenAiBackend: Error generating response", e)
                future.complete(LlmResponse.failure(formatErrorMessage(e, keyStamp)))
            }
        }

        return future
    }

    override fun generateStreaming(
        prompt: String,
        config: LlmConfig,
        callback: StreamCallback
    ) {
        streamMessages(
            OpenAiRequestBuilder.messages(emptyList(), prompt, config.systemPrompt),
            config,
            emptyList(),
            callback.asToolCallback()
        )
    }

    override fun generateWithHistory(
        history: List<ChatMessage>,
        prompt: String,
        config: LlmConfig
    ): CompletableFuture<LlmResponse> {
        context.logger.info("OpenAiBackend.generateWithHistory() called with ${history.size} messages")

        val future = CompletableFuture<LlmResponse>()

        currentJob = scope.launch {
            val keyStamp = storedKeyStamp()
            try {
                val startTime = System.currentTimeMillis()

                val messages = OpenAiRequestBuilder.messages(history, prompt, config.systemPrompt)
                val text = requestText(messages, config)

                if (text.isBlank()) {
                    future.complete(LlmResponse.failure(failureMessages.of(OpenAiFailure.Failed(null))))
                } else {
                    val tokenCount = text.split("\\s+".toRegex()).size
                    context.logger.info("OpenAiBackend: Generated ${text.length} chars with history, ~$tokenCount tokens")
                    future.complete(LlmResponse.success(text, tokenCount, System.currentTimeMillis() - startTime))
                }
            } catch (e: CancellationException) {
                future.cancel(true)
                throw e
            } catch (e: Exception) {
                context.logger.error("OpenAiBackend: Error generating with history", e)
                future.complete(LlmResponse.failure(formatErrorMessage(e, keyStamp)))
            }
        }

        return future
    }

    /**
     * Streams a reply for a multi-turn conversation, sending [history] as real `messages[]` turns.
     *
     * @param history the conversation so far, oldest first
     * @param prompt the current user turn
     * @param config sampling settings; its system prompt becomes the leading `system` turn
     * @param callback receives tokens, completion, and errors
     */
    override fun generateStreamingWithHistory(
        history: List<ChatMessage>,
        prompt: String,
        config: LlmConfig,
        callback: StreamCallback
    ) {
        streamMessages(
            OpenAiRequestBuilder.messages(history, prompt, config.systemPrompt),
            config,
            emptyList(),
            callback.asToolCallback()
        )
    }

    /**
     * Streams a turn with [tools] declared to the server, reporting each `tool_calls` entry through
     * [ToolStreamCallback.onToolCall].
     *
     * This is the path the agent takes. Declaring the tools is what stops the model writing a call
     * as prose the caller has to parse back: the arguments arrive already structured, so a file
     * whose contents contain quotes or newlines can no longer break the call carrying it
     * (ADFA-5410).
     *
     * @param prompt the current user turn
     * @param history the conversation so far, oldest first
     * @param config sampling settings; its system prompt becomes the leading `system` turn
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
        streamMessages(
            OpenAiRequestBuilder.messages(history, prompt, config.systemPrompt),
            config,
            tools,
            callback
        )
    }

    /**
     * Adapts a plain stream callback to the tool-aware one [streamMessages] takes.
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
     * Streams one `chat/completions` request over the already-built [messages].
     *
     * @param messages the request's `messages[]` turns
     * @param config sampling settings for this request
     * @param tools the tools to declare, or empty to stream plain text
     * @param callback receives tokens, tool calls, completion, and errors
     */
    private fun streamMessages(
        messages: JSONArray,
        config: LlmConfig,
        tools: List<ToolDefinition>,
        callback: ToolStreamCallback
    ) {
        currentJob = scope.launch {
            val keyStamp = storedKeyStamp()
            try {
                val startTime = System.currentTimeMillis()

                val fullText = StringBuilder()
                var chunkCount = 0
                var outcome = StreamOutcome()
                var calls = emptyList<ToolCallRequest>()
                withToolRetry(tools) { declared ->
                    // The retry exists because reasoning models and third-party servers disagree
                    // about max_tokens/temperature; see RequestTuning.
                    withParameterRetry(config) { tuning ->
                        fullText.clear()
                        chunkCount = 0
                        val accumulator = OpenAiToolProtocol.CallAccumulator()
                        val body = OpenAiRequestBuilder.body(
                            messages,
                            getModelName(),
                            stream = true,
                            config = config,
                            tuning = tuning,
                            tools = declared,
                        )
                        Log.i(
                            TAG,
                            "REQUEST | model=${getModelName()} turns=${messages.length()} " +
                                "tools=${declared.size} " +
                                declared.joinToString(",") { it.name }
                        )
                        outcome = streamOnce(body, accumulator) { chunk ->
                            chunkCount++
                            fullText.append(chunk)
                            callback.onToken(chunk)
                        }
                        calls = accumulator.requests()
                        outcome.droppedCalls = accumulator.droppedCalls
                    }
                }

                val finalText = fullText.toString()
                Log.i(
                    TAG,
                    "STREAM | chars=${finalText.length} chunks=$chunkCount calls=${calls.size} " +
                        "dropped=${outcome.droppedCalls} finish=${outcome.finishReason}"
                )
                // Reported after the stream, so a call the retry replaced never reaches the caller.
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
                    // The request succeeded and the stream ended, so this is not a failed request;
                    // say which of the empty-reply cases it was instead of a generic error.
                    context.logger.warn(
                        "OpenAiBackend: stream produced no reply text " +
                            "(skipped=${outcome.skippedChunks}, " +
                            "reasoningChars=${outcome.reasoningChars}, " +
                            "droppedCalls=${outcome.droppedCalls}, " +
                            "finishReason=${outcome.finishReason})"
                    )
                    callback.onError(failureMessages.of(emptyReplyFailure(outcome)))
                } else {
                    val tokenCount = finalText.split("\\s+".toRegex()).size
                    context.logger.info("OpenAiBackend: Streamed ${finalText.length} chars in $chunkCount chunks, ~$tokenCount tokens")
                    callback.onComplete(LlmResponse.success(finalText, tokenCount, System.currentTimeMillis() - startTime))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                Log.e(TAG, "STREAM | failed: ${e.message}", e)
                context.logger.error("OpenAiBackend: Error in streaming", e)
                callback.onError(formatErrorMessage(e, keyStamp))
            }
        }
    }

    /**
     * What one streaming attempt observed beyond the reply text itself.
     *
     * Collected so a stream that ends with no content can say *why* — the difference between a
     * reasoning model that never answered, a server that reported an error inside a 200, and a
     * shape this parser does not understand.
     *
     * @param skippedChunks payloads the parser could not use
     * @param reasoningChars thinking text seen, which is never part of the reply
     * @param finishReason the last `finish_reason` the server sent, if any
     * @param droppedCalls tool calls whose arguments never parsed, i.e. arrived half-written
     */
    private data class StreamOutcome(
        var skippedChunks: Int = 0,
        var reasoningChars: Int = 0,
        var finishReason: String? = null,
        var droppedCalls: Int = 0,
    )

    /**
     * POST [body] and feed each streamed chunk to [onChunk].
     *
     * Tokens already delivered before a mid-stream failure stay delivered; the caller resets its
     * buffer before a retry, which only ever happens on a 400 raised before any token arrived.
     *
     * @param accumulator collects the native tool-call fragments the stream carries
     * @return what else the stream carried, for diagnosing an empty reply
     */
    private suspend fun streamOnce(
        body: JSONObject,
        accumulator: OpenAiToolProtocol.CallAccumulator,
        onChunk: (String) -> Unit
    ): StreamOutcome {
        val outcome = StreamOutcome()
        // Hoisted: the reader below is an ordinary lambda, with no suspend context of its own.
        val requestContext = coroutineContext
        var cancelHandle: DisposableHandle? = null
        try {
            postChat(
                body = body,
                sse = true,
                onConnected = { conn ->
                    cancelHandle = requestContext[Job]?.invokeOnCompletion { cause ->
                        if (cause != null) conn.disconnect()
                    }
                },
            ) { reader ->
                for (line in reader.lineSequence()) {
                    requestContext.ensureActive()
                    when (val event = SseChunk.parse(line)) {
                        is SseChunk.Event.Token -> onChunk(event.text)

                        is SseChunk.Event.ToolCalls -> {
                            accumulator.accept(event.deltas)
                            // Prose the same chunk carried; usually empty, never dropped.
                            if (event.text.isNotEmpty()) onChunk(event.text)
                        }

                        // Not shown, but proof the model was working; see StreamOutcome.
                        is SseChunk.Event.Reasoning ->
                            outcome.reasoningChars += event.text.length

                        // A 200 whose body carries the real error: raised so it reaches the same
                        // classifier as an HTTP-level failure instead of ending the stream empty.
                        is SseChunk.Event.Failure ->
                            throw IOException("OpenAI stream error: ${event.message}")

                        is SseChunk.Event.Finish -> outcome.finishReason = event.reason

                        SseChunk.Event.Done -> break
                        SseChunk.Event.Ignored -> Unit

                        // One bad chunk must not abort a stream that is otherwise producing text.
                        is SseChunk.Event.Malformed -> {
                            outcome.skippedChunks++
                            context.logger.warn(
                                "OpenAiBackend: skipping SSE chunk: ${event.detail}"
                            )
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
     * Ordered by how actionable the advice is: reasoning that ate the budget and a truncating
     * token cap both have a fix the user can apply, while an unrecognised shape only has a log.
     */
    private fun emptyReplyFailure(outcome: StreamOutcome): OpenAiFailure = when {
        outcome.reasoningChars > 0 -> OpenAiFailure.ReasoningOnly
        outcome.finishReason == "length" -> OpenAiFailure.TruncatedBeforeReply
        // Arguments that stop mid-JSON are a cut-off reply, whatever the server said stopped it.
        outcome.droppedCalls > 0 -> OpenAiFailure.TruncatedBeforeReply
        else -> OpenAiFailure.EmptyReply(outcome.skippedChunks)
    }

    /**
     * Run [attempt] with [tools] declared and, if this server refuses a tool declaration, run it
     * once more with none.
     *
     * The refusal is remembered per server so only the first turn pays for it. What it costs is
     * real: the system prompt for this run was built for native calling, so it teaches no envelope
     * and the model has no other way to reach a tool — the turn answers in prose. Servers that
     * take `tools` are the overwhelming majority, and this keeps the rest chatting rather than
     * failing outright.
     *
     * @param tools the tools this turn wants declared; an empty list skips straight through
     * @param attempt the request to make, given the tools to actually declare
     */
    private suspend fun withToolRetry(
        tools: List<ToolDefinition>,
        attempt: suspend (List<ToolDefinition>) -> Unit
    ) {
        val baseUrl = getBaseUrl()
        if (tools.isEmpty() || toolsRejectedBy == baseUrl) {
            attempt(emptyList())
            return
        }
        try {
            attempt(tools)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OpenAiHttpException) {
            if (!UnsupportedTools.rejectedIn(e.statusCode, e.body)) throw e
            toolsRejectedBy = baseUrl
            Log.w(TAG, "REQUEST | server refused a tool declaration; retrying with none")
            context.logger.warn(
                "OpenAiBackend: $baseUrl does not accept tool declarations; " +
                    "the agent cannot call tools on this server"
            )
            // Said out loud, not only logged: from here the agent answers but never touches the
            // project, which reads as the tools being broken. Once per server, since the flag
            // above short-circuits every later turn.
            notifyToolsUnsupported(baseUrl)
            attempt(emptyList())
        }
    }

    /**
     * Tells the user this server cannot call tools, as a Toast: the run continues, so there is no
     * error message to carry it, and the chat's own turn is an ordinary prose answer.
     *
     * @param baseUrl the server that refused, named in the message.
     */
    private fun notifyToolsUnsupported(baseUrl: String) {
        val appContext = context.androidContext.applicationContext
        val message = appContext.getString(R.string.openai_error_tools_unsupported, baseUrl)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Run [attempt] and, if the server rejected one optional parameter, run it once more without it.
     *
     * Compatible servers vary too much to hardcode which parameters each accepts, so the matrix is
     * discovered from the one 400 that names the offender.
     *
     * @param config supplies the model, which decides the starting tuning
     * @param attempt the request to make, given the tuning to use
     */
    private suspend fun withParameterRetry(
        config: LlmConfig,
        attempt: suspend (RequestTuning) -> Unit
    ) {
        val model = getModelName()
        val tuning = RequestTuning.forModel(model, BaseUrlPolicy.requiresApiKey(getBaseUrl()))
        try {
            attempt(tuning)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OpenAiHttpException) {
            if (e.statusCode != 400) throw e
            val param = UnsupportedParameter.nameIn(e.body) ?: throw e
            val adjusted = tuning.without(param) ?: throw e
            context.logger.info(
                "OpenAiBackend: server rejected '$param'; retrying once without it"
            )
            attempt(adjusted)
        }
    }

    /**
     * POST [messages] without streaming and return the reply text.
     *
     * @param messages the request's `messages[]` turns
     * @param config sampling settings for this request
     * @return the reply text, or "" when the server returned no choices
     */
    private suspend fun requestText(messages: JSONArray, config: LlmConfig): String {
        var text = ""
        withParameterRetry(config) { tuning ->
            val body = OpenAiRequestBuilder.body(
                messages, getModelName(), stream = false, config = config, tuning = tuning
            )
            text = postChat(body) { reader -> extractText(JSONObject(reader.readText())) }
        }
        return text
    }

    /**
     * POST [body] to the configured server's chat endpoint with the stored credential.
     *
     * Every generation goes through here, streaming or not, which is why the recorded refusal is
     * cleared here — on the status line, since a 2xx is the server accepting the key whether or not
     * the body that follows is read to the end, or cancelled, or dropped mid-stream.
     *
     * @param sse true to ask for the server-sent-events stream
     * @param onConnected receives the live connection, so the caller can disconnect it on cancel
     * @return whatever [readResponse] produced
     */
    private fun <T> postChat(
        body: JSONObject,
        sse: Boolean = false,
        onConnected: (HttpURLConnection) -> Unit = {},
        readResponse: (BufferedReader) -> T,
    ): T = http.post(
        url = getBaseUrl() + CHAT_COMPLETIONS_PATH,
        apiKey = readApiKeyOrBlank(),
        body = body,
        sse = sse,
        onConnected = onConnected,
        onAccepted = { credentialFailures.clear() },
        readResponse = readResponse,
    )

    /**
     * List the models the configured server offers, filtered to plausible chat models.
     *
     * Completes with an empty list when the server answered with none, and exceptionally on a
     * network/API failure — an HTTP one as an [OpenAiHttpException], so the caller can tell a
     * refused key from an unreachable server.
     */
    fun listModels(): CompletableFuture<List<String>> = listModels(readApiKeyOrBlank(), getBaseUrl())

    /**
     * List the models reachable with a caller-supplied credential and server.
     *
     * Lets the settings pane check a just-typed key or URL *before* either is persisted; the no-arg
     * [listModels] reads what is on disk. Nothing here touches the stored key or its cache.
     *
     * @param apiKey the candidate key, or blank for a server that needs none; never logged
     * @param baseUrl the candidate server, normalized by the caller
     */
    fun listModels(apiKey: String, baseUrl: String): CompletableFuture<List<String>> {
        val future = CompletableFuture<List<String>>()
        // close() cancels the scope, making launch a silent no-op; fail loudly instead.
        if (!scope.isActive) {
            future.completeExceptionally(IllegalStateException("OpenAI backend is closed"))
            return future
        }

        val job = scope.launch {
            try {
                val models = fetchAvailableModels(apiKey.trim(), baseUrl)
                context.logger.info("OpenAiBackend: ${models.size} chat models offered by $baseUrl")
                future.complete(models)
            } catch (e: CancellationException) {
                future.cancel(true)
                throw e
            } catch (e: Exception) {
                context.logger.warn("OpenAiBackend: model listing failed: ${e.message}")
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
        openAiPrefs()?.getLong(OpenAiPreferences.KEY_API_KEY_TIMESTAMP, 0L) ?: 0L

    /**
     * The saved key, but only for the server it was saved for.
     *
     * A key entered for OpenAI must not travel to whatever server the URL is pointed at next: a
     * loopback or private-range server is reached over plain `http`, so the bearer token would
     * cross the local network in the clear. A key stored before the origin was recorded is still
     * sent — it cannot be shown to belong elsewhere, and dropping it would break an upgrade.
     */
    private fun readApiKeyOrBlank(): String {
        val savedFor = openAiPrefs()?.getString(OpenAiPreferences.KEY_API_KEY_URL, null)
        if (savedFor != null && !BaseUrlPolicy.sameOrigin(savedFor, getBaseUrl())) {
            context.logger.debug("OpenAiBackend: saved key belongs to another server; sending none")
            return ""
        }
        return keyCache.read().orEmpty()
    }

    /** Fetch and filter `GET {baseUrl}/models`. Runs on the caller's (IO) coroutine. */
    private fun fetchAvailableModels(apiKey: String, baseUrl: String): List<String> {
        val body = http.get(baseUrl + MODELS_PATH, apiKey)
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val ids = (0 until data.length()).mapNotNull { index ->
            data.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }
        }
        return ChatModelFilter.chatModels(ids)
    }

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
     * Extract the reply text of a non-streamed response.
     *
     * @param response a `chat/completions` response
     * @return the reply text, or "" when there are no choices
     */
    private fun extractText(response: JSONObject): String {
        val choices = response.optJSONArray("choices") ?: return ""
        return buildString {
            for (i in 0 until choices.length()) {
                val message = choices.optJSONObject(i)?.optJSONObject("message") ?: continue
                append(message.optString("content"))
            }
        }
    }

    /**
     * Turn a failure into one user-facing sentence.
     *
     * [OpenAiErrorFormatter] decides *what* went wrong; the wording comes from `strings.xml`. The
     * raw HTTP error body stays on the logged exception and must never reach the transcript.
     *
     * @param keyStamp when the key this request read was saved, so a refusal landing after a later
     *   save or clear is not reported against a credential that was never tried
     */
    private fun formatErrorMessage(e: Exception, keyStamp: Long): String {
        val baseUrl = getBaseUrl()
        val failure = OpenAiErrorFormatter.classify(
            error = e,
            modelName = getModelName(),
            hasApiKey = readApiKeyOrBlank().isNotBlank(),
            isOpenAiHost = BaseUrlPolicy.requiresApiKey(baseUrl),
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
