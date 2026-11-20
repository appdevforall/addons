package com.itsaky.androidide.plugins.aiagentmcp.transport

import android.util.Log
import com.itsaky.androidide.plugins.aiagentmcp.errors.runCatchingRecoverable
import com.itsaky.androidide.plugins.aiagentmcp.logging.LOG_PREFIX
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

private const val TAG = "$LOG_PREFIX.McpHttpClient"

/**
 * The HTTP transport this plugin speaks: one POST that answers with JSON or with an SSE stream
 * carrying the same JSON, and one DELETE that ends a session.
 *
 * [HttpURLConnection] rather than an SDK: plugins run in the host IDE's classloader, where
 * `okhttp3` resolves to the host's older OkHttp and an SDK bundling its own copy crashes with a
 * `NoSuchMethodError`. Kept apart from [com.itsaky.androidide.plugins.aiagentmcp.client.McpSession]
 * so the session is about the protocol, not about sockets.
 *
 * Open, and [post] / [deleteSession] with it, only so a test can answer the protocol without a
 * socket — which is what `McpSession`'s injectable transport promises.
 *
 * @param connectTimeoutMs how long to wait for the connection itself.
 * @param readTimeoutMs how long a call may take to answer.
 * @param drainDeadlineMs how long reading a 202 body may take in all; see [drain].
 */
