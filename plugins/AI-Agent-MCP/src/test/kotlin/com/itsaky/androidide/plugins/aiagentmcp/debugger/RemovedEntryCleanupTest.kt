package com.itsaky.androidide.plugins.aiagentmcp.debugger

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer.Companion.tool
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpServerHealth
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A read that finds an app gone forgets that entry's health and ends its session. A later read can
 * restore the app, at a new endpoint, before the first read has finished; what the first read
 * forgets is the entry it removed, never the one that replaced it under the same id.
 */
class RemovedEntryCleanupTest {

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
     * Two apps go away in one read. That read is held by the health listener its first forgotten
     * entry tells, so the second app's cleanup is still to come, while a newer read restores the
     * second app at a new endpoint and the relisting connects to it.
     */
    @Test
    fun `a removal that finishes after the app came back leaves the new endpoint's health and session`() {
        val appA = "com.example.a"
        val appB = "com.example.b"
        val a = fake("ta", "a_tool")
        val b1 = fake("tb1", "b_tool")
        val b2 = fake("tb2", "b_tool")
        val stored = AtomicReference(
            listOf(AutomaticEndpoint(appA, a.port, "ta"), AutomaticEndpoint(appB, b1.port, "tb1"))
        )
        AutomaticServers.install { stored.get() }
        AutomaticServers.servers().forEach { McpToolCatalog.connect(it) }
        val idB = AutomaticServers.idFor(appB)
        assertEquals(McpServerHealth.State.AVAILABLE, McpServerHealth.of(idB)?.state)

        val removingThread = AtomicReference<Thread>()
        val removalHeld = CountDownLatch(1)
        val releaseRemoval = CountDownLatch(1)
        val holdRemoval: () -> Unit = {
            if (Thread.currentThread() === removingThread.get() && removalHeld.count > 0) {
                removalHeld.countDown()
                releaseRemoval.await(10, TimeUnit.SECONDS)
            }
        }
        McpServerHealth.addChangeListener(holdRemoval)
        try {
            stored.set(emptyList())
            val removal = thread(start = false) { AutomaticServers.refresh() }
            removingThread.set(removal)
            removal.start()
            assertTrue("the removal never forgot anything", removalHeld.await(10, TimeUnit.SECONDS))
            assertNull("the removal stored nothing", AutomaticServers.endpoint(idB))

            stored.set(listOf(AutomaticEndpoint(appB, b2.port, "tb2")))
            assertTrue(AutomaticServers.refresh())
            val restored = AutomaticServers.servers().single()
            McpToolCatalog.connect(restored)
            val session = McpConnections.withSession(restored) { it }

            releaseRemoval.countDown()
            removal.join(10_000)
            assertFalse("the removal never finished", removal.isAlive)

            assertEquals(
                "the removal forgot the new endpoint's tools",
                listOf("b_tool"),
                McpToolCatalog.tools(idB).map { it.name },
            )
            assertEquals(
                "the removal forgot the new endpoint's health",
                McpServerHealth.State.AVAILABLE,
                McpServerHealth.of(idB)?.state,
            )
            assertSame(
                "the removal ended the new endpoint's session",
                session,
                McpConnections.withSession(restored) { it },
            )
        } finally {
            releaseRemoval.countDown()
            McpServerHealth.removeChangeListener(holdRemoval)
        }
    }
}
