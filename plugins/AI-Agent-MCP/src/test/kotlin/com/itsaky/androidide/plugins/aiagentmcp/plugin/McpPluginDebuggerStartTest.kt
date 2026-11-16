package com.itsaky.androidide.plugins.aiagentmcp.plugin

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticServers
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import com.itsaky.androidide.plugins.services.DebuggerEndpoint
import com.itsaky.androidide.plugins.services.DebuggerEndpointService
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * An activation that ends during the host's blocking first read of the debugger endpoints must
 * leave no source and no listener behind: nothing removes them after [McpPlugin.deactivate].
 */
class McpPluginDebuggerStartTest {

    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        AutomaticServers.install(null)
        val endpoint = mockk<DebuggerEndpoint> {
            every { applicationId } returns "com.example.app"
            every { port } returns 4000
            every { token } returns "t1"
        }
        val service = mockk<DebuggerEndpointService> {
            every { endpoints } answers {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                listOf(endpoint)
            }
        }
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        every { context.services.get(DebuggerEndpointService::class.java) } returns service
        plugin = McpPlugin().also { it.initialize(context) }
    }

    @After
    fun tearDown() {
        release.countDown()
        plugin.dispose()
        AutomaticServers.install(null)
    }

    @Test
    fun `an activation that ends during the first read installs nothing and adds no listener`() {
        val listeners = AutomaticServers.endpointListenerCount
        val active = AtomicBoolean(true)
        val start = Thread { plugin.startDebuggerEntry { active.get() } }.also { it.start() }
        assertTrue("the read never started", entered.await(10, TimeUnit.SECONDS))

        active.set(false)
        release.countDown()
        start.join(10_000)

        assertTrue(AutomaticServers.servers().isEmpty())
        assertEquals(listeners, AutomaticServers.endpointListenerCount)
    }

    @Test
    fun `a live activation installs the entry and the listener once`() {
        val listeners = AutomaticServers.endpointListenerCount
        release.countDown()
        plugin.startDebuggerEntry { true }

        assertEquals(1, AutomaticServers.servers().size)
        assertEquals(listeners + 1, AutomaticServers.endpointListenerCount)
    }
}
