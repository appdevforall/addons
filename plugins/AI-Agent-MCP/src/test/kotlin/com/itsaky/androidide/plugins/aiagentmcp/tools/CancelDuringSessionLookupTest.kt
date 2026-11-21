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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A cancel that lands while a call looks up its session must stop the call.
 *
 * The lookup sits between the worker's check of the cancel flag and the call's first request. A
 * cancel in that window sets the flag but finds no connection to drop, so only a later read of the
 * flag, once the request has published its connection to the call's handle, keeps the request from
 * going out. The test lands the cancel there for certain: the lookup reads the server's token, and
 * the first read after the test arms it cancels the call.
 */
class CancelDuringSessionLookupTest {

    private val executor = Executors.newSingleThreadExecutor()
    private val source = McpToolSource(executor)
    private val armed = AtomicBoolean(false)
    private lateinit var server: FakeMcpServer
    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        plugin = McpPlugin().also { it.initialize(context) }
        // The Keystore is not there on the JVM; this server has no secrets. Reading its token is
        // also where the call looks up its session, so that is where the cancel lands.
        mockkObject(McpServerStore)
        every { McpServerStore.token(any()) } answers {
            if (armed.getAndSet(false)) source.cancel(CALL_ID)
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
    fun `a cancel that lands while the call looks up its session sends nothing`() {
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

        assertFalse("the call never looked up its session", armed.get())
        assertEquals(
            "the call reached the server after it was cancelled: ${server.requests.drop(before)}",
            before,
            server.requests.size,
        )
    }

    private companion object {
        const val CALL_ID = "call-1"
    }
}
