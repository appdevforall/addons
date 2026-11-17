package com.itsaky.androidide.plugins.aiagentmcp.debugger

import com.itsaky.androidide.plugins.aiagentmcp.client.McpTool
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpServerHealth
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpToolCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticServersTest {

    @After
    fun reset() = AutomaticServers.install(null)

    private fun endpoint(app: String = "com.example.app", port: Int = 4000, token: String = "t1") =
        AutomaticEndpoint(app, port, token)

    @Test
    fun `no endpoint means no entry and no error`() {
        AutomaticServers.install { emptyList() }
        assertTrue(AutomaticServers.servers().isEmpty())
        assertTrue(McpServers.all().isEmpty())
    }

    @Test
    fun `a host that refuses or lacks the service means no entry and no error`() {
        AutomaticServers.install { throw SecurityException("no permission") }
        assertTrue(AutomaticServers.servers().isEmpty())
    }

    @Test
    fun `an endpoint becomes a loopback entry with its token held in memory`() {
        AutomaticServers.install { listOf(endpoint(port = 4321, token = "secret")) }

        val server = AutomaticServers.servers().single()
        assertEquals("http://127.0.0.1:4321/mcp", server.url)
        assertTrue(server.automatic)
        assertTrue(server.enabled)
        assertEquals("secret", AutomaticServers.token(server.id))
        assertFalse("secret" in server.toString())
    }

    @Test
    fun `the entry disappears when the app stops reporting`() {
        var current = listOf(endpoint())
        AutomaticServers.install { current }
        assertEquals(1, AutomaticServers.servers().size)

        current = emptyList()
        assertTrue(AutomaticServers.refresh())
        assertTrue(AutomaticServers.servers().isEmpty())
    }

    @Test
    fun `refresh reports a change only when port or token differ`() {
        var current = listOf(endpoint())
        AutomaticServers.install { current }
        assertFalse(AutomaticServers.refresh())

        current = listOf(endpoint(port = 4001))
        assertTrue(AutomaticServers.refresh())
        current = listOf(endpoint(port = 4001, token = "t2"))
        assertTrue(AutomaticServers.refresh())
        assertEquals("t2", AutomaticServers.token(AutomaticServers.idFor("com.example.app")))
    }

    @Test
    fun `a relaunch keeps the old tools and switches until the new process lists`() {
        var current = listOf(endpoint())
        AutomaticServers.install { current }
        val id = AutomaticServers.idFor("com.example.app")
        AutomaticServers.recordTools(id, endpoint(), listOf(McpTool("query", "", readOnlyHint = true)))

        current = listOf(endpoint(port = 4002))
        AutomaticServers.refresh()

        val server = AutomaticServers.servers().single()
        assertEquals(listOf("query"), server.knownTools)
        assertEquals(setOf("query"), server.enabledTools)
        assertTrue(server.url.endsWith(":4002/mcp"))
    }

    @Test
    fun `a relaunch asks the owner to list the new endpoint at once`() {
        var current = listOf(endpoint())
        val asked = mutableListOf<String>()
        AutomaticServers.install { current }
        AutomaticServers.addEndpointListener { asked += it }

        assertFalse(AutomaticServers.refresh())
        assertTrue(asked.isEmpty())

        current = listOf(endpoint(port = 4002, token = "t2"))
        AutomaticServers.refresh()
        assertEquals(listOf(AutomaticServers.idFor("com.example.app")), asked)
    }

    @Test
    fun `a relaunch lets the probe ask an entry that had refused its credential`() {
        var current = listOf(endpoint())
        AutomaticServers.install { current }
        val id = AutomaticServers.idFor("com.example.app")
        McpServerHealth.degraded(id, "refused", refused = true)

        current = listOf(endpoint(token = "t2"))
        AutomaticServers.refresh()
        assertFalse(McpServerHealth.of(id)!!.refused)
        McpServerHealth.forget(id)
    }

    @Test
    fun `a tool list fetched from a replaced endpoint is dropped`() {
        var current = listOf(endpoint())
        AutomaticServers.install { current }
        val id = AutomaticServers.idFor("com.example.app")
        val old = endpoint()
        AutomaticServers.recordTools(id, old, listOf(McpTool("fresh", "")))

        current = listOf(endpoint(port = 4002))
        AutomaticServers.refresh()
        assertFalse(AutomaticServers.recordTools(id, old, listOf(McpTool("stale", ""))))

        assertEquals(listOf("fresh"), AutomaticServers.servers().single().knownTools)
        assertTrue(AutomaticServers.recordTools(id, endpoint(port = 4002), listOf(McpTool("newer", ""))))
        assertEquals(listOf("newer"), AutomaticServers.servers().single().knownTools)
    }

    @Test
    fun `a failed read keeps the entries as they were`() {
        var failing = false
        AutomaticServers.install {
            if (failing) throw IllegalStateException("db closed")
            listOf(endpoint())
        }
        val id = AutomaticServers.idFor("com.example.app")
        AutomaticServers.recordTools(id, endpoint(), listOf(McpTool("q", "", readOnlyHint = true)))

        failing = true
        assertFalse(AutomaticServers.refresh())

        val server = AutomaticServers.servers().single()
        assertEquals(listOf("q"), server.knownTools)
        assertEquals("t1", AutomaticServers.token(id))
    }

    @Test
    fun `an entry that disappears has its health and cached tools forgotten`() {
        var current = listOf(endpoint())
        AutomaticServers.install { current }
        val id = AutomaticServers.idFor("com.example.app")
        McpServerHealth.available(id)

        current = emptyList()
        AutomaticServers.refresh()

        assertNull(McpServerHealth.of(id))
        assertTrue(McpToolCatalog.tools(id).isEmpty())
    }

    @Test
    fun `only readOnlyHint core tools of the automatic entry start on`() {
        AutomaticServers.install { listOf(endpoint()) }
        val id = AutomaticServers.idFor("com.example.app")
        AutomaticServers.recordTools(
            id,
            endpoint(),
            listOf(
                McpTool("query", "", readOnlyHint = true),
                McpTool("get_stats", "", readOnlyHint = true),
                McpTool("get_schema", "", readOnlyHint = false),
                McpTool("get_events", ""),
            ),
        )

        val server = AutomaticServers.servers().single()
        assertEquals(listOf("query", "get_stats", "get_schema", "get_events"), server.knownTools)
        assertEquals(setOf("query"), server.enabledTools)
        assertEquals(listOf("query"), server.activeTools)
    }

    @Test
    fun `the four core tools start on and the other four stay known but off`() {
        AutomaticServers.install { listOf(endpoint()) }
        val names = listOf(
            "list_connections", "list_databases", "get_schema", "query",
            "explain_query_plan", "get_stats", "integrity_check", "get_events",
        )
        AutomaticServers.recordTools(
            AutomaticServers.idFor("com.example.app"),
            endpoint(),
            names.map { McpTool(it, "", readOnlyHint = true) },
        )

        val server = AutomaticServers.servers().single()
        assertEquals(names, server.knownTools)
        assertEquals(setOf("list_databases", "get_schema", "query", "get_events"), server.enabledTools)
    }
}