open class McpHttpClient(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val drainDeadlineMs: Long = DRAIN_DEADLINE_MS,
) {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000

        /** A remote tool can take a while to run, so reading outlives connecting by a lot. */
        private const val READ_TIMEOUT_MS = 60_000

        /** Header carrying the server-assigned session, when the server keeps state. */
        const val HEADER_SESSION_ID = "Mcp-Session-Id"

        /** Header naming the negotiated revision, required from the 2025-06-18 revision on. */
        const val HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"

        private const val CONTENT_TYPE_SSE = "text/event-stream"

        /** Same-origin redirects followed before giving up, so a redirect loop cannot spin. */
        private const val MAX_REDIRECTS = 5

        /**
         * Characters of a drained body kept for the connection log: twice what the log shows, so
         * a credential the redactor has to find is whole even where the log's own cut falls. The
         * drain's own cut, at this length, can fall inside one; the trace masks what was kept of
         * it with [McpRedactor.scrubCut], so the log never shows the start of a credential.
         */
        internal const val DRAINED_CHARS_KEPT = 2 * McpExchange.Body.MAX_CHARS

        /**
         * Characters of a 202 body read and thrown away, past the ones kept, before the drain gives
         * up on it: about 1 MiB of ASCII. Far more than any server sends by mistake, and little
         * enough that a body which never ends costs a moment rather than the call.
         */
        internal const val DRAIN_BUDGET_CHARS = 1L shl 20

        /**
         * How long the drain of a 202 body may take in all, whatever the read timeout: far longer
         * than a server needs to send a body it should not have sent, short enough that one which
         * dribbles it cannot hold `initialize` for long.
         */
        internal const val DRAIN_DEADLINE_MS = 10_000L

        /**
         * Reads a body to its end through a fixed buffer, keeping only its first [keep] characters.
         *
         * For a 202, which should carry no body: one left unread keeps the socket out of the pool,
         * and a server that sends a huge one must not cost Code on the Go, whose process this
         * plugin runs in, its memory. Nor its time: a body that runs on past [budget] dropped
         * characters is abandoned as a failure. A server streaming one forever would otherwise hold
         * the read, and with it `initialize`, which is synchronized, and every health check behind
         * it. A read that fails with an Exception or a LinkageError is returned as a failure too.
         * The caller survives either by not reusing the socket; any other Error, an OutOfMemoryError
         * above all, propagates.
         *
         * The budget bounds how much is read; [deadlineMs] bounds how long it takes. The clock is
         * checked between reads, so a server that sends a byte just under every read timeout, which
         * never trips that timeout, is abandoned at the deadline like one past the budget. The worst
         * case is [deadlineMs] plus one read timeout: a read already blocked when the deadline
         * passes ends only at its own timeout.
         *
         * Only a 202 is bounded this way. A JSON reply and an error body are read whole, with
         * `readText()`: each is the document the caller needs.
         *
         * What is kept is cut at [keep] characters wherever that falls, inside a credential the
         * server echoed too; [McpExchangeTrace.received] masks the end of a cut body for that.
         *
         * @param keep how many characters to keep.
         * @param budget how many characters past [keep] may be read and dropped before the body is
         *   abandoned.
         * @param deadlineMs how long the whole drain may take before the body is abandoned.
         * @param nowMs the clock, in milliseconds from any fixed point; injectable so a test needs
         *   no real waiting.
         * @param open opens the body; called inside the guard, since opening it can fail too.
         * @return what was kept and how much was dropped, or the failure: a recoverable read
         *   failure, or a [DrainBudgetException] for a body that ran past [budget] or [deadlineMs].
         */
        internal fun drain(
            keep: Int = DRAINED_CHARS_KEPT,
            budget: Long = DRAIN_BUDGET_CHARS,
            deadlineMs: Long = DRAIN_DEADLINE_MS,
            nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
            open: () -> InputStream,
        ): Result<Drained> =
            runCatchingRecoverable {
                val started = nowMs()
                open().reader(Charsets.UTF_8).use { reader ->
                    val kept = StringBuilder()
                    val buffer = CharArray(8 * 1024)
                    var dropped = 0L
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        val room = (keep - kept.length).coerceIn(0, read)
                        kept.append(buffer, 0, room)
                        dropped += read - room
                        if (dropped > budget) {
                            throw DrainBudgetException(
                                "A 202 body ran past ${kept.length + budget} characters; abandoned it"
                            )
                        }
                        if (nowMs() - started > deadlineMs) {
                            throw DrainBudgetException(
                                "A 202 body took more than $deadlineMs ms to read; abandoned it"
                            )
                        }
                    }
                    Drained(kept.toString(), dropped)
                }
            }
    }

    /**
     * What [drain] read.
     * @property text the body's first characters.
     * @property dropped how many characters came after [text] and were read and thrown away.
     */
    internal class Drained(val text: String, val dropped: Long)

    /** A 202 body that ran past [DRAIN_BUDGET_CHARS] or [DRAIN_DEADLINE_MS]; its socket is disconnected, not reused. */
    internal class DrainBudgetException(message: String) : IOException(message)

    /**
     * One answer to a POST.
     * @property document the JSON-RPC document the server replied with, or null for `202 Accepted`,
     *   which is what a notification gets.
     * @property sessionId the session the server assigned or confirmed, when it sent one.
     */
    data class Response(val document: String?, val sessionId: String?)

    /**
     * POSTs [body] and reads whichever answer shape the server chose.
     *
     * @param url the server's MCP endpoint.
     * @param token bearer token, or blank for a server that needs none.
     * @param body the JSON-RPC envelope to send.
     * @param sessionId the session to continue, or null before one exists.
     * @param protocolVersion the negotiated revision, or null before `initialize` has answered.
     * @param onConnected receives the live connection, so a caller can disconnect it to cancel.
     * @param exchangeListener receives each request made and what came back, redirects included.
     *   An Exception or a LinkageError it throws is dropped; any other Error propagates.
     * @return the reply document and any session id the server sent.
     * @throws McpHttpException on a non-2xx answer, carrying the server's error body.
     */
    open fun post(
        url: String,
        token: String,
        body: JSONObject,
        sessionId: String? = null,
        protocolVersion: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        onConnected: (HttpURLConnection) -> Unit = {},
        exchangeListener: McpExchangeListener = McpExchangeListener.NONE,
    ): Response {
        val payload = body.toString()
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val userHeaders = McpHeaders.sanitize(extraHeaders)
        // Both are advertised because the server picks, and offering one earns 406 from some.
        val headers = headersFor(token, userHeaders, sessionId, protocolVersion) +
            listOf("Content-Type" to "application/json", "Accept" to "application/json, $CONTENT_TYPE_SSE")
        val redactor =
            McpRedactor.forRequest(url, listOfNotNull(token, sessionId) + userHeaders.values, userHeaders.keys)
        val credential = McpCredential.of(token, userHeaders)
        var target = url
        var hops = 0
        while (true) {
            val conn = open(target, "POST", token, headers).apply {
                readTimeout = readTimeoutMs
                doOutput = true
            }
            val trace = McpExchangeTrace("POST", target, headers, payload, bytes.size, redactor)
            onConnected(conn)
            // Only a socket that cannot be reused is disconnected: `disconnect()` evicts it from
            // the pool, so doing it on the success path made every call — initialize, each
            // tools/list page, each tools/call — pay a fresh TCP and TLS handshake.
            var reusable = false
            var failure: Throwable? = null
            val redirect = try {
                conn.outputStream.use { it.write(bytes) }
                trace.answered(conn)
                val next = conn.redirectTargetFrom(target)
                if (next == null) {
                    conn.failIfNotOk(trace, credential)

                    val assignedSession =
                        conn.getHeaderField(HEADER_SESSION_ID)?.takeIf { it.isNotBlank() }
                    // 202 with no body is the correct answer to a notification; drained rather
                    // than ignored, since a body left unread keeps the socket out of the pool.
                    if (conn.responseCode == HttpURLConnection.HTTP_ACCEPTED) {
                        // A half-read body is neither pooled nor closed, so only a whole drain counts.
                        val drained = drain(deadlineMs = drainDeadlineMs) { conn.inputStream }
                        drained.getOrNull()?.let { trace.received(it.text, it.dropped) }
                        drained.exceptionOrNull()?.let { e ->
                            Log.w(TAG, "Not reusing the connection after its 202 body: ${e.message}")
                        }
                        reusable = drained.isSuccess
                        return Response(null, assignedSession)
                    }

                    val streaming =
                        conn.contentType.orEmpty().contains(CONTENT_TYPE_SSE, ignoreCase = true)
                    val document = conn.inputStream.bufferedReader().use { reader ->
                        if (streaming) {
                            readFirstSseDocument(reader, trace::received)
                        } else {
                            reader.readText().also(trace::received)
                        }
                    }
                    // A JSON reply was read to EOF and its socket can serve the next call; an SSE
                    // stream is abandoned after its first event, so that one is never reusable.
                    reusable = !streaming
                    return Response(document, assignedSession)
                }
                next
            } catch (e: Throwable) {
                failure = e
                throw e
            } finally {
                if (!reusable) conn.disconnect()
                // The connection log is a convenience: a listener failing with an Exception or a
                // LinkageError costs only its record. Any other Error, an OutOfMemoryError above
                // all, is the process failing and propagates.
                runCatchingRecoverable { exchangeListener.onExchange(trace.toExchange(failure), redactor) }
            }

            if (++hops > MAX_REDIRECTS) {
                throw McpRedirectException("More than $MAX_REDIRECTS redirects from $url.")
            }
            Log.d(TAG, "Following a same-origin redirect")
            target = redirect
        }
    }

    /**
     * Ends a server-side session, best effort.
     *
     * A server that keeps no session answers 405 and a stale one answers 404; both mean the same
     * thing here — there is nothing left to close — so neither is worth surfacing.
     *
     * @param url the server's MCP endpoint.
     * @param token bearer token, or blank.
     * @param sessionId the session to end.
     */
    open fun deleteSession(
        url: String,
        token: String,
        sessionId: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val headers = headersFor(token, McpHeaders.sanitize(extraHeaders), sessionId, null)
        val conn = open(url, "DELETE", token, headers)
        try {
            Log.d(TAG, "Closing session, server answered ${conn.responseCode}")
        } catch (e: Exception) {
            Log.d(TAG, "Could not close the session: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Reads an SSE body until the first complete event, whose data is the JSON-RPC reply.
     *
     * Everything after it is dropped: this client has one call in flight and never subscribes to
     * the server-initiated stream, so a server that keeps the connection open must not keep the
     * caller waiting on it.
     *
     * @param reader the response body.
     * @param onRaw receives the stream's lines as read, for the exchange record.
     * @return the reply document, or an empty string when the stream ended without one.
     */
    private fun readFirstSseDocument(reader: BufferedReader, onRaw: (String) -> Unit): String {
        val raw = StringBuilder()
        val payload = StringBuilder()
        try {
            while (true) {
                val line = reader.readLine() ?: break
                raw.append(line).append('\n')
                when (val event = SseChunk.parse(line)) {
                    is SseChunk.Event.Data -> payload.append(event.payload)
                    is SseChunk.Event.Named -> Log.d(TAG, "SSE event '${event.name}'")
                    SseChunk.Event.Dispatch -> if (payload.isNotEmpty()) return payload.toString()
                    SseChunk.Event.Ignored -> Unit
                }
            }
            return payload.toString()
        } finally {
            onRaw(raw.toString())
        }
    }

    /**
     * The headers one request carries, in order; a later name replaces an earlier one. The token
     * is a header, never a query string, which leaks into logs; the session and protocol headers
     * come after the user's, so a user header cannot displace them.
     *
     * @param userHeaders the user's own headers, already sanitised.
     * @return the name/value pairs, which [open] applies and the exchange record shows.
     */
    private fun headersFor(
        token: String,
        userHeaders: Map<String, String>,
        sessionId: String?,
        protocolVersion: String?,
    ): List<Pair<String, String>> {
        val byName = LinkedHashMap<String, Pair<String, String>>()
        fun set(name: String, value: String) {
            byName[name.lowercase()] = name to value
        }
        if (token.isNotBlank()) set("Authorization", "Bearer $token")
        userHeaders.forEach { (name, value) -> set(name, value) }
        sessionId?.let { set(HEADER_SESSION_ID, it) }
        protocolVersion?.let { set(HEADER_PROTOCOL_VERSION, it) }
        return byName.values.toList()
    }

    /**
     * Opens a connection carrying [headers]; the read timeout starts at the connect budget. The
     * token is checked first: a CR or LF in it would forge a second header, and Save refuses one,
     * so reaching this means a value that predates that check.
     *
     * A cleartext URL off this device is refused here too, not only on Save: a server saved before
     * that check existed would otherwise keep sending its session and credentials in the clear.
     *
     * @throws IOException when the token cannot be put in a header.
     * @throws McpCleartextException when [url] is `http://` to a server not on this device.
     */
    private fun open(
        url: String,
        method: String,
        token: String,
        headers: List<Pair<String, String>>,
    ): HttpURLConnection {
        if (McpUrlPolicy.verdict(url) == McpUrlPolicy.Verdict.CleartextOffDevice) {
            throw McpCleartextException("Refused an http:// URL to a server not on this device.")
        }
        if (token.isNotBlank() && !McpHeaders.isSendableToken(token)) {
            throw IOException("The stored token cannot be sent: it contains a line break or control character.")
        }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = connectTimeoutMs
            instanceFollowRedirects = false
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
    }

    /**
     * Where a 3xx wants this request to go, when repeating it there is safe.
     *
     * The method is kept rather than degraded to a GET as 301/302/303 prescribe: the body is the
     * JSON-RPC call, and dropping it surfaces as "the server sent no reply" rather than as anything
     * a reader could act on. Which destinations are safe is [McpRedirects]' decision.
     *
     * @param current the URL this request was sent to, for resolving a relative `Location`.
     * @return the URL to repeat the request at, or null when this is not a redirect.
     * @throws McpRedirectException when the redirect cannot be followed safely.
     */
    private fun HttpURLConnection.redirectTargetFrom(current: String): String? =
        when (val verdict = McpRedirects.verdict(responseCode, current, getHeaderField("Location"))) {
            McpRedirects.Verdict.NotARedirect -> null
            is McpRedirects.Verdict.Follow -> verdict.url
            McpRedirects.Verdict.OtherOrigin -> throw McpRedirectException(
                "$current redirected off its own origin; the request carries credentials."
            )
            McpRedirects.Verdict.Unusable ->
                throw McpRedirectException("$current sent a redirect with no usable destination.")
        }

    /**
     * Fails with the server's error body attached, so the status reaches its readers as a number
     * rather than as text they have to match.
     */
    private fun HttpURLConnection.failIfNotOk(trace: McpExchangeTrace, credential: McpCredential) {
        val code = responseCode
        if (code !in 200..299) {
            val body = errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            trace.received(body)
            throw McpHttpException(code, body, credential)
        }
    }
}
