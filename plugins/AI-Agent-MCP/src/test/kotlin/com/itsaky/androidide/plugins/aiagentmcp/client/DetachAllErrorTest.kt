package com.itsaky.androidide.plugins.aiagentmcp.client

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer.Companion.tool
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpToolCatalog
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Taking every session out loses none of them when reading one session's credentials throws an
 * Error.
 *
 * `detachAll` emptied the map, then read each session's credentials as it built the list it
 * returned. An Error from one read, which a recoverable read lets through, escaped with the list
 * unbuilt: the sessions were out of the map and nobody held them, so none got its DELETE. Each
 * session now reaches the caller's list as it leaves the map, the one whose read failed with no
 * credentials, and the Error is thrown after the others are safe.
 */
class DetachAllErrorTest {

    private val servers = List(3) { FakeMcpServer("", listOf(tool("search", true)), sessionId = "session-$it") }
    private val failure = OutOfMemoryError("reading the second token")
    @Volatile private var failing = false

    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        plugin = McpPlugin().also { it.initialize(context) }
        mockkObject(McpServerStore)
        every { McpServerStore.headers(any()) } returns emptyMap()
    }

    @After
    fun tearDown() {
        failing = false
        McpConnections.closeAll()
        McpToolCatalog.clear()
        servers.forEach { it.stop() }
        plugin.dispose()
        unmockkObject(McpServerStore)
    }

    @Test
    fun `an Error reading one session's credentials does not lose the others, and reaches the caller`() {
        val saved = servers.mapIndexed { i, server ->
            McpServerStore.saveDetails(McpServerStore.newServer("S$i", "http://127.0.0.1:${server.port}/mcp"))
        }
        every { McpServerStore.token(any()) } answers {
            val id = firstArg<String>()
            if (failing && id == saved[1].id) throw failure
            KeystoreSecretStore.Stored.Value("token-of-$id")
        }
        saved.forEach { McpConnections.withSession(it) { session -> session.initialize() } }
        // After the sessions exist, so only the take-out's read throws.
        failing = true

        val thrown = assertThrows(OutOfMemoryError::class.java) { McpConnections.closeAll() }

        assertSame("not the Error from the read", failure, thrown)
        servers.forEachIndexed { i, server ->
            val delete = server.requests.singleOrNull { it.httpMethod == "DELETE" }
            assertEquals("session $i was never ended", true, delete != null)
            if (i == 1) {
                assertNull("the session whose read failed was sent a token", delete!!.authorization)
            } else {
                assertEquals("Bearer token-of-${saved[i].id}", delete!!.authorization)
            }
        }
    }
}
