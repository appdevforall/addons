package com.itsaky.androidide.plugins.aiagentmcp.client

import android.util.Log
import com.itsaky.androidide.plugins.aiagentmcp.debugger.ReadOnlyTrust
import com.itsaky.androidide.plugins.aiagentmcp.errors.runCatchingRecoverable
import com.itsaky.androidide.plugins.aiagentmcp.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aiagentmcp.transport.JsonRpc
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpExchangeListener
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpHttpClient
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpHttpException
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "$LOG_PREFIX.McpSession"

/**
 * One client connection to one MCP server: initialize, list tools, call a tool.
 *
 * Every call is blocking and belongs off the main thread; the callers are a background executor
 * (tool invocation) and the settings pane's IO dispatcher (Connect).
 *
 * The session is shared: every tool call to its server, the health probe's listing and the
 * relisting of a SQLite debugger entry use it, at the same time. So it holds no connection of its
 * own for a cancel to drop; a tool call brings an [McpCallHandle], and only that call's requests
 * are published to it.
 *
 * @param endpoint the server's MCP URL.
 * @param credentials read once per request; see [McpCredentials] for why they are not held here.
 * @param http the transport; injectable so the protocol can be exercised without a socket.
 */
class McpSession(
    private val endpoint: String,
    private val credentials: () -> McpCredentials,
    private val http: McpHttpClient = McpHttpClient(),
) {

    companion object {
        /** The revision this client implements and asks for; a server may negotiate one down. */
        const val PREFERRED_PROTOCOL_VERSION = "2025-06-18"

        /**
         * From this revision on the transport is stateless: the server assigns no session and
         * expects no lifecycle notification, so sending one earns a 4xx rather than a 202.
         */
        const val STATELESS_FROM_VERSION = "2026-07-28"

        /**
         * MCP revisions are ISO dates, which compare correctly as strings. Anything else — a
         * semantic version, a server's own label — does not, so it is not compared at all.
         */
        private val DATED_VERSION = Regex("""\d{4}-\d{2}-\d{2}""")

        /** The handshake method, named because the retry below has to exclude it. */
        private const val METHOD_INITIALIZE = "initialize"

        private const val CLIENT_NAME = "CodeOnTheGo"
        private const val CLIENT_VERSION = "1.0.0"

        /** `tools/list` pages followed before giving up, so a huge server cannot loop forever. */
        private const val MAX_TOOL_PAGES = 10

        /** Tools kept from one server; the agent's prompt budget caps far lower than this. */
        private const val MAX_TOOLS = 200
    }

    private val nextId = AtomicLong(1)

    @Volatile private var sessionId: String? = null

    @Volatile private var negotiatedVersion: String? = null

    @Volatile private var initialized = false

    /** Who is watching the exchanges made on each thread; see [recording]. */
    private val recorder = ThreadLocal<McpExchangeListener?>()

    /** What the server called itself in `initialize`, for the settings pane's verdict. */
    @Volatile var serverName: String? = null
        private set

    /**
     * Performs the MCP handshake, unless it has already been performed.
     *
     * @throws IOException when the server cannot be reached or refuses the handshake.
     */
    fun initialize() = initialize(handle = null)

    /**
     * As [initialize], with the `initialize` request published to [handle], so a call cancelled
     * while it waits on the handshake stops waiting. A cancelled handshake is not recorded, and the
     * next caller repeats it.
     *
     * `notifications/initialized` is sent without the handle: once the server has answered, the
     * handshake is finished for every caller, and a session marked initialized whose server was
     * never told so would have each later request answered "not initialized".
     *
     * @param handle the calling tool call's, or null for a listing.
     */
    @Synchronized
    private fun initialize(handle: McpCallHandle?) {
        if (initialized) return

        val params = JSONObject().apply {
            put("protocolVersion", PREFERRED_PROTOCOL_VERSION)
            put("capabilities", JSONObject())
            put(
                "clientInfo",
                JSONObject().apply {
                    put("name", CLIENT_NAME)
                    put("version", CLIENT_VERSION)
                }
            )
        }

        val result = call(METHOD_INITIALIZE, params, handle)
        negotiatedVersion = result.optString("protocolVersion").takeIf { it.isNotBlank() }
            ?: PREFERRED_PROTOCOL_VERSION
        serverName = result.optJSONObject("serverInfo")?.optString("name")?.takeIf { it.isNotBlank() }
        initialized = true
        Log.i(TAG, "Initialized $endpoint (revision $negotiatedVersion, session=${sessionId != null})")

        if (!isStateless()) notifyInitialized()
    }

    /**
     * Every tool the server advertises, following `nextCursor` pagination.
     *
     * @return the tools, capped at [MAX_TOOLS].
     * @throws IOException when the server cannot be reached or answers an error.
     */
    fun listTools(): List<McpTool> {
        initialize()

        val tools = mutableListOf<McpTool>()
        var cursor: String? = null
        var page = 0
        do {
            val params = cursor?.let { JSONObject().put("cursor", it) }
            val result = call("tools/list", params)
            result.optJSONArray("tools")?.let { tools += toolsFrom(it) }
            cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
            page++
        } while (cursor != null && page < MAX_TOOL_PAGES && tools.size < MAX_TOOLS)

        if (cursor != null) {
            Log.w(TAG, "Stopped listing tools after $page page(s); the server has more")
        }
        return tools.take(MAX_TOOLS)
    }

    /**
     * Runs one tool.
     *
     * A tool that fails is not an exception: MCP reports it as a normal reply with `isError`, and
     * the agent shows the model that text so it can try something else.
     *
     * @param name the tool's own name, as the server listed it.
     * @param arguments the call arguments.
     * @param handle this call's own, so a cancel stops this call's requests and no one else's;
     *   null for a call nothing will cancel.
     * @return the outcome, successful or not.
     * @throws IOException when the server cannot be reached.
     * @throws java.io.InterruptedIOException when [handle] was cancelled; a request not yet sent
     *   is not sent.
     */
    fun callTool(
        name: String,
        arguments: Map<String, Any?>,
        handle: McpCallHandle? = null,
    ): McpCallResult {
        initialize(handle)

        val params = JSONObject().apply {
            put("name", name)
            put("arguments", JSONObject(arguments.filterValues { it != null }))
        }

        val result = try {
            call("tools/call", params, handle)
        } catch (e: McpProtocolException) {
            // A protocol-level error for a single call is the tool failing, not the session dying.
            return McpCallResult(false, "", e.message ?: "The server rejected the call.")
        }

        val text = flattenContent(result.optJSONArray("content"))
        return if (result.optBoolean("isError", false)) {
            McpCallResult(false, text, text.takeIf { it.isNotBlank() } ?: "The tool reported an error.")
        } else {
            McpCallResult(true, text)
        }
    }

    /**
     * Runs [block], handing [listener] every HTTP exchange [block] makes on this thread. A tool
     * call another thread makes meanwhile is not the caller's, and may carry project code.
     *
     * @param listener receives each exchange as it ends.
     * @param block the work to watch; blocking, so it stays on the calling thread.
     * @return what [block] returned.
     */
    fun <T> recording(listener: McpExchangeListener, block: () -> T): T {
        val outer = recorder.get()
        recorder.set(listener)
        return try {
            block()
        } finally {
            // Removed rather than set to null: the thread goes back to a pool and outlives this.
            if (outer == null) recorder.remove() else recorder.set(outer)
        }
    }

    /**
     * Ends the server-side session, if there is one, and forgets the handshake.
     *
     * The DELETE is best effort; its credentials come from the host's Keystore, so a LinkageError
     * is survived as well as an Exception, and any other Error propagates.
     */
    fun close() = close(credentials)

    /**
     * As [close], with credentials read earlier: for a session taken out of use while they could
     * still be read, and ended once they may have gone with the server, as when a SQLite debugger
     * entry is removed or the plugin deactivates. Read at the DELETE, they would be empty, and the
     * server would refuse it and keep the session open.
     *
     * @param saved what the DELETE carries.
     */
    fun close(saved: McpCredentials) = close { saved }

    @Synchronized
    private fun close(read: () -> McpCredentials) {
        sessionId?.let { id ->
            runCatchingRecoverable {
                val current = read()
                http.deleteSession(endpoint, current.token, id, current.headers)
            }
        }
        sessionId = null
        initialized = false
        negotiatedVersion = null
    }

    /**
     * Whether the negotiated revision keeps no server-side session.
     *
     * The revision alone decides, never the presence of an `Mcp-Session-Id`: session management is
     * *optional* in every revision this client asks for, while `notifications/initialized` is not.
     * Reading an absent session header as "stateless" is what left a conforming server without the
     * notification, so its next `tools/list` came back "not initialized" and the user saw an empty
     * tool list with no error.
     *
     * A server reporting an unorderable revision — a semantic version, its own label — is treated
     * as stateful, which costs at worst one notification a stateless server answers with a 4xx.
     */
    private fun isStateless(): Boolean {
        val version = negotiatedVersion ?: return false
        return DATED_VERSION.matches(version) && version >= STATELESS_FROM_VERSION
    }

    /** Tells a stateful server the handshake is complete; failure here is not fatal. */
    private fun notifyInitialized() {
        try {
            send(JsonRpc.notification("notifications/initialized"))
        } catch (e: IOException) {
            Log.d(TAG, "Server did not accept notifications/initialized: ${e.message}")
        }
    }

    /**
     * Sends one request and unwraps its reply, re-initializing once if the session expired.
     *
     * @param method the MCP method.
     * @param params its parameters, or null.
     * @param handle the calling tool call's, or null for a listing.
     * @return the `result` object.
     * @throws McpProtocolException when the server answers a JSON-RPC error.
     * @throws IOException on transport failure or a reply that is not one.
     */
    private fun call(
        method: String,
        params: JSONObject? = null,
        handle: McpCallHandle? = null,
    ): JSONObject {
        val envelope = JsonRpc.request(nextId.getAndIncrement().toString(), method, params)

        val response = try {
            send(envelope, handle)
        } catch (e: McpHttpException) {
            val retryable = e.statusCode == HttpURLConnection.HTTP_NOT_FOUND &&
                sessionId != null &&
                method != METHOD_INITIALIZE
            if (retryable) {
                Log.i(TAG, "Session expired; re-initializing before retrying $method")
                sessionId = null
                initialized = false
                initialize(handle)
                send(envelope, handle)
            } else {
                throw e
            }
        }

        val document = response.document
            ?: throw IOException("The server accepted '$method' but sent no reply.")

        val reply = try {
            JsonRpc.parseReply(document)
        } catch (e: JsonRpc.UnparsedReplyException) {
            // Refused rather than parsed, so the call or listing fails with the rule the reply broke
            // instead of a StackOverflowError ending Code on the Go.
            throw IOException("The server's reply to '$method' ${e.message}; it was not read.", e)
        } catch (e: Exception) {
            throw IOException("The server's reply to '$method' was not JSON-RPC: ${e.message}")
        } ?: throw IOException("The server's reply to '$method' carried no result.")

        if (reply.isError) {
            throw McpProtocolException(reply.errorCode ?: 0, reply.errorMessage.orEmpty())
        }
        return reply.result ?: JSONObject()
    }

    /**
     * POSTs one envelope.
     *
     * @param handle the calling tool call's: its connection is published there before anything is
     *   sent, and the request is refused if the call was cancelled meanwhile, while the credentials
     *   were read for instance, or after that, before the connection was connected or while the
     *   body was written. Null for a listing, which no call's cancel reaches.
     */
    private fun send(envelope: JSONObject, handle: McpCallHandle? = null): McpHttpClient.Response {
        // Read here rather than held on the session, so the plaintext lives for one request.
        val current = credentials()
        // Forgotten in a finally, so a late cancel does not disconnect a connection whose request
        // has ended and whose socket may be back in the pool.
        val response = try {
            http.post(
                url = endpoint,
                token = current.token,
                body = envelope,
                sessionId = sessionId,
                protocolVersion = negotiatedVersion,
                extraHeaders = current.headers,
                onConnected = { conn -> handle?.attach(conn) },
                beforeSend = { handle?.throwIfCancelled() },
                exchangeListener = recorder.get() ?: McpExchangeListener.NONE,
            )
        } finally {
            handle?.detach()
        }
        response.sessionId?.let { sessionId = it }
        return response
    }

    /**
     * Reads the tools out of one `tools/list` page.
     * @param array the `tools` array.
     * @return the tools it described, skipping any entry with no name.
     */
    private fun toolsFrom(array: JSONArray): List<McpTool> =
        (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val name = entry.optString("name").trim()
            if (name.isEmpty()) return@mapNotNull null
            McpTool(
                name = name,
                description = entry.optString("description"),
                inputSchema = entry.optJSONObject("inputSchema")?.let(::toMap).orEmpty(),
                readOnlyHint = ReadOnlyTrust.isReadOnlyHint(
                    entry.optJSONObject("annotations")?.opt("readOnlyHint")
                ),
            )
        }

    /**
     * Flattens a `content` array into the text the model reads.
     * @param content the array, or null when the server sent none.
     * @return the text, with non-text parts named rather than dropped silently.
     */
    private fun flattenContent(content: JSONArray?): String {
        if (content == null) return ""
        return (0 until content.length()).mapNotNull { index ->
            val part = content.optJSONObject(index) ?: return@mapNotNull null
            when (val type = part.optString("type")) {
                "text" -> part.optString("text")
                "" -> null
                else -> "[$type content omitted]"
            }
        }.filter { it.isNotBlank() }.joinToString("\n")
    }

    /** Converts a JSON object to plain JDK types, which is all that may cross to another plugin. */
    private fun toMap(json: JSONObject): Map<String, Any> =
        json.keys().asSequence().mapNotNull { key ->
            when (val value = json.get(key)) {
                is JSONObject -> key to toMap(value)
                is JSONArray -> key to toList(value)
                JSONObject.NULL -> null
                else -> key to value
            }
        }.toMap()

    private fun toList(array: JSONArray): List<Any> =
        (0 until array.length()).mapNotNull { index ->
            when (val value = array.get(index)) {
                is JSONObject -> toMap(value)
                is JSONArray -> toList(value)
                JSONObject.NULL -> null
                else -> value
            }
        }
}

/**
 * A JSON-RPC error answer, as opposed to a transport failure.
 *
 * @param code the JSON-RPC error code.
 * @param detail the server's message.
 */
class McpProtocolException(val code: Int, detail: String) : IOException(
    detail.ifBlank { "the server reported error $code" }
)
