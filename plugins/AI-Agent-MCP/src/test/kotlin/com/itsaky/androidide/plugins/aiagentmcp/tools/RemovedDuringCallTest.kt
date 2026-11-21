package com.itsaky.androidide.plugins.aiagentmcp.tools

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticEndpoint
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticServers
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A tool call still on its way when its SQLite debugger entry goes away leaves nothing of that
 * entry behind.
 *
 * The read that removes an entry takes out its session and its health. A call accepted before that
 * looked its session up afterwards, which stored a new one for an entry that no longer exists, and
 * once it was answered it marked the entry available again. Both are now skipped for a server that
 * is no longer configured, as a listing already skipped recording its health.
 *
 * The worker is held on a latch before its first line while the entry is removed; nothing sleeps.
 */
class RemovedDuringCallTest {

    private val delegate = Executors.newSingleThreadExecutor()
    private lateinit var server: FakeMcpServer
    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        plugin = McpPlugin().also { it.initialize(context) }
        // The Keystore is not there on the JVM; nothing here reads a hand-added server's secrets.
        mockkObject(McpServerStore)
        every { McpServerStore.token(any()) } returns KeystoreSecretStore.Stored.Absent
        every { McpServerStore.headers(any()) } returns emptyMap()
        // Answers whatever token it is sent: the removed entry's requests go out with none.
        server = FakeMcpServer("", listOf(tool("query", true)))
    }

    @After
    fun tearDown() {
        delegate.shutdownNow()
        AutomaticServers.install(null)
        McpConnections.closeAll()
        McpToolCatalog.clear()
        server.stop()
        plugin.dispose()
        unmockkObject(McpServerStore)
    }

    @Test
    fun `a call that outlives its entry's removal leaves no session and no health behind`() {
        val stored = AtomicReference(listOf(AutomaticEndpoint("com.example.notes", server.port, "t")))
        AutomaticServers.install { stored.get() }
        val entry = AutomaticServers.servers().single()
        McpToolCatalog.connect(entry)

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

        stored.set(emptyList())
        AutomaticServers.refresh()
        assertNull("the removal left the entry's health", McpServerHealth.of(entry.id))
        proceed.countDown()
        val outcome = future.get(10, TimeUnit.SECONDS)

        assertTrue("the call was not answered: ${outcome.errorMessage}", outcome.isSuccess)
        assertNull("the call stored a session for the removed entry", McpConnections.detach(entry.id))
        assertNull("the call brought back the removed entry's health", McpServerHealth.of(entry.id))
    }
}
