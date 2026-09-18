package com.itsaky.androidide.plugins.aiagentgemini.backend

import android.content.SharedPreferences
import android.os.Looper
import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentgemini.R
import com.itsaky.androidide.plugins.aiagentgemini.errors.CredentialFailure
import com.itsaky.androidide.plugins.aiagentgemini.errors.CredentialFailureLog
import com.itsaky.androidide.plugins.aiagentgemini.errors.GeminiErrorFormatter
import com.itsaky.androidide.plugins.aiagentgemini.errors.GeminiFailure
import com.itsaky.androidide.plugins.aiagentgemini.errors.isCredentialProblem
import com.itsaky.androidide.plugins.aiagentgemini.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aiagentgemini.preferences.GeminiPreferences
import com.itsaky.androidide.plugins.aiagentgemini.prompt.GeminiSystemPrompt
import com.itsaky.androidide.plugins.aiagentgemini.security.secureApiKeyStore
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool-protocol tracing, under the tag suffix `ai-core` uses for the other half of the same run:
 * `adb logcat -s AiCore.AgentTrace:V AiAgentGemini.AgentTrace:V` reads a run end to end.
 *
 * These lines go through [Log] rather than `context.logger`, which the host funnels into its own
 * class's tag with only a `[pluginId]` prefix — unfilterable, and why a captured log of an agent
 * run showed nothing from this plugin at all.
 */
private const val TAG = "$LOG_PREFIX.AgentTrace"

/**
 * Gemini API backend for cloud-based LLM inference.
 *
 * Talks to the Generative Language REST API directly over [HttpURLConnection] rather than the
 * google-genai SDK: the SDK bundles OkHttp 4.x and calls `RequestBody.create(String, MediaType)`,
 * but plugins run in the host IDE's classloader where `okhttp3` resolves to the host's older
 * OkHttp (no such overload) — that mismatch crashed generation with a NoSuchMethodError.
 * HttpURLConnection has no third-party dependency, so it works regardless of the host's OkHttp.
 */
