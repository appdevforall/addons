package com.itsaky.androidide.plugins.aiagentmcp.client

import com.itsaky.androidide.plugins.aiagentmcp.transport.JsonDepth
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpExchangeListener
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpHttpClient
import java.io.IOException
import java.net.HttpURLConnection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * A server whose reply nests arrays thousands deep fails the listing with a reason, rather than
 * running the thread out of stack.
 *
 * Android's org.json recurses once per level with no limit, and the StackOverflowError it throws
 * is not caught on the way: during Connect, the activation's first listing or the health probe it
 * would end Code on the Go. The reply is now refused before it is parsed. The org.json these tests
 * run on fails such a parse with a JSONException of its own, so the message is what tells the
 * guard's refusal apart.
 */
class DeepReplyTest {

    private fun session(listReply: String) = McpSession(
        "http://127.0.0.1:1/mcp",
        { McpCredentials("", emptyMap()) },
        object : McpHttpClient() {
            override fun post(
                url: String,
                token: String,
                body: JSONObject,
                sessionId: String?,
                protocolVersion: String?,
                extraHeaders: Map<String, String>,
                onConnected: (HttpURLConnection) -> Unit,
                beforeSend: () -> Unit,
                exchangeListener: McpExchangeListener,
            ): Response = when (body.optString("method")) {
                "initialize" -> Response(
                    """{"jsonrpc":"2.0","id":${body.get("id")},"result":{"protocolVersion":"2026-07-28"}}""",
                    null,
                )
                "tools/list" -> Response(listReply, null)
                else -> Response(null, null)
            }
        },
    )

    @Test
    fun `a reply nested ten thousand deep fails the listing with the depth as its reason`() {
        val failure = assertThrows(IOException::class.java) { session("[".repeat(10_000)).listTools() }

        assertEquals(
            "The server's reply to 'tools/list' nests arrays and objects more than ${JsonDepth.MAX} " +
                "levels deep; it was not read.",
            failure.message,
        )
    }
}
