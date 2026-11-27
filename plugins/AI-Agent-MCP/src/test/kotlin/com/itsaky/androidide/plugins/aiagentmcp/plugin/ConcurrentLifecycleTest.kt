package com.itsaky.androidide.plugins.aiagentmcp.plugin

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLifecycleListener
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import com.itsaky.androidide.plugins.aiagentmcp.testing.awaitFinishedOrBlockedBy
import com.itsaky.androidide.plugins.services.SharedServices
import com.itsaky.androidide.plugins.services.ToolSourceRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A deactivation that arrives while the tool source is being registered waits for the registration
 * and then undoes it, rather than running alongside it.
 *
 * The registration used to run unguarded against [McpPlugin.deactivate]: the deactivation found
 * nothing registered yet, closed the tool source and cleared it, and the registration then
 * finished. AI Core was left holding a closed source, whose every call fails with a
 * RejectedExecutionException. The same held for `toolSource = toolSource ?: McpToolSource()`,
 * a read and a write a teardown could fall between. The lifecycle calls, and the registration AI
 * Core's activation triggers, now share one lock.
 *
 * The registry holds each registration on a latch. The deactivation runs on another thread, and the
 * test waits until it has either finished or is blocked on a lock the registering thread holds:
 * no sleeps, and no outcome that depends on timing.
 */
class ConcurrentLifecycleTest {

    /** What the registry holds now. */
    private val registered = CopyOnWriteArrayList<ToolSourceRegistry.ToolSource>()

    /** Every source ever registered and every one unregistered, in order, so a test can tell
     * that the registration happened and that the deactivation undid that very source. */
    private val registrations = CopyOnWriteArrayList<ToolSourceRegistry.ToolSource>()
    private val unregistrations = CopyOnWriteArrayList<ToolSourceRegistry.ToolSource>()

    /** What the worker threads threw, which a thread would otherwise only print. */
    private val failures = CopyOnWriteArrayList<Throwable>()

    private val registering = CountDownLatch(1)
    private val releaseRegistration = CountDownLatch(1)

    /** Matches by identity, as AI Core's does; a registration waits on [releaseRegistration]. */
    private val registry = object : ToolSourceRegistry {
        override fun getToolSources(): List<ToolSourceRegistry.ToolSource> = registered.toList()

        override fun notifyToolsChanged(providerId: String) = Unit

        override fun registerToolSource(source: ToolSourceRegistry.ToolSource) {
            registering.countDown()
            assertTrue("the test never released the registration", releaseRegistration.await(10, TimeUnit.SECONDS))
            registrations += source
            registered += source
        }

        override fun unregisterToolSource(source: ToolSourceRegistry.ToolSource) {
            unregistrations += source
            registered.removeAll { it === source }
        }
    }

    private val lifecycleListener = slot<PluginLifecycleListener>()
    private lateinit var context: PluginContext
    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        every { context.addPluginLifecycleListener(capture(lifecycleListener)) } returns Unit
        every { context.getPluginService(any(), ToolSourceRegistry::class.java) } returns null
        plugin = McpPlugin().also { it.initialize(context) }
    }

    @After
    fun tearDown() {
        releaseRegistration.countDown()
        plugin.dispose()
        SharedServices.unregister(ToolSourceRegistry::class.java)
    }

    @Test
    fun `a deactivate that arrives while activate registers the tool source leaves none registered`() {
        SharedServices.register(ToolSourceRegistry::class.java, registry)

        val activating = worker("activating") { plugin.activate() }
        assertTrue("activate never registered the tool source", registering.await(10, TimeUnit.SECONDS))
        val deactivating = worker("deactivating") { plugin.deactivate() }
        awaitFinishedOrBlockedBy(deactivating, activating)
        releaseRegistration.countDown()
        activating.join(10_000)
        deactivating.join(10_000)

        assertFalse("activate never finished", activating.isAlive)
        assertFalse("deactivate never finished", deactivating.isAlive)
        assertEquals("a tool source is still registered after the deactivate", 0, registered.size)
        assertEquals("a worker failed: $failures", emptyList<Throwable>(), failures.toList())
        assertEquals("the tool source was not registered exactly once", 1, registrations.size)
        assertEquals("the deactivation did not unregister the registered source", 1, unregistrations.size)
        assertTrue("the unregistered source is not the registered one", unregistrations.single() === registrations.single())
    }

    @Test
    fun `a deactivate that arrives while AI Core's activation registers the tool source leaves none registered`() {
        // AI Core is not active yet: activate registers nothing and leaves it to the listener.
        plugin.activate()
        assertEquals(0, registered.size)
        SharedServices.register(ToolSourceRegistry::class.java, registry)

        val registeringThread = worker("ai-core-activated") {
            lifecycleListener.captured.onPluginActivated(AI_CORE_PLUGIN_ID)
        }
        assertTrue("the listener never registered the tool source", registering.await(10, TimeUnit.SECONDS))
        val deactivating = worker("deactivating") { plugin.deactivate() }
        awaitFinishedOrBlockedBy(deactivating, registeringThread)
        releaseRegistration.countDown()
        registeringThread.join(10_000)
        deactivating.join(10_000)

        assertFalse("the registration never finished", registeringThread.isAlive)
        assertFalse("deactivate never finished", deactivating.isAlive)
        assertEquals("a tool source is still registered after the deactivate", 0, registered.size)
        assertEquals("a worker failed: $failures", emptyList<Throwable>(), failures.toList())
        assertEquals("the tool source was not registered exactly once", 1, registrations.size)
        assertEquals("the deactivation did not unregister the registered source", 1, unregistrations.size)
        assertTrue("the unregistered source is not the registered one", unregistrations.single() === registrations.single())
    }

    /** A thread running [block] that records what it throws in [failures]. */
    private fun worker(name: String, block: () -> Unit): Thread = thread(name = name) {
        try {
            block()
        } catch (e: Throwable) {
            failures += e
        }
    }

    private companion object {
        const val AI_CORE_PLUGIN_ID = "com.itsaky.androidide.plugins.aicore"
    }
}