class GeminiBackend(
    private val context: PluginContext
) : HistoryCapableBackend, CancellableBackend, ConfigurableBackend, ToolCallingBackend {

    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var currentJob: Job? = null

    /**
     * Last decryption, as (value on disk -> plaintext). Decrypting costs a Keystore IPC round
     * trip and [isAvailable] runs on every generate, so caching against the raw stored value
     * pays that cost once and re-decrypts only when the stored key actually changes.
     */
    @Volatile
    private var keyCache: Pair<String, String?>? = null

    /**
     * Where a refused credential is left for the settings pane to report, so a key problem is not
     * only readable in a transcript the user has already navigated away from.
     */
    private val credentialFailures = CredentialFailureLog(::agentPrefs)

    companion object {
        /** Current default model. gemini-1.5-* is retired on v1beta and now 404s. */
        const val DEFAULT_MODEL = "gemini-2.5-flash"

        /** Base URL for the v1beta models API (ListModels, generateContent, streaming). */
        private const val MODELS_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models"

        /** Generation method for chat; also the flag ListModels advertises for chat-capable models. */
        private const val METHOD_GENERATE_CONTENT = "generateContent"

        /** Server-sent-events streaming variant of [METHOD_GENERATE_CONTENT]. */
        private const val METHOD_STREAM_GENERATE_CONTENT = "streamGenerateContent"

        /** `finishReason` for a reply the model's output cap cut short. */
        private const val FINISH_REASON_MAX_TOKENS = "MAX_TOKENS"
    }

    /** This plugin's own settings, written by its settings pane and read here at request time. */
    private fun agentPrefs(): SharedPreferences? = try {
        GeminiPreferences.of(context)
    } catch (e: Exception) {
        context.logger.error("GeminiBackend: Error getting preferences", e)
        null
    }

    /**
     * Get the model name from preferences, or use the current default.
     */
    private fun getModelName(): String =
        agentPrefs()?.getString(GeminiPreferences.KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

    /**
     * When the stored key was saved, or 0 when none is stored.
     *
     * Read into a local at the top of each request and carried to that request's error handler: a
     * field on the backend is overwritten by any other key read before the refusal lands.
     */
    private fun storedKeyStamp(): Long =
        agentPrefs()?.getLong(GeminiPreferences.KEY_API_KEY_TIMESTAMP, 0L) ?: 0L

    /**
     * Read the saved Gemini API key from AI Core's shared prefs, or null.
     *
     * Decryption is Keystore IPC + AES/GCM and must not run on the main thread. Every caller
     * today reaches this from [Dispatchers.IO], but [LlmBackend.isAvailable] is a synchronous
     * interface method a future caller could invoke from the UI thread — so instead of relying
     * on that, a main-thread call answers from the cache and kicks off a background refresh
     * rather than blocking; [warmKeyCache] fills the cache first so that never reports "no key".
     */
    private fun readGeminiApiKey(): String? {
        val stored = agentPrefs()?.getString(GeminiPreferences.KEY_API_KEY, null)
        if (stored.isNullOrBlank()) {
            keyCache = null
            return null
        }
        keyCache?.let { (raw, plain) -> if (raw == stored) return plain }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            context.logger.warn("GeminiBackend: API key read on the main thread; refreshing off-thread")
            // close() cancels scope, so without this guard the launch is a silent no-op.
            if (scope.isActive) {
                scope.launch { refreshKeyCache() }
            } else {
                context.logger.warn("GeminiBackend: backend already closed; not refreshing key cache")
            }
            return null
        }
        return refreshKeyCache()
    }

    /**
     * Decrypt the stored key — upgrading a pre-encryption plaintext value in passing — and
     * cache the result. Off-main-thread only; see [readGeminiApiKey].
     */
    private fun refreshKeyCache(): String? {
        val prefs = agentPrefs()
        val plain = when (val stored = secureApiKeyStore.readAndMigrate(prefs, GeminiPreferences.KEY_API_KEY)) {
            is KeystoreSecretStore.Stored.Value -> stored.plain.trim().takeIf { it.isNotBlank() }
            KeystoreSecretStore.Stored.Absent -> null
            // Reported here rather than passed on as "no key": generation fails either way, but a
            // lost Keystore entry needs the key entering again, and the log is all that says so.
            KeystoreSecretStore.Stored.Unreadable -> {
                context.logger.warn(
                    "GeminiBackend: the saved API key cannot be decrypted on this device; " +
                        "it has to be entered again in settings"
                )
                null
            }
            // Transient, so it returns without caching: the key is very likely intact, and caching
            // this answer would freeze "no key" until the stored value itself changed.
            KeystoreSecretStore.Stored.Unavailable -> {
                context.logger.warn(
                    "GeminiBackend: the keystore could not be reached to read the saved API key; " +
                        "retrying on the next read"
                )
                return null
            }
        }
        val raw = prefs?.getString(GeminiPreferences.KEY_API_KEY, null)
        keyCache = raw?.let { it to plain }
        return plain
    }

    /**
     * Warm [keyCache] off-thread, so the synchronous [isAvailable] never reports "no key" for a
     * stored, decryptable key just because it was first called from the main thread. Invoked from
     * [GeminiPlugin.activate].
     */
    fun warmKeyCache() {
        if (!scope.isActive) return
        scope.launch {
            try {
                val warmed = refreshKeyCache() != null
                context.logger.debug("GeminiBackend: key cache warmed (key present: $warmed)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context.logger.warn("GeminiBackend: could not warm key cache: ${e.message}")
            }
        }
    }

    override fun getId(): String = "gemini"

    override fun getName(): String = "Gemini API"

    /**
     * Written for a large cloud model; see [GeminiSystemPrompt] for why the wording belongs here
     * rather than with the caller.
     */
    override fun getSystemPrompt(request: SystemPromptRequest): String =
        GeminiSystemPrompt.build(request)

    /** Room to plan, matching the high-autonomy prompt this backend asks for. */
    override fun getDefaultTemperature(): Float = 0.7f

    /**
     * This backend draws its own settings, so the consumer needs no knowledge of API keys, AI
     * Studio or Google's model catalog.
     */
    override fun getSettingsFragmentClassName(): String =
        "com.itsaky.androidide.plugins.aiagentgemini.settings.GeminiSettingsFragment"

    override fun isAvailable(): Boolean {
        // Available once a (decryptable) API key is configured.
        val apiKey = readGeminiApiKey()
        context.logger.debug("GeminiBackend.isAvailable() - API key configured: ${!apiKey.isNullOrBlank()}")
        return !apiKey.isNullOrBlank()
    }

    override fun generate(prompt: String, config: LlmConfig): CompletableFuture<LlmResponse> {
        val future = CompletableFuture<LlmResponse>()

        currentJob = scope.launch {
            val keyStamp = storedKeyStamp()
            try {
                val apiKey = readGeminiApiKey()
                    ?: run {
                        future.complete(LlmResponse.failure("Gemini API key not configured"))
                        return@launch
                    }

                val startTime = System.currentTimeMillis()
                context.logger.info("GeminiBackend: Generating response for prompt (${prompt.length} chars)")

                val contents = JSONArray().put(contentJson("user", buildPrompt(prompt, config)))
                val text = requestText(getModelName(), apiKey, buildRequestJson(contents, config))

                if (text.isBlank()) {
                    future.complete(LlmResponse.failure("Empty response from Gemini API"))
                } else {
                    val tokenCount = text.split("\\s+".toRegex()).size  // Approximate token count
                    context.logger.info("GeminiBackend: Generated ${text.length} chars, ~$tokenCount tokens")
                    future.complete(LlmResponse.success(text, tokenCount, System.currentTimeMillis() - startTime))
                }
            } catch (e: CancellationException) {
                future.cancel(true)
                throw e
            } catch (e: Exception) {
                context.logger.error("GeminiBackend: Error generating response", e)
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
        val contents = JSONArray().put(contentJson("user", buildPrompt(prompt, config)))
        streamContents(contents, config, emptyList(), callback.asToolCallback())
    }

    /**
     * Builds the `contents[]` array for a multi-turn request.
     *
     * Gemini has no system role, so the system prompt is carried as a leading user turn the model
     * acknowledges — the same shape [generateWithHistory] uses, kept in one place so the two
     * transports cannot drift apart.
     *
     * Consecutive same-role turns are merged into one content, because the transcript no longer
     * always alternates: the agent loop drops an ASSISTANT turn that carried only a native call
     * and no prose, leaving the user message and the tool results it produced adjacent.
     *
     * @param history the conversation so far, oldest first
     * @param prompt the current user turn, appended last
     * @param config supplies the optional system prompt
     */
    internal fun buildContents(
        history: List<ChatMessage>,
        prompt: String,
        config: LlmConfig
    ): JSONArray {
        val turns = mutableListOf<Pair<String, String>>()
        // Folds a turn into the previous one when the role repeats, so the roles alternate.
        fun add(role: String, text: String) {
            val last = turns.lastOrNull()
            if (last != null && last.first == role) {
                turns[turns.lastIndex] = role to (last.second + "\n\n" + text)
            } else {
                turns.add(role to text)
            }
        }
        config.systemPrompt?.let { systemPrompt ->
            add("user", systemPrompt)
            add("model", "Understood.")
        }
        for (msg in history) {
            val role = when (msg.role) {
                ChatMessage.Role.USER -> "user"
                ChatMessage.Role.ASSISTANT -> "model"
                // Gemini has no system role; a mid-conversation system note goes as a user turn.
                ChatMessage.Role.SYSTEM -> "user"
                // A functionResponse part pairs with a functionCall one, and the assistant turn
                // in history is text, so a tool result rides in as a user turn instead.
                ChatMessage.Role.TOOL -> "user"
            }
            add(role, msg.content)
        }
        add("user", prompt)

        val contents = JSONArray()
        for ((role, text) in turns) {
            contents.put(contentJson(role, text))
        }
        return contents
    }

    /**
     * Streams one `streamGenerateContent` request over the already-built [contents].
     *
     * @param contents the request's `contents[]` turns
     * @param config sampling settings for this request
     * @param tools the tools to declare, or empty to stream plain text
     * @param callback receives tokens, tool calls, completion, and errors
     */
    private fun streamContents(
        contents: JSONArray,
        config: LlmConfig,
        tools: List<ToolDefinition>,
        callback: ToolStreamCallback
    ) {
        currentJob = scope.launch {
            val keyStamp = storedKeyStamp()
            try {
                val apiKey = readGeminiApiKey()
                    ?: run {
                        callback.onError("Gemini API key not configured")
                        return@launch
                    }

                val startTime = System.currentTimeMillis()
                val body = buildRequestJson(contents, config, tools)
                // `declared` counts what reached the body, not what was asked for: a schema
                // [buildRequestJson] had to drop falls back to text calls without saying so here.
                Log.i(
                    TAG,
                    "REQUEST | model=${getModelName()} turns=${contents.length()} " +
                        "tools=${tools.size} declared=${declaredToolCount(body)} " +
                        tools.joinToString(",") { it.name }
                )

                val fullText = StringBuilder()
                var chunkCount = 0
                var toolCallCount = 0
                // Last chunk's reason wins: only the final one carries why generation stopped.
                var finishReason: String? = null
                // Read outside the tagged block: `coroutineContext` is only reachable from suspend code.
                val requestJob = coroutineContext[Job]
                withTrafficTag(NetworkTags.INFERENCE) {
                    val conn = openConnection(getModelName(), METHOD_STREAM_GENERATE_CONTENT, sse = true, apiKey = apiKey)
                    val cancelHandle = requestJob?.invokeOnCompletion { cause ->
                        if (cause != null) conn.disconnect()
                    }
                    try {
                        writeBody(conn, body)
                        checkResponse(conn)
                        // SSE: each chunk arrives as a `data: {json}` line; parse text as it streams.
                        conn.inputStream.bufferedReader().useLines { lines ->
                            for (line in lines) {
                                ensureActive()
                                if (!line.startsWith("data:")) continue
                                val payload = line.substringAfter("data:").trim()
                                if (payload.isEmpty() || payload == "[DONE]") continue
                                // A malformed/non-JSON chunk must not abort the whole stream; skip it.
                                val chunk = runCatching { GeminiToolProtocol.parseChunk(JSONObject(payload)) }.getOrElse {
                                    Log.w(TAG, "CHUNK | skipped malformed SSE chunk: ${it.message}")
                                    GeminiToolProtocol.StreamChunk.EMPTY
                                }
                                chunk.finishReason?.let { finishReason = it }
                                if (chunk.text.isNotEmpty()) {
                                    chunkCount++
                                    fullText.append(chunk.text)
                                    callback.onToken(chunk.text)
                                }
                                for (call in chunk.calls) {
                                    toolCallCount++
                                    Log.i(
                                        TAG,
                                        "FUNCTION_CALL | tool=${call.name} " +
                                            "args=${call.args.orEmpty().keys.joinToString(",")}"
                                    )
                                    callback.onToolCall(call)
                                }
                            }
                        }
                    } finally {
                        cancelHandle?.dispose()
                        conn.disconnect()
                    }
                }

                val finalText = fullText.toString()
                Log.i(
                    TAG,
                    "STREAM | chars=${finalText.length} chunks=$chunkCount calls=$toolCallCount " +
                        "finish=$finishReason"
                )
                when {
                    // Truncation is why a request to write a whole file came back unusable, and it
                    // reads as an empty reply unless the reason is reported. Only when the cap left
                    // nothing behind: onError deletes the bubble, so reporting it for a long answer
                    // that ran out of room would throw away the prose it did produce.
                    toolCallCount == 0 && finalText.isBlank() &&
                        finishReason == FINISH_REASON_MAX_TOKENS ->
                        callback.onError(userMessage(GeminiFailure.ReplyTruncated))

                    toolCallCount == 0 && finalText.isBlank() ->
                        callback.onError("Empty response from Gemini API")

                    else -> {
                        val tokenCount = finalText.split("\\s+".toRegex()).size
                        callback.onComplete(
                            LlmResponse.success(finalText, tokenCount, System.currentTimeMillis() - startTime)
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                Log.e(TAG, "STREAM | failed: ${e.message}", e)
                callback.onError(formatErrorMessage(e, keyStamp))
            }
        }
    }

    override fun generateWithHistory(
        history: List<ChatMessage>,
        prompt: String,
        config: LlmConfig
    ): CompletableFuture<LlmResponse> {
        context.logger.info("GeminiBackend.generateWithHistory() called with ${history.size} messages")

        val future = CompletableFuture<LlmResponse>()

        currentJob = scope.launch {
            val keyStamp = storedKeyStamp()
            try {
                val apiKey = readGeminiApiKey()
                    ?: run {
                        future.complete(LlmResponse.failure("Gemini API key not configured"))
                        return@launch
                    }

                val startTime = System.currentTimeMillis()

                val contents = buildContents(history, prompt, config)

                val text = requestText(getModelName(), apiKey, buildRequestJson(contents, config))

                if (text.isBlank()) {
                    future.complete(LlmResponse.failure("Empty response from Gemini API"))
                } else {
                    val tokenCount = text.split("\\s+".toRegex()).size
                    context.logger.info("GeminiBackend: Generated ${text.length} chars with history, ~$tokenCount tokens")
                    future.complete(LlmResponse.success(text, tokenCount, System.currentTimeMillis() - startTime))
                }
            } catch (e: CancellationException) {
                future.cancel(true)
                throw e
            } catch (e: Exception) {
                context.logger.error("GeminiBackend: Error generating with history", e)
                future.complete(LlmResponse.failure(formatErrorMessage(e, keyStamp)))
            }
        }

        return future
    }

    /**
     * List the Gemini models currently available to the saved API key that support chat
     * (i.e. advertise the `generateContent` method). The result reflects the live v1beta
     * catalog, so any name returned here is safe to pass to [generate] / [generateStreaming]
     * without a 404 for a retired model.
     *
     * Returns an empty list when no key is configured and completes exceptionally on a
     * network/API failure, so the caller can fall back to a current-models-only list and
     * never advertise a dead model.
     */
    fun listModels(): CompletableFuture<List<String>> {
        val future = CompletableFuture<List<String>>()
        // close() cancels the scope, making launch a silent no-op; fail loudly instead, or the
        // gateway's blocking get() would sit at "Loading" for its full 60-second timeout.
        if (!scope.isActive) {
            future.completeExceptionally(IllegalStateException("Gemini backend is closed"))
            return future
        }

        val job = scope.launch {
            try {
                val key = readGeminiApiKey()
                if (key.isNullOrBlank()) {
                    context.logger.warn("GeminiBackend: no API key configured; cannot list live models")
                    future.complete(emptyList())
                    return@launch
                }
                val models = fetchAvailableModels(key)
                context.logger.info("GeminiBackend: ${models.size} models support $METHOD_GENERATE_CONTENT")
                future.complete(models)
            } catch (e: CancellationException) {
                future.cancel(true)
                throw e
            } catch (e: Exception) {
                context.logger.error("GeminiBackend: Error in listModels", e)
                future.completeExceptionally(e)
            }
        }
        future.cancelJobOnCancel(job)

        return future
    }

    /**
     * List the models a caller-supplied [apiKey] can use, instead of the one saved on disk.
     *
     * Lets the settings pane check a just-typed key *before* it is persisted; the no-arg [listModels]
     * reads the stored key. Nothing here touches the stored key or [keyCache].
     *
     * @param apiKey the candidate key to authenticate the request with; never logged
     * @return the chat-capable catalog for [apiKey], or a future completed exceptionally with the
     *   `ListModels HTTP <code>` [IOException] from [fetchAvailableModels] — the caller reads the
     *   status code out of that message to tell a refused key from an unreachable network
     */
    fun listModels(apiKey: String): CompletableFuture<List<String>> {
        val future = CompletableFuture<List<String>>()
        val key = apiKey.trim()
        if (key.isEmpty()) {
            future.completeExceptionally(IllegalArgumentException("Gemini API key is blank"))
            return future
        }
        // close() cancels the scope, making launch a silent no-op; fail loudly instead.
        if (!scope.isActive) {
            future.completeExceptionally(IllegalStateException("Gemini backend is closed"))
            return future
        }

        val job = scope.launch {
            try {
                val models = fetchAvailableModels(key)
                context.logger.info("GeminiBackend: candidate key lists ${models.size} chat models")
                future.complete(models)
            } catch (e: CancellationException) {
                future.cancel(true)
                throw e
            } catch (e: Exception) {
                context.logger.warn("GeminiBackend: candidate key check failed: ${e.message}")
                future.completeExceptionally(e)
            }
        }
        future.cancelJobOnCancel(job)

        return future
    }

    /**
     * Fetch and parse the ListModels catalog, following pagination, keeping only models that
     * support [METHOD_GENERATE_CONTENT]. Runs on the caller's (IO) coroutine, sockets tagged
     * [NetworkTags.CATALOG] across every page. The
     * `ListModels HTTP <code>` message is a cross-plugin contract — keep that shape if you reword.
     */
    private fun fetchAvailableModels(apiKey: String): List<String> {
        val names = mutableListOf<String>()
        withTrafficTag(NetworkTags.CATALOG) {
            var pageToken: String? = null

            do {
                val url = buildString {
                    append(MODELS_BASE_URL)
                    append("?pageSize=1000")
                    pageToken?.let { append("&pageToken=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
                }

                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    // Pass the API key as a header, never in the URL query string: query
                    // strings leak into logs, proxies, and crash reports.
                    setRequestProperty("x-goog-api-key", apiKey)
                }

                val body = try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        throw java.io.IOException("ListModels HTTP $code: $err")
                    }
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }

                val json = org.json.JSONObject(body)
                val models = json.optJSONArray("models")
                if (models != null) {
                    for (i in 0 until models.length()) {
                        val model = models.getJSONObject(i)
                        val methods = model.optJSONArray("supportedGenerationMethods") ?: continue
                        val supportsChat = (0 until methods.length())
                            .any { methods.optString(it) == METHOD_GENERATE_CONTENT }
                        if (!supportsChat) continue
                        val name = model.optString("name").removePrefix("models/")
                        if (name.isNotBlank()) names.add(name)
                    }
                }
                pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
            } while (pageToken != null)
        }

        return names.distinct()
    }

    /**
     * Build the full prompt including system instructions.
     */
    private fun buildPrompt(userPrompt: String, config: LlmConfig): String {
        val systemPrompt = config.systemPrompt ?: "You are a helpful coding assistant."
        return """$systemPrompt

User: $userPrompt"""
    }

    /**
     * Streams a reply for a multi-turn conversation, sending [history] as real `contents[]` turns.
     *
     * @param history the conversation so far, oldest first
     * @param prompt the current user turn
     * @param config sampling settings; its system prompt becomes the leading turn pair
     * @param callback receives tokens, completion, and errors
     */
    override fun generateStreamingWithHistory(
        history: List<ChatMessage>,
        prompt: String,
        config: LlmConfig,
        callback: StreamCallback
    ) {
        streamContents(buildContents(history, prompt, config), config, emptyList(), callback.asToolCallback())
    }

    /**
     * Streams a turn with [tools] declared to the API, reporting each `functionCall` part through
     * [ToolStreamCallback.onToolCall].
     *
     * This is the path the agent takes. Declaring the tools is what stops Gemini writing a call as
     * prose the caller has to parse back: the arguments arrive already structured, so a file whose
     * contents contain quotes or newlines can no longer break the call carrying it (ADFA-5410).
     *
     * @param prompt the current user turn
     * @param history the conversation so far, oldest first
     * @param config sampling settings; its system prompt becomes the leading turn pair
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
        streamContents(buildContents(history, prompt, config), config, tools, callback)
    }

    /** Cancel any in-flight generation (user pressed Stop). */
    override fun cancelStreaming() {
        currentJob?.cancel()
        currentJob = null
    }

    /**
     * Release all resources: cancel the backend scope and any in-flight
     * request. Called from [GeminiPlugin.dispose].
     *
     * [keyCache] holds the *decrypted* API key, so it is dropped here too — otherwise the
     * plaintext stays reachable on the host process heap for as long as the IDE runs, long
     * after the plugin was unloaded, which is exactly what encrypting at rest is meant to
     * prevent.
     */
    fun close() {
        currentJob?.cancel()
        scope.cancel()
        keyCache = null
    }

    /**
     * POST [body] to a model method and return the concatenated response text.
     *
     * The socket is tagged [NetworkTags.INFERENCE].
     *
     * @param model model name (without the `models/` prefix)
     * @param apiKey Gemini API key
     * @param body request payload built by [buildRequestJson]
     * @return the response text, or "" when the API returned no candidates/parts
     */
    private fun requestText(model: String, apiKey: String, body: JSONObject): String =
        withTrafficTag(NetworkTags.INFERENCE) {
            val conn = openConnection(model, METHOD_GENERATE_CONTENT, sse = false, apiKey = apiKey)
            try {
                writeBody(conn, body)
                checkResponse(conn)
                extractText(JSONObject(conn.inputStream.bufferedReader().use { it.readText() }))
            } finally {
                conn.disconnect()
            }
        }

    /**
     * Open a POST connection to `.../models/{model}:{method}`.
     *
     * @param sse when true, requests the server-sent-events stream (`?alt=sse`)
     * @return a configured, not-yet-written [HttpURLConnection]
     */
    private fun openConnection(
        model: String,
        method: String,
        sse: Boolean,
        apiKey: String,
    ): HttpURLConnection {
        val url = buildString {
            append(MODELS_BASE_URL).append('/').append(model).append(':').append(method)
            if (sse) append("?alt=sse")
        }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000  // generation can run for a while
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            // Header, not query string: keeps the key out of logs, proxies, and crash reports.
            setRequestProperty("x-goog-api-key", apiKey)
        }
    }

    /** Write [body] as the UTF-8 JSON request payload of [conn]. */
    private fun writeBody(conn: HttpURLConnection, body: JSONObject) {
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
    }

    /**
     * Throw an [IOException] carrying the API's error body on a non-2xx response, so
     * [formatErrorMessage] can turn it into a user-facing message.
     */
    private fun checkResponse(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IOException("Gemini HTTP $code: $err")
        }
        // Every request passes here, streaming or not, and a 2xx is the API accepting the key: any
        // recorded refusal describes a credential that is no longer the one in use. Cleared here
        // rather than on one success path, which left the pane accusing a key it had just proven.
        credentialFailures.clear()
    }

    /**
     * Build a generateContent request body.
     *
     * @param contents the `contents` array of role/parts turns
     * @param config supplies temperature and max output tokens
     * @param tools the tools to declare; omitted from the body when empty
     * @return the request JSON
     */
    private fun buildRequestJson(
        contents: JSONArray,
        config: LlmConfig,
        tools: List<ToolDefinition> = emptyList(),
    ): JSONObject {
        val body = JSONObject()
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", config.temperature.toDouble())
                    .put("maxOutputTokens", config.maxTokens)
            )
        if (tools.isEmpty()) return body
        // A schema this side cannot express must not cost the user the whole request: dropping the
        // declarations degrades to the text envelope the prompt still describes.
        val declarations = runCatching { GeminiToolProtocol.functionDeclarations(tools) }.getOrElse {
            Log.w(TAG, "REQUEST | could not declare tools, falling back to text calls", it)
            return body
        }
        return body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations)))
    }

    /**
     * How many function declarations [body] ended up carrying.
     *
     * Read back off the body rather than counted from the tool list, so a run whose declarations
     * were dropped is distinguishable in the trace from one that was never offered any tools.
     *
     * @param body a request built by [buildRequestJson]
     * @return the declaration count, or 0 when the body declares no tools
     */
    private fun declaredToolCount(body: JSONObject): Int =
        body.optJSONArray("tools")
            ?.optJSONObject(0)
            ?.optJSONArray("functionDeclarations")
            ?.length()
            ?: 0

    /**
     * Build a single `contents` turn.
     *
     * @param role Gemini role, "user" or "model"
     * @param text the turn's text part
     * @return a `{role, parts:[{text}]}` object
     */
    private fun contentJson(role: String, text: String): JSONObject =
        JSONObject()
            .put("role", role)
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

    /**
     * Adapts a plain stream callback to the tool-aware one [streamContents] takes.
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
     * Extract and concatenate the text parts of the first candidate.
     *
     * @param response a generateContent response (or a single stream chunk)
     * @return the concatenated text, or "" when there are no candidates/parts
     */
    private fun extractText(response: JSONObject): String {
        val candidates = response.optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""
        val parts = candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts") ?: return ""
        return buildString {
            for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text"))
        }
    }

    /**
     * Turn a failure into one user-facing sentence.
     *
     * [GeminiErrorFormatter] decides *what* went wrong; the wording comes from `strings.xml`. The
     * raw HTTP error body stays on the logged exception and must never reach the transcript.
     *
     * @param keyStamp when the key this request read was saved, so a refusal landing after a later
     *   save or clear is not reported against a credential that was never tried
     */
    private fun formatErrorMessage(e: Exception, keyStamp: Long): String {
        val failure = GeminiErrorFormatter.classify(e, getModelName())
        val message = userMessage(failure)
        // Only a credential failure is recorded: any other reason says nothing about the key, and
        // filing it as one would send the user off to replace a key that works.
        if (failure.isCredentialProblem) credentialFailures.record(failure, keyStamp)
        return message
    }

    /**
     * Resolve a [GeminiFailure] against the plugin's own resources.
     *
     * `context.androidContext` is plugin-scoped, so this plugin's string ids resolve here. A failed
     * lookup degrades to the generic message rather than throwing out of an error handler.
     */
    private fun userMessage(failure: GeminiFailure): String = try {
        val resources = context.androidContext
        when (failure) {
            is GeminiFailure.ModelUnavailable ->
                resources.getString(R.string.gemini_error_model_unavailable, failure.modelName)

            GeminiFailure.QuotaExceeded ->
                resources.getString(R.string.gemini_error_quota)

            // Through CredentialFailure, which is also what the settings pane resolves, so the
            // transcript and the pane cannot describe the same refusal differently.
            GeminiFailure.KeyRefused ->
                resources.getString(CredentialFailure.KeyRefused.messageRes)

            GeminiFailure.KeyInvalid ->
                resources.getString(CredentialFailure.KeyInvalid.messageRes)

            is GeminiFailure.RequestRejected -> failure.reason?.let {
                resources.getString(R.string.gemini_error_request_rejected_reason, it)
            } ?: resources.getString(R.string.gemini_error_request_rejected)

            is GeminiFailure.ServiceUnavailable ->
                resources.getString(R.string.gemini_error_service_unavailable, failure.httpStatus)

            is GeminiFailure.Unexpected -> failure.reason?.let {
                resources.getString(R.string.gemini_error_unexpected_reason, failure.httpStatus, it)
            } ?: resources.getString(R.string.gemini_error_unexpected, failure.httpStatus)

            GeminiFailure.Unreachable ->
                resources.getString(R.string.gemini_error_unreachable)

            GeminiFailure.ReplyTruncated ->
                resources.getString(R.string.gemini_error_truncated)

            is GeminiFailure.Failed -> failure.reason?.let {
                resources.getString(R.string.gemini_error_failed_reason, it)
            } ?: resources.getString(R.string.gemini_error_failed)
        }
    } catch (e: Exception) {
        context.logger.error("GeminiBackend: could not resolve error string for $failure", e)
        "The Gemini request failed."
    }
}

/**
 * Cancel [job] when this future is cancelled by its caller.
 *
 * [CompletableFuture.cancel] only flips the future's own state, so without this a caller that
 * gives up leaves the HTTP fetch running to completion for a result nobody will read.
 *
 * @param job the coroutine producing this future's value
 */
private fun <T> CompletableFuture<T>.cancelJobOnCancel(job: Job) {
    whenComplete { _, _ -> if (isCancelled) job.cancel() }
}
