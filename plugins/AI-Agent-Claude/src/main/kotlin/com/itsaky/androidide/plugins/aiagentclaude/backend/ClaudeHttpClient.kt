package com.itsaky.androidide.plugins.aiagentclaude.backend

import com.itsaky.androidide.plugins.aiagentclaude.errors.ClaudeHttpException
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * The HTTP transport this backend speaks: one POST that streams or does not, and one GET.
 *
 * [HttpURLConnection] rather than Anthropic's Java SDK: plugins run in the host IDE's classloader,
 * which resolves `okhttp3` to the host's older OkHttp first, and an SDK bundling its own copy
 * crashes with a NoSuchMethodError. Kept apart from the backend so the backend is about
 * generating, not sockets.
 *
 * Both entry points tag their sockets ([NetworkTags]): the host's debug builds install
 * `StrictMode.VmPolicy.detectAll()`, and an untagged socket trips `detectUntaggedSockets()` from
 * inside plugin code.
 *
 * @param connectTimeoutMs how long to wait for the connection itself
 */
internal class ClaudeHttpClient(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
) {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000

        /**
         * Longest silence a stream may hold. The API sends `ping` events while the model thinks,
         * so this bounds a dead connection, not a slow turn.
         */
        const val STREAM_READ_TIMEOUT_MS = 120_000

        /** A turn that is not streamed sends nothing until it is done, thinking included. */
        const val BLOCKING_READ_TIMEOUT_MS = 600_000

        /** The Messages API version every request names. */
        private const val API_VERSION = "2023-06-01"
    }

    /**
     * POST [body] to [url] and hand the response's reader to [readResponse].
     *
     * The connection is closed before this returns, whatever [readResponse] did with it.
     *
     * @param apiKey the Claude API key; sent as `x-api-key`, never in the URL
     * @param betas `anthropic-beta` values the body's parameters need
     * @param sse true to ask for the server-sent-events stream
     * @param readTimeoutMs how long the response may stay silent
     * @param tag the [NetworkTags] value to tag this request's socket with
     * @param onConnected receives the live connection, so a caller can disconnect it on cancellation
     * @param onAccepted called once the status line says 2xx, before a byte of the body is read
     * @return whatever [readResponse] produced
     * @throws ClaudeHttpException on a non-2xx answer, carrying the API's error body
     */
    fun <T> post(
        url: String,
        apiKey: String,
        body: JSONObject,
        betas: List<String> = emptyList(),
        sse: Boolean = false,
        readTimeoutMs: Int = BLOCKING_READ_TIMEOUT_MS,
        tag: Int = NetworkTags.INFERENCE,
        onConnected: (HttpURLConnection) -> Unit = {},
        onAccepted: () -> Unit = {},
        readResponse: (BufferedReader) -> T,
    ): T = withTrafficTag(tag) {
        val conn = open(url, "POST", apiKey).apply {
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (betas.isNotEmpty()) setRequestProperty("anthropic-beta", betas.joinToString(","))
            if (sse) setRequestProperty("Accept", "text/event-stream")
        }
        onConnected(conn)
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            conn.failIfNotOk()
            onAccepted()
            conn.inputStream.bufferedReader().use(readResponse)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * GET [url] and return its response body, over a socket tagged [NetworkTags.CATALOG].
     *
     * @param apiKey the Claude API key
     * @throws ClaudeHttpException on a non-2xx answer, carrying the API's error body
     */
    fun get(url: String, apiKey: String): String = withTrafficTag(NetworkTags.CATALOG) {
        val conn = open(url, "GET", apiKey)
        try {
            conn.failIfNotOk()
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Open a connection carrying the key and the API version. A blank key sends no header, so
     * the API answers 401 and the caller reports a missing key rather than a refused one. The read
     * timeout starts at the connect budget; only a generation raises it.
     */
    private fun open(url: String, method: String, apiKey: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = connectTimeoutMs
            setRequestProperty("anthropic-version", API_VERSION)
            if (apiKey.isNotBlank()) setRequestProperty("x-api-key", apiKey)
        }

    /**
     * Fail with the API's error body attached, so the status reaches its readers as a number
     * rather than as text they have to match.
     */
    private fun HttpURLConnection.failIfNotOk() {
        val code = responseCode
        if (code !in 200..299) {
            val body = errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val retryAfter = getHeaderField("retry-after")?.trim()?.toLongOrNull()
            throw ClaudeHttpException(code, body, retryAfter)
        }
    }
}
