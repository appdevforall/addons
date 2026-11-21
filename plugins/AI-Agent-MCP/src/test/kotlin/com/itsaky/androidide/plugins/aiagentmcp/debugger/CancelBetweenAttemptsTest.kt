package com.itsaky.androidide.plugins.aiagentmcp.debugger

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer.Companion.tool
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpToolCatalog
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpToolSource
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


/** A cancel that lands between the endpoint retry's two attempts must stop the second one. */
class CancelBetweenAttemptsTest {

    private val app = "com.example.app"
    private val servers = mutableListOf<FakeMcpServer>()
    private val executor = Executors.newSingleThreadExecutor()
    private val source = McpToolSource(executor)
    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        plugin = McpPlugin().also { it.initialize(context) }
        // The Keystore is not there on the JVM; a hand-added server in these tests has no secrets.
        mockkObject(McpServerStore)
        every { McpServerStore.token(any()) } returns KeystoreSecretStore.Stored.Absent
        every { McpServerStore.headers(any()) } returns emptyMap()
    }

    @After
    fun tearDown() {
        source.close()
        AutomaticServers.install(null)
        McpConnections.closeAll()
        McpToolCatalog.clear()
        servers.forEach { it.stop() }
        plugin.dispose()
        unmockkObject(McpServerStore)
    }

    private fun fake(token: String, vararg tools: org.json.JSONObject) =
        FakeMcpServer(token, tools.toList()).also { servers += it }

    private fun offered(): Map<String, ToolSourceRegistry.ToolSpec> =
        source.listTools().associateBy { it.name }

    private fun invoke(name: String): java.util.concurrent.CompletableFuture<ToolSourceRegistry.ToolOutcome> =
        source.invoke(
            mockk<ToolSourceRegistry.ToolInvocation> {
                every { toolName } returns name
                every { arguments } returns emptyMap()
                every { callId } returns "call-1"
            }
        )

    @Test
    fun `a cancel between the attempts sends nothing to the new endpoint`() {
        val first = fake("t1", tool("list_databases", true))
        var stored = listOf(AutomaticEndpoint(app, first.port, "t1"))
        var cancelOnRead = false
        // invoke stores the call's future only after its worker has started, and a cancel that
        // landed first would find no future to cancel: the outcome would come back as an ordinary
        // failure, which is not what this test is about. So the cancel waits for invoke to return.
        val invoked = CountDownLatch(1)
        AutomaticServers.install {
            if (cancelOnRead) {
                check(invoked.await(10, TimeUnit.SECONDS)) { "invoke never returned" }
                source.cancel("call-1")
            }
            stored
        }
        McpToolCatalog.connect(AutomaticServers.servers().single())
        val name = offered().keys.single()

        // The app relaunches: the first attempt is refused, and the retry's re-read is where the
        // cancel lands.
        first.stop()
        val second = fake("t2", tool("list_databases", true))
        stored = listOf(AutomaticEndpoint(app, second.port, "t2"))
        cancelOnRead = true
        val future = invoke(name)
        invoked.countDown()

        // The agent's future is cancelled with the call; the worker behind it is what must stop.
        // Waits for the worker itself, so whatever it was going to send has been sent.
        executor.shutdown()
        assertTrue("the call never finished", executor.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue(future.isCompletedExceptionally)
        assertTrue("the retry was sent anyway: ${second.requests}", second.requests.isEmpty())
    }
}
