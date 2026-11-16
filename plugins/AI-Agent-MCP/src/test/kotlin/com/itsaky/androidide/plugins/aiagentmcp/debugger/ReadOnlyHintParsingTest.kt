package com.itsaky.androidide.plugins.aiagentmcp.debugger

import com.itsaky.androidide.plugins.aiagentmcp.client.McpSession
import com.itsaky.androidide.plugins.aiagentmcp.client.McpTool
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpExchangeListener
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpHttpClient
import java.net.HttpURLConnection
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule is read from a real `tools/list` reply, so the JSON types are the ones a server sends. */
class ReadOnlyHintParsingTest {

    private fun listed(annotations: Any?, present: Boolean = true): McpTool {
        val tool = JSONObject().put("name", "t")
        if (present) tool.put("annotations", annotations)
        val http = object : McpHttpClient() {
            override fun post(
                url: String,
                token: String,
                body: JSONObject,
                sessionId: String?,
                protocolVersion: String?,
                extraHeaders: Map<String, String>,
                onConnected: (HttpURLConnection) -> Unit,
                exchangeListener: McpExchangeListener,
            ): Response {
                val result = when (body.optString("method")) {
                    "initialize" -> JSONObject().put("protocolVersion", "2026-07-28")
                    "tools/list" -> JSONObject().put("tools", JSONArray().put(tool))
                    else -> return Response(null, null)
                }
                val reply = JSONObject().put("jsonrpc", "2.0").put("id", body.opt("id")).put("result", result)
                return Response(reply.toString(), null)
            }
        }
        return McpSession("http://127.0.0.1:1/mcp", { com.itsaky.androidide.plugins.aiagentmcp.client.McpCredentials("", emptyMap()) }, http)
            .listTools().single()
    }

    private fun hint(value: Any?) = listed(JSONObject().put("readOnlyHint", value))

    @Test fun `boolean true is read only`() = assertTrue(hint(true).readOnlyHint)

    @Test fun `boolean false is not`() = assertFalse(hint(false).readOnlyHint)

    @Test fun `missing hint is not`() = assertFalse(listed(JSONObject()).readOnlyHint)

    @Test fun `missing annotations is not`() = assertFalse(listed(null, present = false).readOnlyHint)

    @Test fun `string true is not`() = assertFalse(hint("true").readOnlyHint)

    @Test fun `number one is not`() = assertFalse(hint(1).readOnlyHint)

    @Test fun `json null is not`() = assertFalse(hint(JSONObject.NULL).readOnlyHint)

    @Test
    fun `trust enables exactly the true ones`() {
        val tools = listOf(McpTool("a", "", readOnlyHint = true), McpTool("b", ""))
        assertEquals(setOf("a"), ReadOnlyTrust.enabledTools(tools))
    }
}
