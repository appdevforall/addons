package com.itsaky.androidide.plugins.aiagentmcp.plugin

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Whether [McpPlugin] leaves a coroutine scope running past the lifecycle edge that ended it.
 *
 * The scope carries the cold-start `tools/list` refresh, which registers sessions in
 * `McpConnections` and fills `McpToolCatalog`. Left running past `deactivate`, it repopulates both
 * straight after they were cleared, leaving sockets nothing can reach — and a host that activates
 * twice would orphan the first scope entirely.
 */
class McpPluginScopeTest {

    private val prefs = FakeSharedPreferences()
    private lateinit var context: PluginContext
    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns prefs
        plugin = McpPlugin()
        plugin.initialize(context)
    }

    @After
    fun tearDown() {
        plugin.dispose()
    }

    @Test
    fun givenAnActivatedPlugin_whenActivatedAgain_thenThePreviousScopeIsCancelled() {
        plugin.activate()
        val first = requireNotNull(plugin.scopeJob)

        plugin.activate()

        val second = requireNotNull(plugin.scopeJob)
        assertTrue("the orphaned scope must not outlive the activation", first.isCancelled)
        assertNotSame("a second activation gets a scope of its own", first, second)
        assertScopeUsable(second)
    }

    @Test
    fun givenAnActivatedPlugin_whenDeactivated_thenTheScopeIsCancelled() {
        plugin.activate()
        val job = requireNotNull(plugin.scopeJob)

        plugin.deactivate()

        assertTrue("an in-flight refresh must not survive deactivation", job.isCancelled)
    }

    @Test
    fun givenAnActivatedPlugin_whenDisposed_thenTheScopeIsCancelled() {
        plugin.activate()
        val job = requireNotNull(plugin.scopeJob)

        plugin.dispose()

        assertTrue("dispose must leave nothing running", job.isCancelled)
    }

    @Test
    fun givenADeactivatedPlugin_whenActivatedAgain_thenItGetsALiveScope() {
        plugin.activate()
        plugin.deactivate()

        plugin.activate()

        assertScopeUsable(requireNotNull(plugin.scopeJob))
    }

    /**
     * What the swap onto [McpPlugin.stoppedScope] buys, under two lifecycle calls at once.
     *
     * `activate` and `deactivate` each read the scope field, install their own, and cancel what
     * they displaced. Cancelling the field in place instead — the shape before this commit — leaves
     * a cancelled scope installed as though the plugin were activated, so the scope an activation
     * handed its `tools/list` refresh to is left running with nothing holding it: a socket the
     * plugin can no longer reach, repopulating a catalogue that was just cleared.
     *
     * So whatever order the two land in, the scope an activation launched on is either the one
     * still installed (and live) or cancelled. Never a live orphan, and never a cancelled scope
     * left installed as though the plugin were activated.
     *
     * What this does *not* pin is [McpPlugin.lifecycleLock]: removing the `synchronized` from
     * `swapScope` leaves every case here green, because the read-then-write window is too narrow
     * for these threads to land inside. Reverting the swap fails it deterministically — including
     * on the sequential activate-then-deactivate case above, which is the failure it really covers.
     */
    @Test
    fun givenTwoLifecycleCallsAtOnce_whenTheyInterleave_thenNoScopeIsOrphaned() {
        val threads = Executors.newFixedThreadPool(2)
        try {
            repeat(ITERATIONS) { iteration ->
                val barrier = CyclicBarrier(2)
                // Both orders: which call reaches the field first is the whole question.
                val activateFirst = iteration % 2 == 0
                val first = threads.submit {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    if (activateFirst) plugin.activate() else plugin.deactivate()
                }
                val second = threads.submit {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    if (activateFirst) plugin.deactivate() else plugin.activate()
                }
                first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

                val installed = plugin.scopeJob
                val launched = requireNotNull(plugin.activationJob)
                assertEquals(
                    "the scope an activation launched on must be the installed one or cancelled",
                    installed !== launched,
                    launched.isCancelled,
                )
            }
        } finally {
            threads.shutdownNow()
        }
    }

    /**
     * Asserts a scope can still take work, which a cancelled one cannot.
     * @param job the activation scope's job.
     */
    private fun assertScopeUsable(job: Job) {
        assertFalse("the current activation's scope must be live", job.isCancelled)
        assertTrue("the current activation's scope must accept work", job.isActive)
    }

    private companion object {
        /** Enough interleavings to catch a lost swap; the whole loop runs in well under a second. */
        const val ITERATIONS = 400

        /** Generous: a thread that never arrives is a deadlock, not a slow machine. */
        const val TIMEOUT_SECONDS = 10L
    }
}
