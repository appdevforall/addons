package com.itsaky.androidide.plugins.aiagentmcp.client

import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticEndpoint
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticServers
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer.Companion.tool
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpToolCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A session for a replaced debugger endpoint must not send the new endpoint's token to the old port. */
class ReplacedEndpointCredentialTest {

    private val app = "com.example.app"
    private val servers = mutableListOf<FakeMcpServer>()

    @After
    fun tearDown() {
        AutomaticServers.install(null)
        McpConnections.closeAll()
        McpToolCatalog.clear()
        servers.forEach { it.stop() }
    }

    @Test
    fun `closing the session of a replaced endpoint does not send the new token to the old port`() {
        val old = FakeMcpServer("old-token", listOf(tool("list_tables", true)), sessionId = "s1")
            .also { servers += it }
        val replacement = FakeMcpServer("new-token", listOf(tool("list_tables", true)), sessionId = "s2")
            .also { servers += it }
        var stored = listOf(AutomaticEndpoint(app, old.port, "old-token"))
        AutomaticServers.install { stored }

        val session = McpConnections.withSession(AutomaticServers.servers().single()) { it }
        session.listTools()
        assertTrue("the session opened against the old endpoint", old.requests.any { it.httpMethod == "POST" })

        stored = listOf(AutomaticEndpoint(app, replacement.port, "new-token"))
        assertTrue(AutomaticServers.refresh())
        McpConnections.withSession(AutomaticServers.servers().single()) { }   // replaces and closes the old one

        val delete = old.requests.single { it.httpMethod == "DELETE" }
        assertNotEquals("Bearer new-token", delete.authorization)
        assertEquals(null, delete.authorization?.removePrefix("Bearer ")?.takeIf { it.isNotEmpty() })
    }
}
