package com.itsaky.androidide.plugins.aiagentopenai.backend

import com.itsaky.androidide.plugins.aiagentopenai.errors.OpenAiHttpException
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * The HTTP transport this backend speaks: one POST that streams or does not, and one GET.
 *
 * [HttpURLConnection] rather than an SDK: plugins run in the host IDE's classloader, where
 * `okhttp3` resolves to the host's older OkHttp and an SDK bundling its own copy crashes with a
 * NoSuchMethodError. Kept apart from the backend so the backend is about generating, not sockets.
 *
 * Both entry points tag their sockets ([NetworkTags]): the host's debug builds install
 * `StrictMode.VmPolicy.detectAll()`, and an untagged socket trips `detectUntaggedSockets()` from
 * inside plugin code.
 *
 * @param connectTimeoutMs how long to wait for the connection itself
 * @param readTimeoutMs how long a generation may take to answer
 */
internal class OpenAiHttpClient(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000

        /** Generation can run for a while, so the read timeout is far longer than the connect. */
        private const val READ_TIMEOUT_MS = 60_000
    }

    /**
     * POST [body] to [url] and hand the response's reader to [readResponse].
     *
     * The connection is closed before this returns, whatever [readResponse] did with it. Its
     * socket is tagged [NetworkTags.INFERENCE].
     *
     * @param apiKey bearer token, or blank for a server that needs none
     * @param sse true to ask for the server-sent-events stream
     * @param onConnected receives the live connection, so a caller can disconnect it on cancellation
     * @param onAccepted called once the status line says 2xx, before a byte of the body is read
     * @return whatever [readResponse] produced
     * @throws OpenAiHttpException on a non-2xx answer, carrying the server's error body
     */
    fun <T> post(
        url: String,
        apiKey: String,
        body: JSONObject,
        sse: Boolean = false,
        onConnected: (HttpURLConnection) -> Unit = {},
        onAccepted: () -> Unit = {},
        readResponse: (BufferedReader) -> T,
    ): T = withTrafficTag(NetworkTags.INFERENCE) {
        val conn = open(url, "POST", apiKey).apply {
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
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
     * @param apiKey bearer token, or blank for a server that needs none
     * @throws OpenAiHttpException on a non-2xx answer, carrying the server's error body
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
     * Open a connection carrying the bearer token, when there is one.
     *
     * A header, never a query string: query strings leak into logs, proxies and crash reports. A
     * blank key sends no header at all, which is what a local server expects. The read timeout
     * starts at the connect budget; only a generation raises it.
     */
    private fun open(url: String, method: String, apiKey: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = connectTimeoutMs
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }

    /**
     * Fail with the server's error body attached, so the status reaches its readers as a number
     * rather than as text they have to match.
     */
    private fun HttpURLConnection.failIfNotOk() {
        val code = responseCode
        if (code !in 200..299) {
            val body = errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw OpenAiHttpException(code, body)
        }
    }
}
