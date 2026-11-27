package com.itsaky.androidide.plugins.aiagentmcp.debugger

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Two listings of one SQLite debugger entry can overlap: the probe's, of the endpoint it read, and
 * the relisting a relaunch starts, of the endpoint that replaced it. Whichever records last, the
 * tools the agent is offered are the ones the current endpoint listed.
 */
class ReplacedEndpointListingTest {

    private val app = "com.example.app"
    private val servers = mutableListOf<FakeMcpServer>()
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
    }

    @After
    fun tearDown() {
        AutomaticServers.install(null)
        McpConnections.closeAll()
        McpToolCatalog.clear()
        servers.forEach { it.stop() }
        plugin.dispose()
        unmockkObject(McpServerStore)
    }

    private fun fake(token: String, toolName: String) =
        FakeMcpServer(token, listOf(tool(toolName, true))).also { servers += it }

    /**
     * The old endpoint's listing passes its endpoint check and is held there, by the change
     * listener its own record calls, until the new endpoint has listed and recorded.
     */
    @Test
    fun `a replaced endpoint's listing that finishes last leaves the new endpoint's tools`() {
        val first = fake("t1", "old_tool")
        val second = fake("t2", "new_tool")
        val stored = AtomicReference(listOf(AutomaticEndpoint(app, first.port, "t1")))
        AutomaticServers.install { stored.get() }
        val id = AutomaticServers.idFor(app)
        val oldEntry = AutomaticServers.servers().single()

        val olderThread = AtomicReference<Thread>()
        val olderRecorded = CountDownLatch(1)
        val releaseOlder = CountDownLatch(1)
        val holdOlder: () -> Unit = {
            if (Thread.currentThread() === olderThread.get() && olderRecorded.count > 0) {
                olderRecorded.countDown()
                releaseOlder.await(10, TimeUnit.SECONDS)
            }
        }
        AutomaticServers.addChangeListener(holdOlder)
        try {
            val older = thread(start = false) { McpToolCatalog.connect(oldEntry) }
            olderThread.set(older)
            older.start()
            assertTrue("the old endpoint's listing never recorded", olderRecorded.await(10, TimeUnit.SECONDS))

            stored.set(listOf(AutomaticEndpoint(app, second.port, "t2")))
            assertTrue(AutomaticServers.refresh())
            McpToolCatalog.connect(AutomaticServers.servers().single())

            releaseOlder.countDown()
            older.join(10_000)
            assertFalse("the old endpoint's listing never finished", older.isAlive)

            assertEquals(
                "the replaced endpoint's listing overwrote the new endpoint's tools",
                listOf("new_tool"),
                McpToolCatalog.tools(id).map { it.name },
            )
            assertEquals(listOf("new_tool"), AutomaticServers.servers().single().knownTools)
        } finally {
            releaseOlder.countDown()
            AutomaticServers.removeChangeListener(holdOlder)
        }
    }
}
