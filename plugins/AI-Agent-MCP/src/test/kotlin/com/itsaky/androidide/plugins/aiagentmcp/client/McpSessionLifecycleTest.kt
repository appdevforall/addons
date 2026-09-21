package com.itsaky.androidide.plugins.aiagentmcp.client

import com.itsaky.androidide.plugins.aiagentmcp.transport.McpHttpClient
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpHttpException
import java.net.HttpURLConnection
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a session does across calls: whether `notifications/initialized` is sent, and whether the
 * handshake it paid for is then kept alive rather than repeated or silently lost.
 *
 * Reading an absent `Mcp-Session-Id` as "stateless" left a conforming 2025-06-18 server without
 * the notification, so its next `tools/list` answered "not initialized" and the user saw no tools.
 */
class McpSessionLifecycleTest {

    private companion object {
        const val ENDPOINT = "https://example.test/mcp"
        const val NOTIFICATION = "notifications/initialized"
        const val INITIALIZE = "initialize"
        const val LIST_TOOLS = "tools/list"
        const val SESSION = "s-1"
        const val TOOL = "search"
    }

    /**
     * Answers `initialize` with [protocolVersion], recording the methods it was asked for.
     *
     * @param protocolVersion the revision the handshake reports back.
     * @param sessionId the session the server assigns, or null for one that keeps no state.
     */
    private class FakeHttpClient(
        private val protocolVersion: String,
        private val sessionId: String? = null,
    ) : McpHttpClient() {

        /** One request the client made, with the session it carried. */
        data class Request(val method: String, val sessionId: String?)

        val requests = mutableListOf<Request>()

        /** Sessions the client ended with a DELETE. */
        val deletedSessions = mutableListOf<String>()

        /** The method to answer `404` for, standing in for a session the server forgot. */
        var expiringMethod: String? = null

        /** How many more times [expiringMethod] answers `404` before it starts working. */
        var expiriesLeft = 0

        val methods: List<String> get() = requests.map { it.method }

        override fun post(
            url: String,
            token: String,
            body: JSONObject,
            sessionId: String?,
            protocolVersion: String?,
            extraHeaders: Map<String, String>,
            onConnected: (HttpURLConnection) -> Unit,
        ): Response {
            val method = body.optString("method")
            requests += Request(method, sessionId)
            if (method == expiringMethod && expiriesLeft > 0) {
                expiriesLeft--
                throw McpHttpException(HttpURLConnection.HTTP_NOT_FOUND, "session expired")
            }
            val result = when (method) {
                INITIALIZE -> JSONObject().put("protocolVersion", this.protocolVersion)
                LIST_TOOLS -> JSONObject().put(
                    "tools",
                    JSONArray().put(JSONObject().put("name", TOOL))
                )
                else -> return Response(null, this.sessionId)
            }
            val document = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", body.opt("id"))
                .put("result", result)
            return Response(document.toString(), this.sessionId)
        }

        override fun deleteSession(
            url: String,
            token: String,
            sessionId: String,
            extraHeaders: Map<String, String>,
        ) {
            deletedSessions += sessionId
        }
    }

    private fun sessionOn(http: McpHttpClient) =
        McpSession(ENDPOINT, { McpCredentials("", emptyMap()) }, http)

    @Test
    fun givenAStatefulRevisionAndNoSessionHeader_whenInitializing_thenTheNotificationIsStillSent() {
        val http = FakeHttpClient(McpSession.PREFERRED_PROTOCOL_VERSION, sessionId = null)

        sessionOn(http).initialize()

        assertEquals(listOf(INITIALIZE, NOTIFICATION), http.methods)
    }

    @Test
    fun givenAStatefulRevisionAndASessionHeader_whenInitializing_thenTheNotificationIsSent() {
        val http = FakeHttpClient(McpSession.PREFERRED_PROTOCOL_VERSION, sessionId = SESSION)

        sessionOn(http).initialize()

        assertEquals(listOf(INITIALIZE, NOTIFICATION), http.methods)
    }

    @Test
    fun givenAStatelessRevision_whenInitializing_thenNoNotificationIsSent() {
        val http = FakeHttpClient(McpSession.STATELESS_FROM_VERSION, sessionId = null)

        sessionOn(http).initialize()

        assertEquals(listOf(INITIALIZE), http.methods)
    }

    @Test
    fun givenAnUnorderableRevision_whenInitializing_thenItIsTreatedAsStateful() {
        val http = FakeHttpClient("2.1.0", sessionId = null)

        sessionOn(http).initialize()

        assertTrue("an unparseable revision must fail safe", NOTIFICATION in http.methods)
    }

    @Test
    fun givenAnInitializedSession_whenMoreCallsFollow_thenTheHandshakeIsNotRepeated() {
        val http = FakeHttpClient(McpSession.PREFERRED_PROTOCOL_VERSION, sessionId = SESSION)
        val session = sessionOn(http)

        session.initialize()
        session.listTools()
        session.listTools()

        // The handshake is what a kept-alive session buys; paying it per call is the regression.
        assertEquals(1, http.methods.count { it == INITIALIZE })
        assertEquals(2, http.methods.count { it == LIST_TOOLS })
    }

    @Test
    fun givenAServerAssignedSession_whenACallFollows_thenItCarriesTheSessionHeader() {
        val http = FakeHttpClient(McpSession.PREFERRED_PROTOCOL_VERSION, sessionId = SESSION)

        sessionOn(http).listTools()

        assertNull("the handshake itself has no session yet", http.requests.first().sessionId)
        assertTrue(
            "every later request must carry the assigned session",
            http.requests.drop(1).all { it.sessionId == SESSION }
        )
    }

    @Test
    fun givenAnExpiredSession_whenTheServerAnswers404_thenItReInitializesAndRetriesOnce() {
        val http = FakeHttpClient(McpSession.PREFERRED_PROTOCOL_VERSION, sessionId = SESSION).apply {
            expiringMethod = LIST_TOOLS
            expiriesLeft = 1
        }

        val tools = sessionOn(http).listTools()

        assertEquals(
            listOf(INITIALIZE, NOTIFICATION, LIST_TOOLS, INITIALIZE, NOTIFICATION, LIST_TOOLS),
            http.methods
        )
        assertEquals(listOf(TOOL), tools.map { it.name })
    }

    @Test
    fun givenAClosedSession_whenItIsUsedAgain_thenTheServerSessionIsEndedAndTheHandshakeRepeats() {
        val http = FakeHttpClient(McpSession.PREFERRED_PROTOCOL_VERSION, sessionId = SESSION)
        val session = sessionOn(http)

        session.listTools()
        session.close()
        session.listTools()

        assertEquals(listOf(SESSION), http.deletedSessions)
        assertEquals(2, http.methods.count { it == INITIALIZE })
    }
}
