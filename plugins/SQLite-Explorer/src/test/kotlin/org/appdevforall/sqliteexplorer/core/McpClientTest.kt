package org.appdevforall.sqliteexplorer.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private fun result(request: JSONObject, result: JSONObject) =
    JSONObject().put("jsonrpc", "2.0").put("id", request.get("id")).put("result", result).toString()

class McpClientTest {
    /** Answers like the debugger's server: a session on initialize, 202 for notifications, scripted replies after. */
    private class FakeServer(val replies: MutableList<(JSONObject) -> HttpReply> = mutableListOf()) : HttpTransport {
        val requests = mutableListOf<Pair<Map<String, String>, JSONObject>>()
        var sessions = 0

        override fun post(port: Int, headers: Map<String, String>, body: String): HttpReply {
            val json = JSONObject(body)
            requests += headers to json
            return when (json.optString("method")) {
                "initialize" -> {
                    sessions++
                    HttpReply(200, "session-$sessions", result(json, JSONObject().put("protocolVersion", "2025-06-18")))
                }
                "notifications/initialized" -> HttpReply(202, null, "")
                else -> replies.removeAt(0)(json)
            }
        }
    }

    private fun toolOk(structured: JSONObject): (JSONObject) -> HttpReply =
        { request -> HttpReply(200, null, result(request, JSONObject().put("content", org.json.JSONArray()).put("structuredContent", structured).put("isError", false))) }

    @Test
    fun `the first call starts a session, then sends the token, session and protocol version`() {
        val server = FakeServer(mutableListOf(toolOk(JSONObject().put("databases", org.json.JSONArray()))))
        val result = McpClient(4321, "tok", server).callTool("list_databases")

        assertEquals(0, result.getJSONArray("databases").length())
        assertEquals(listOf("initialize", "notifications/initialized", "tools/call"), server.requests.map { it.second.optString("method") })
        val (initHeaders, _) = server.requests[0]
        assertEquals("Bearer tok", initHeaders["Authorization"])
        assertNull(initHeaders["Mcp-Session-Id"])
        val (callHeaders, call) = server.requests[2]
        assertEquals("Bearer tok", callHeaders["Authorization"])
        assertEquals("session-1", callHeaders["Mcp-Session-Id"])
        assertEquals("2025-06-18", callHeaders["MCP-Protocol-Version"])
        assertEquals("list_databases", call.getJSONObject("params").getString("name"))
    }

    @Test
    fun `a tool error becomes a ToolException with its text and details`() {
        val server =
            FakeServer(
                mutableListOf({ request ->
                    val body =
                        JSONObject()
                            .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "no such table: x")))
                            .put("structuredContent", JSONObject().put("sqlite_code", 1))
                            .put("isError", true)
                    HttpReply(200, null, result(request, body))
                }),
            )

        val e = assertThrows(ToolException::class.java) { McpClient(1, "tok", server).callTool("query") }
        assertEquals("no such table: x", e.message)
        assertEquals(1, e.details!!.getInt("sqlite_code"))
    }

    @Test
    fun `a rejected token says so`() {
        val server = object : HttpTransport {
            override fun post(port: Int, headers: Map<String, String>, body: String) = HttpReply(401, null, "")
        }
        assertThrows(TokenRejectedException::class.java) { McpClient(1, "tok", server).callTool("list_databases") }
    }

    @Test
    fun `an expired session is started again once and the call retried`() {
        val server = FakeServer()
        server.replies += { HttpReply(404, null, "") }
        server.replies += toolOk(JSONObject().put("ok", true))

        val result = McpClient(1, "tok", server).callTool("get_stats")

        assertTrue(result.getBoolean("ok"))
        assertEquals(2, server.sessions)
        assertEquals("session-2", server.requests.last().first["Mcp-Session-Id"])
    }

    @Test
    fun `a JSON-RPC error is reported`() {
        val server = FakeServer(mutableListOf({ request ->
            HttpReply(200, null, JSONObject().put("jsonrpc", "2.0").put("id", request.get("id")).put("error", JSONObject().put("code", -32602).put("message", "Unknown tool: x")).toString())
        }))
        val e = assertThrows(McpException::class.java) { McpClient(1, "tok", server).callTool("x") }
        assertEquals("Unknown tool: x", e.message)
    }

    @Test
    fun `a result without structuredContent is refused as an older server`() {
        val server = FakeServer(mutableListOf({ request -> HttpReply(200, null, result(request, JSONObject().put("content", org.json.JSONArray()))) }))
        assertThrows(McpException::class.java) { McpClient(1, "tok", server).callTool("list_databases") }
    }

    @Test
    fun `a token that could forge a header is refused before any request`() {
        val server = FakeServer()
        assertThrows(IllegalArgumentException::class.java) { McpClient(1, "tok\r\nX-Evil: 1", server) }
        assertThrows(IllegalArgumentException::class.java) { McpClient(1, "", server) }
        assertTrue(server.requests.isEmpty())
    }
}
