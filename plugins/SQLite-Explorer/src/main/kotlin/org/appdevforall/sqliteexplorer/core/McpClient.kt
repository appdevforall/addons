package org.appdevforall.sqliteexplorer.core

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** The server refused or failed the request itself: bad token, unknown session, malformed reply. */
open class McpException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The server rejected the token: the app restarted and has a new one, which Code on the Go learns when it reports. */
class TokenRejectedException : McpException("The app refused the stored token.")

/** A tool ran and reported an error, such as a SQL error. [details] is the tool's structured detail, if any. */
class ToolException(message: String, val details: JSONObject?) : McpException(message)

/**
 * A minimal MCP client for the database debugger: `initialize`, then `tools/call`.
 *
 * One call at a time; not thread-safe. Calls block, so callers keep them off the main thread.
 */
class McpClient(
    private val port: Int,
    private val token: String,
    private val transport: HttpTransport = LoopbackTransport(),
    private val clientVersion: String = "1",
) {
    private var sessionId: String? = null
    private var protocolVersion: String? = null
    private var nextId = 1L

    init {
        // A CR or LF would forge a second header.
        require(token.isNotEmpty() && token.all { it in '!'..'~' }) { "The stored token is not usable." }
    }

    /** Calls tool [name] and returns its structured result. Starts a session first if there is none. */
    fun callTool(name: String, arguments: JSONObject = JSONObject()): JSONObject {
        if (sessionId == null) initialize()
        val params = JSONObject().put("name", name).put("arguments", arguments)
        val reply =
            try {
                request("tools/call", params)
            } catch (e: SessionExpiredException) {
                // The app restarted its server with the same port and token, or the session timed out.
                initialize()
                request("tools/call", params)
            }
        return toolResult(name, reply)
    }

    private fun initialize() {
        sessionId = null
        protocolVersion = null
        val params =
            JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "SQLite Explorer").put("version", clientVersion))
        val result = request("initialize", params)
        protocolVersion = result.optString("protocolVersion").takeIf { it.isNotEmpty() } ?: PROTOCOL_VERSION
        if (sessionId == null) throw McpException("The app's server started no session.")
        val notification = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")
        send(notification)
    }

    /** Sends a JSON-RPC request and returns its `result`. */
    private fun request(method: String, params: JSONObject): JSONObject {
        val id = nextId++
        val envelope = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val reply = send(envelope)
        val document =
            try {
                JSONObject(reply.body)
            } catch (e: JSONException) {
                throw McpException("The app's server sent a reply that is not JSON.", e)
            }
        document.optJSONObject("error")?.let { error ->
            throw McpException(error.optString("message").ifEmpty { "The app's server reported an error." })
        }
        return document.optJSONObject("result") ?: throw McpException("The app's server sent a reply without a result.")
    }

    private fun send(envelope: JSONObject): HttpReply {
        val headers = LinkedHashMap<String, String>()
        headers["Authorization"] = "Bearer $token"
        sessionId?.let { headers[LoopbackTransport.SESSION_HEADER] = it }
        protocolVersion?.let { headers[PROTOCOL_HEADER] = it }
        val reply = transport.post(port, headers, envelope.toString())
        when (reply.status) {
            200, 202 -> Unit
            401 -> throw TokenRejectedException()
            404 -> if (sessionId != null) throw SessionExpiredException() else throw McpException("The app's server has no MCP endpoint here.")
            else -> throw McpException("The app's server answered HTTP ${reply.status}${reply.body.take(200).let { if (it.isBlank()) "" else ": $it" }}")
        }
        reply.sessionId?.let { sessionId = it }
        return reply
    }

    private fun toolResult(name: String, result: JSONObject): JSONObject {
        if (result.optBoolean("isError", false)) {
            throw ToolException(textOf(result).ifEmpty { "$name failed." }, result.optJSONObject("structuredContent"))
        }
        return result.optJSONObject("structuredContent")
            ?: throw McpException("$name returned no structured result: the app needs a newer Code on the Go.")
    }

    private fun textOf(result: JSONObject): String {
        val content = result.optJSONArray("content") ?: return ""
        return (0 until content.length())
            .mapNotNull { content.optJSONObject(it)?.takeIf { part -> part.optString("type") == "text" }?.optString("text") }
            .joinToString("\n")
    }

    private class SessionExpiredException : McpException("The session expired.")

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        const val PROTOCOL_HEADER = "MCP-Protocol-Version"
    }
}
