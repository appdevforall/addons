package com.itsaky.androidide.plugins.aiagentmcp.tools

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer.Companion.tool
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import com.itsaky.androidide.plugins.services.ToolSourceRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A cancel that lands while a request reads its credentials must stop the call.
 *
 * Reading them decrypts the token through the Keystore, after the worker's own read of the cancel
 * flag and before the request has a connection a cancel could drop. A cancel there used to find the
 * session, drop nothing, and leave the `tools/call` to go out; each request now publishes its
 * connection to the call's handle and reads the flag again before it sends anything.
 *
 * The test lands the cancel there for certain. Once armed, the worker reads the server's token
 * twice: first while it looks up its session, then while the `tools/call` request reads its
 * credentials, and that second read cancels the call.
 */
class CancelDuringCredentialsReadTest {

    private val executor = Executors.newSingleThreadExecutor()
    private val source = McpToolSource(executor)
    private val armed = AtomicBoolean(false)
    private val reads = AtomicInteger(0)
    private lateinit var server: FakeMcpServer
    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        plugin = McpPlugin().also { it.initialize(context) }
        // The Keystore is not there on the JVM; this server has no secrets.
        mockkObject(McpServerStore)
        every { McpServerStore.token(any()) } answers {
            if (armed.get() && reads.incrementAndGet() == CREDENTIALS_READ) {
                armed.set(false)
                source.cancel(CALL_ID)
            }
            KeystoreSecretStore.Stored.Absent
        }
        every { McpServerStore.headers(any()) } returns emptyMap()
        server = FakeMcpServer("", listOf(tool("search", true)))
    }

    @After
    fun tearDown() {
        source.close()
        McpConnections.closeAll()
        McpToolCatalog.clear()
        server.stop()
        plugin.dispose()
        unmockkObject(McpServerStore)
    }

    @Test
    fun `a cancel that lands while the request reads its credentials sends nothing`() {
        val docs = McpServerStore.saveDetails(
            McpServerStore.newServer("Docs", "http://127.0.0.1:${server.port}/mcp")
        )
        McpToolCatalog.connect(docs)
        McpServerStore.setToolEnabled(docs.id, "search", true)
        val name = source.listTools().single().name
        val before = server.requests.size

        armed.set(true)
        source.invoke(
            mockk<ToolSourceRegistry.ToolInvocation> {
                every { toolName } returns name
                every { arguments } returns emptyMap()
                every { callId } returns CALL_ID
            }
        )
        // Waits for the worker itself, so whatever it was going to send has been sent.
        executor.shutdown()
        assertTrue("the call never finished", executor.awaitTermination(10, TimeUnit.SECONDS))

        assertFalse("the call never read its credentials", armed.get())
        assertEquals(
            "the call reached the server after it was cancelled: ${server.requests.drop(before)}",
            before,
            server.requests.size,
        )
        assertEquals("a cancelled call left its registration behind", 0, source.pendingCalls())
    }

    private companion object {
        const val CALL_ID = "call-1"

        /** The session lookup reads the token first; the `tools/call` request's read is second. */
        const val CREDENTIALS_READ = 2
    }
}
