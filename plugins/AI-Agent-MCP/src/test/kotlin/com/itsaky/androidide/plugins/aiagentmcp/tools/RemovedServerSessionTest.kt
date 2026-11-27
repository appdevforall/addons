package com.itsaky.androidide.plugins.aiagentmcp.tools

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServer
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpSettingsViewModel
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer.Companion.tool
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import com.itsaky.androidide.plugins.services.ToolSourceRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A call or a listing made for a server of the user's that is already gone ends the session it
 * used.
 *
 * Such a lookup gets a session that is not kept, since one stored for a server that is gone would
 * outlive it with nothing left to end it. But nothing ended the unkept one either, so the server
 * kept the session its `initialize` assigned. It is now ended once the call or the listing is done.
 *
 * The worker is held on a latch before its first line while the server is deleted; nothing sleeps.
 */
class RemovedServerSessionTest {

    private val delegate = Executors.newSingleThreadExecutor()
    private val server = FakeMcpServer("", listOf(tool("search", true)), sessionId = "session-1")
    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        plugin = McpPlugin().also { it.initialize(context) }
        // The Keystore is not there on the JVM; this server has no secrets.
        mockkObject(McpServerStore)
        every { McpServerStore.token(any()) } returns KeystoreSecretStore.Stored.Absent
        every { McpServerStore.headers(any()) } returns emptyMap()
    }

    @After
    fun tearDown() {
        delegate.shutdownNow()
        McpConnections.closeAll()
        McpToolCatalog.clear()
        server.stop()
        plugin.dispose()
        unmockkObject(McpServerStore)
    }

    @Test
    fun `a call that outlives its server's removal ends the session it used`() {
        val saved = save()
        McpToolCatalog.connect(saved)
        McpServerStore.setToolEnabled(saved.id, "search", true)
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val source = McpToolSource(delegate) {
            started.countDown()
            assertTrue("the test never released the worker", proceed.await(10, TimeUnit.SECONDS))
        }
        val name = source.listTools().single().name
        val future = source.invoke(
            mockk<ToolSourceRegistry.ToolInvocation> {
                every { toolName } returns name
                every { arguments } returns emptyMap()
                every { callId } returns "call-1"
            }
        )
        assertTrue("the worker never started", started.await(10, TimeUnit.SECONDS))

        McpSettingsViewModel { null }.deleteNow(saved.id)
        val ended = deletes()
        proceed.countDown()
        val outcome = future.get(10, TimeUnit.SECONDS)

        assertTrue("the call was not answered: ${outcome.errorMessage}", outcome.isSuccess)
        assertEquals("the session the call used for the removed server was never ended", ended + 1, deletes())
    }

    @Test
    fun `a listing of a server already removed ends the session it used`() {
        val saved = save()
        McpSettingsViewModel { null }.deleteNow(saved.id)

        McpToolCatalog.connect(saved)

        assertEquals("the session the listing used for the removed server was never ended", 1, deletes())
    }

    private fun save(): McpServer =
        McpServerStore.saveDetails(McpServerStore.newServer("Docs", "http://127.0.0.1:${server.port}/mcp"))

    private fun deletes() = server.requests.count { it.httpMethod == "DELETE" }
}
