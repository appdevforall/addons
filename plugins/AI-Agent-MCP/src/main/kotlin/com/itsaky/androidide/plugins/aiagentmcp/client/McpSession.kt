package com.itsaky.androidide.plugins.aiagentmcp.client

import android.util.Log
import com.itsaky.androidide.plugins.aiagentmcp.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aiagentmcp.transport.JsonRpc
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

    /** The connection a call is currently using, so [cancel] can drop it mid-flight. */
    @Volatile private var liveConnection: HttpURLConnection? = null

    /** What the server called itself in `initialize`, for the settings pane's verdict. */
    @Volatile var serverName: String? = null
        private set

    /**
     * Performs the MCP handshake, unless it has already been performed.
     *
     * @throws IOException when the server cannot be reached or refuses the handshake.
     */
    @Synchronized
    fun initialize() {
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

        val result = call(METHOD_INITIALIZE, params)
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
     * @return the outcome, successful or not.
     * @throws IOException when the server cannot be reached.
     */
    fun callTool(name: String, arguments: Map<String, Any?>): McpCallResult {
        initialize()

        val params = JSONObject().apply {
            put("name", name)
            put("arguments", JSONObject(arguments.filterValues { it != null }))
        }

        val result = try {
            call("tools/call", params)
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

    /** Drops the connection a call is blocked on, so a stopped agent run does not wait it out. */
    fun cancel() {
        runCatching { liveConnection?.disconnect() }
    }

    /** Ends the server-side session, if there is one, and forgets the handshake. */
    @Synchronized
    fun close() {
        sessionId?.let { id ->
            runCatching {
                val current = credentials()
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
     * @return the `result` object.
     * @throws McpProtocolException when the server answers a JSON-RPC error.
     * @throws IOException on transport failure or a reply that is not one.
     */
    private fun call(method: String, params: JSONObject? = null): JSONObject {
        val envelope = JsonRpc.request(nextId.getAndIncrement().toString(), method, params)

        val response = try {
            send(envelope)
        } catch (e: McpHttpException) {
            val retryable = e.statusCode == HttpURLConnection.HTTP_NOT_FOUND &&
                sessionId != null &&
                method != METHOD_INITIALIZE
            if (retryable) {
                Log.i(TAG, "Session expired; re-initializing before retrying $method")
                sessionId = null
                initialized = false
                initialize()
                send(envelope)
            } else {
                throw e
            }
        }

        val document = response.document
            ?: throw IOException("The server accepted '$method' but sent no reply.")

        val reply = try {
            JsonRpc.parseReply(document)
        } catch (e: Exception) {
            throw IOException("The server's reply to '$method' was not JSON-RPC: ${e.message}")
        } ?: throw IOException("The server's reply to '$method' carried no result.")

        if (reply.isError) {
            throw McpProtocolException(reply.errorCode ?: 0, reply.errorMessage.orEmpty())
        }
        return reply.result ?: JSONObject()
    }

    /** POSTs one envelope, tracking the connection so [cancel] can reach it. */
    private fun send(envelope: JSONObject): McpHttpClient.Response {
        // Read here rather than held on the session, so the plaintext lives for one request.
        val current = credentials()
        // Cleared in a finally: a call that threw would otherwise leave [cancel] holding a
        // connection that is already closed, and the next cancel would reach nothing live.
        val response = try {
            http.post(
                url = endpoint,
                token = current.token,
                body = envelope,
                sessionId = sessionId,
                protocolVersion = negotiatedVersion,
                extraHeaders = current.headers,
                onConnected = { liveConnection = it },
            )
        } finally {
            liveConnection = null
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
