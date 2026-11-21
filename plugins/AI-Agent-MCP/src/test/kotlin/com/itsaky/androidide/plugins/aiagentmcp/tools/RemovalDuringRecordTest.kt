package com.itsaky.androidide.plugins.aiagentmcp.tools

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.debugger.McpServers
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServer
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpSettingsViewModel
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer.Companion.tool
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import com.itsaky.androidide.plugins.aiagentmcp.testing.awaitFinishedOrBlockedBy
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpExchangeListener
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import com.itsaky.androidide.plugins.services.ToolSourceRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A server of the user's removed between the check that it is still configured and the write of
 * its health or its tools leaves neither behind.
 *
 * A listing and a call checked that their server was configured, then wrote its health, with
 * nothing to keep a removal from landing in between: the removal forgot the health, and the write
 * brought it back. The listing wrote the server's tools with no check at all, so a removal that
 * finished first saw them come back too. Each check and its write now share the lock the
 * removal's forget takes.
 *
 * The deletion runs on its own thread from inside the check, once the request before it is over,
 * and the check waits until the deletion has finished or is blocked on a lock the checking thread
 * holds. Nothing sleeps.
 */
class RemovalDuringRecordTest {

    private val delegate = Executors.newSingleThreadExecutor()
    private val removals = CopyOnWriteArrayList<Thread>()

    /** Whether the next check should run the deletion; read by every check, on any thread. */
    @Volatile private var trigger: () -> Boolean = { false }
    private val fired = AtomicBoolean(false)

    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        plugin = McpPlugin().also { it.initialize(context) }
        // The Keystore is not there on the JVM; these servers have no secrets.
        mockkObject(McpServerStore)
        every { McpServerStore.token(any()) } returns KeystoreSecretStore.Stored.Absent
        every { McpServerStore.headers(any()) } returns emptyMap()
        mockkObject(McpServers)
        every { McpServers.isConfigured(any()) } answers {
            // What the check found, before the deletion it lets in.
            val configured = callOriginal()
            if (trigger() && fired.compareAndSet(false, true)) {
                val checking = Thread.currentThread()
                val id = firstArg<String>()
                val removal = thread(name = "removing") { McpSettingsViewModel { null }.deleteNow(id) }
                removals += removal
                awaitFinishedOrBlockedBy(removal, checking)
            }
            configured
        }
    }

    @After
    fun tearDown() {
        removals.forEach { it.join(10_000) }
        delegate.shutdownNow()
        unmockkObject(McpServers)
        McpConnections.closeAll()
        McpToolCatalog.clear()
        plugin.dispose()
        unmockkObject(McpServerStore)
    }

    @Test
    fun `a listing whose server is removed after the check records neither health nor tools`() {
        val server = FakeMcpServer("", listOf(tool("search", true)))
        try {
            val saved = save(server)
            val listed = AtomicBoolean(false)
            trigger = { listed.get() }

            McpToolCatalog.connect(
                saved,
                McpExchangeListener { exchange, _ ->
                    if (exchange.requestBody.text.contains("\"tools/list\"")) listed.set(true)
                },
            )
            removals.forEach { it.join(10_000) }

            assertTrue("no check ran after the listing", fired.get())
            assertNull("the listing brought back the removed server's health", McpServerHealth.of(saved.id))
            assertEquals("the listing brought back the removed server's tools", emptyList<String>(), toolNames(saved))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a listing whose server is removed before its tools are stored leaves no tools`() {
        val server = FakeMcpServer("", listOf(tool("search", true)))
        try {
            val saved = save(server)

            McpToolCatalog.connect(
                saved,
                McpExchangeListener { exchange, _ ->
                    // Deleted as the listing is answered, before anything stores its tools.
                    if (exchange.requestBody.text.contains("\"tools/list\"")) {
                        McpSettingsViewModel { null }.deleteNow(saved.id)
                    }
                },
            )

            assertTrue("the server was not deleted", McpServerStore.server(saved.id) == null)
            assertEquals("the listing stored the removed server's tools", emptyList<String>(), toolNames(saved))
            assertNull("the listing brought back the removed server's health", McpServerHealth.of(saved.id))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a failed listing whose server is removed after the check records no health`() {
        // Refuses the listing: its requests carry no token.
        val server = FakeMcpServer("required", listOf(tool("search", true)))
        try {
            val saved = save(server)
            val answered = AtomicBoolean(false)
            trigger = { answered.get() }

            val failure = runCatching {
                McpToolCatalog.connect(saved, McpExchangeListener { _, _ -> answered.set(true) })
            }.exceptionOrNull()
            removals.forEach { it.join(10_000) }

            assertTrue("the listing did not fail: $failure", failure != null)
            assertTrue("no check ran after the refusal", fired.get())
            assertNull("the failed listing brought back the removed server's health", McpServerHealth.of(saved.id))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a call whose server is removed after the check records no health`() {
        val server = FakeMcpServer("", listOf(tool("search", true)))
        try {
            val saved = save(server)
            McpToolCatalog.connect(saved)
            McpServerStore.setToolEnabled(saved.id, "search", true)
            val source = McpToolSource(delegate)
            val name = source.listTools().single().name
            val before = server.requests.size
            // Armed by the call's own request: nothing checks the server while that is answered.
            trigger = { server.requests.size > before }

            val outcome = source.invoke(
                mockk<ToolSourceRegistry.ToolInvocation> {
                    every { toolName } returns name
                    every { arguments } returns emptyMap()
                    every { callId } returns "call-1"
                }
            ).get(10, TimeUnit.SECONDS)
            removals.forEach { it.join(10_000) }

            assertTrue("the call was not answered: ${outcome.errorMessage}", outcome.isSuccess)
            assertTrue("no check ran after the call", fired.get())
            assertNull("the call brought back the removed server's health", McpServerHealth.of(saved.id))
        } finally {
            server.stop()
        }
    }

    private fun save(server: FakeMcpServer): McpServer =
        McpServerStore.saveDetails(McpServerStore.newServer("Docs", "http://127.0.0.1:${server.port}/mcp"))

    private fun toolNames(server: McpServer) = McpToolCatalog.tools(server.id).map { it.name }
}
