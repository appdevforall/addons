package org.appdevforall.aichat

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.ActiveModelReportingBackend
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage
import com.itsaky.androidide.plugins.services.LlmInferenceService.FailureKind
import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmResponse
import com.itsaky.androidide.plugins.services.LlmInferenceService.StatusReportingBackend
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolCallRequest
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolStreamCallback
import com.itsaky.androidide.plugins.services.SharedServices
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * `window.CoGoChat`: the web UI's whole view of Code on the Go (see web/src/lib/bridge.ts).
 *
 * Every method runs on a WebView binder thread, takes and returns strings, and never blocks on
 * generation: [send] hands the request to AI Core and returns, and the reply streams back through
 * `window.__cogoChatEvent` on the main thread.
 *
 * Requests go to AI Core's [LlmInferenceService] router, never to a backend directly, so the chat
 * gets the same routing, capability fallback and "not ready" messages as AI Core's own Agent.
 */
class ChatBridge(
    private val context: PluginContext,
    private val webView: WebView,
) {
    private val main = Handler(Looper.getMainLooper())

    /** The request whose callbacks still count. Anything else is stale (cancelled or replaced). */
    private val active = AtomicReference<String?>(null)

    @Volatile
    private var detached = false

    private val threadsFile: File get() = File(context.getPluginFilesDir(), THREADS_FILE)

    fun detach() {
        detached = true
        active.getAndSet(null)?.let { runCatching { service()?.cancelGeneration() } }
    }

    private fun service(): LlmInferenceService? = try {
        SharedServices.get(LlmInferenceService::class.java)
            ?: context.getPluginService(AI_CORE_PLUGIN_ID, LlmInferenceService::class.java)
    } catch (e: Exception) {
        context.logger.warn("AI Chat: could not resolve LlmInferenceService: ${e.message}")
        null
    }

    @JavascriptInterface
    fun listBackends(): String {
        val out = JSONArray()
        val svc = service() ?: return out.toString()
        for (b in runCatching { svc.availableBackends }.getOrDefault(emptyList())) {
            val o = JSONObject()
            o.put("id", b.id)
            o.put("name", b.name)
            o.put("available", runCatching { b.isAvailable }.getOrDefault(false))
            if (b is ActiveModelReportingBackend) runCatching { b.activeModelName }.getOrNull()?.let { o.put("model", it) }
            if (b is StatusReportingBackend) runCatching { b.statusMessage }.getOrNull()?.takeIf { it.isNotBlank() }?.let { o.put("status", it) }
            out.put(o)
        }
        return out.toString()
    }

    @JavascriptInterface
    fun preferredBackend(): String = runCatching { service()?.preferredBackendId }.getOrNull().orEmpty()

    @JavascriptInterface
    fun send(requestId: String, backendId: String, historyJson: String, prompt: String, optionsJson: String) {
        val svc = service()
        if (svc == null) {
            emitError(requestId, "AI Core is not installed or not active. Install it from the Addons Manager.", FailureKind.CONFIGURATION)
            return
        }
        val history = try {
            parseHistory(historyJson)
        } catch (e: Exception) {
            emitError(requestId, "Could not read the conversation: ${e.message}", FailureKind.OTHER)
            return
        }
        val options = runCatching { JSONObject(optionsJson) }.getOrDefault(JSONObject())
        val config = LlmConfig(backendId).apply {
            if (options.has("temperature")) temperature = options.getDouble("temperature").toFloat()
            if (options.has("maxTokens")) maxTokens = options.getInt("maxTokens")
            options.optString("systemPrompt").takeIf { it.isNotBlank() }?.let { systemPrompt = it }
        }

        active.set(requestId)
        val callback = object : ToolStreamCallback {
            override fun onToken(token: String) {
                if (active.get() == requestId) emit(JSONObject().put("type", "token").put("requestId", requestId).put("text", token))
            }

            override fun onComplete(response: LlmResponse) {
                if (!active.compareAndSet(requestId, null)) return
                if (response.success) {
                    emit(
                        JSONObject().put("type", "done").put("requestId", requestId)
                            .put("text", response.text ?: "")
                            .put("tokens", response.tokensGenerated)
                            .put("timeMs", response.timeMs),
                    )
                } else {
                    emitError(requestId, response.error ?: "The model returned an error.", response.failureKind ?: FailureKind.OTHER)
                }
            }

            // No tools are offered, so a call here is a backend misbehaving: ignore it.
            override fun onToolCall(request: ToolCallRequest) {}

            override fun onError(error: String) = onError(error, FailureKind.OTHER)

            override fun onError(error: String, kind: FailureKind) {
                if (active.compareAndSet(requestId, null)) emitError(requestId, error, kind)
            }
        }
        try {
            // The router degrades by capability: tools, then history, then a bare prompt.
            svc.generateStreamingWithTools(prompt, history, config, emptyList(), callback)
        } catch (e: Exception) {
            context.logger.error("AI Chat: generation failed to start", e)
            if (active.compareAndSet(requestId, null)) emitError(requestId, e.message ?: e.javaClass.simpleName, FailureKind.OTHER)
        }
    }

    @JavascriptInterface
    fun cancel(requestId: String) {
        // The router cancels whatever is generating and may not call back, so answer here and
        // drop anything still in flight for this request.
        if (active.compareAndSet(requestId, null)) {
            runCatching { service()?.cancelGeneration() }
            emit(JSONObject().put("type", "error").put("requestId", requestId).put("message", "Stopped").put("kind", "CANCELLED"))
        }
    }

    @JavascriptInterface
    fun loadThreads(): String = runCatching { threadsFile.takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()

    @JavascriptInterface
    fun saveThreads(json: String) {
        // Write beside, then rename: a crash mid-write must not lose every chat.
        runCatching {
            val tmp = File(threadsFile.parentFile, "$THREADS_FILE.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(threadsFile)) {
                threadsFile.writeText(json)
                tmp.delete()
            }
        }.onFailure { context.logger.error("AI Chat: could not save chats", it) }
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (uri.scheme != "https" && uri.scheme != "http") return
        main.post {
            runCatching {
                webView.context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { context.logger.warn("AI Chat: no app to open $url") }
        }
    }

    @JavascriptInterface
    fun isDarkTheme(): Boolean {
        val mode = webView.context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun parseHistory(json: String): List<ChatMessage> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val role = when (o.getString("role")) {
                "assistant" -> ChatMessage.Role.ASSISTANT
                "system" -> ChatMessage.Role.SYSTEM
                else -> ChatMessage.Role.USER
            }
            ChatMessage(role, o.getString("content"))
        }
    }

    private fun emitError(requestId: String, message: String, kind: FailureKind) =
        emit(JSONObject().put("type", "error").put("requestId", requestId).put("message", message).put("kind", kind.name))

    private fun emit(event: JSONObject) {
        if (detached) return
        // JSONObject.quote makes the payload a safe JS string literal.
        val script = "window.__cogoChatEvent && window.__cogoChatEvent(${JSONObject.quote(event.toString())})"
        main.post { if (!detached) webView.evaluateJavascript(script, null) }
    }

    companion object {
        const val AI_CORE_PLUGIN_ID = "com.itsaky.androidide.plugins.aicore"
        private const val THREADS_FILE = "threads.json"
    }
}
