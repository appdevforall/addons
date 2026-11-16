package com.itsaky.androidide.plugins.aiagentmcp.testing

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.json.JSONArray
import org.json.JSONObject

/**
 * A loopback MCP server for tests: answers the handshake, `tools/list` and `tools/call` over
 * plain JSON, and 401 unless the bearer token is [token] (any request when it is empty). A call's text names the port that served
 * it, so a test can tell which endpoint a call reached.
 *
 * Every request is recorded in [requests], and a non-null [sessionId] is handed out on `initialize`,
 * so a test can see the `DELETE` a closing session sends and which token it carried.
 *
 * @param tools the `tools/list` entries.
 * @param sessionId the `Mcp-Session-Id` to assign, or null for a server without sessions.
 */
class FakeMcpServer(
    val token: String,
    private val tools: List<JSONObject>,
    private val sessionId: String? = null,
) {

    /** One request as the server saw it. */
    data class Seen(val httpMethod: String, val authorization: String?)

    val requests = java.util.concurrent.CopyOnWriteArrayList<Seen>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/mcp") { exchange ->
            requests += Seen(exchange.requestMethod, exchange.requestHeaders.getFirst("Authorization"))
            if (exchange.requestMethod == "DELETE") {
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
                return@createContext
            }
            val request = JSONObject(exchange.requestBody.use { String(it.readBytes()) })
            val id = request.opt("id")
            if (token.isNotEmpty() && exchange.requestHeaders.getFirst("Authorization") != "Bearer $token") {
                exchange.sendResponseHeaders(401, -1)
            } else if (id == null) {
                exchange.sendResponseHeaders(202, -1)
            } else {
                val result = when (request.getString("method")) {
                    "initialize" -> JSONObject()
                        .put("protocolVersion", "2025-03-26")
                        .put("serverInfo", JSONObject().put("name", "fake"))
                    "tools/list" -> JSONObject().put("tools", JSONArray(tools))
                    "tools/call" -> JSONObject().put(
                        "content",
                        JSONArray().put(JSONObject().put("type", "text").put("text", "served by $port")),
                    )
                    else -> JSONObject()
                }
                val bytes = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
                    .toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                if (sessionId != null && request.getString("method") == "initialize") {
                    exchange.responseHeaders.add("Mcp-Session-Id", sessionId)
                }
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        start()
    }

    val port: Int get() = server.address.port

    fun stop() = server.stop(0)

    companion object {
        fun tool(name: String, readOnlyHint: Boolean?): JSONObject = JSONObject()
            .put("name", name)
            .put("description", name)
            .put("inputSchema", JSONObject().put("type", "object"))
            .also { if (readOnlyHint != null) it.put("annotations", JSONObject().put("readOnlyHint", readOnlyHint)) }
    }
}
