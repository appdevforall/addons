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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * A cancel must always find the call it cancels, whatever its worker has reached, and a call must
 * leave nothing registered however it ends.
 *
 * The worker's first instructions used to be the only place a call became known to
 * [McpToolSource.cancel]; one landing before them set no flag and found no session, and the
 * request went out. The calls are now registered before they are scheduled. These tests force the
 * orders with latches: no sleeps, and every wait ends on a latch or on the executor terminating.
 */
class CancelAfterWorkerStartsTest {

    private val delegate = Executors.newSingleThreadExecutor()
    private lateinit var server: FakeMcpServer
    private lateinit var plugin: McpPlugin
    private lateinit var name: String
    private var requestsBefore = 0

    @Before
    fun setUp() {
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        plugin = McpPlugin().also { it.initialize(context) }
        mockkObject(McpServerStore)
        every { McpServerStore.token(any()) } returns KeystoreSecretStore.Stored.Absent
        every { McpServerStore.headers(any()) } returns emptyMap()
        server = FakeMcpServer("", listOf(tool("search", true)))
        val docs = McpServerStore.saveDetails(
            McpServerStore.newServer("Docs", "http://127.0.0.1:${server.port}/mcp")
        )
        McpToolCatalog.connect(docs)
        McpServerStore.setToolEnabled(docs.id, "search", true)
        name = McpToolSource(delegate).listTools().single().name
        requestsBefore = server.requests.size
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

    private fun invoke(source: McpToolSource): CompletableFuture<ToolSourceRegistry.ToolOutcome> =
        source.invoke(
            mockk<ToolSourceRegistry.ToolInvocation> {
                every { toolName } returns name
                every { arguments } returns emptyMap()
                every { callId } returns CALL_ID
            }
        )

    private fun awaitWorkers() {
        delegate.shutdown()
        assertTrue("the worker never finished", delegate.awaitTermination(10, TimeUnit.SECONDS))
    }

    /** The future [McpToolSource.invoke] returns completes with the scheduled future's cancellation. */
    private fun assertEndedCancelled(future: CompletableFuture<*>) {
        assertTrue("the call did not end", future.isDone)
        val failure = try {
            future.get(10, TimeUnit.SECONDS)
            null
        } catch (e: CancellationException) {
            e
        } catch (e: ExecutionException) {
            e.cause
        }
        assertTrue("the call did not end cancelled: $failure", failure is CancellationException)
    }

    private fun assertNothingSent() = assertEquals(
        "the call reached the server after it was cancelled: ${server.requests.drop(requestsBefore)}",
        requestsBefore,
        server.requests.size,
    )

    @Test
    fun `a cancel that lands after the worker started and before its first line sends nothing`() {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val source = McpToolSource(delegate) {
            started.countDown()
            assertTrue("the test never released the worker", proceed.await(10, TimeUnit.SECONDS))
        }

        val future = invoke(source)
        assertTrue("the worker never started", started.await(10, TimeUnit.SECONDS))
        source.cancel(CALL_ID)
        proceed.countDown()
        awaitWorkers()

        assertNothingSent()
        assertEndedCancelled(future)
        assertEquals("a cancelled call left its registration behind", 0, source.pendingCalls())
    }

    @Test
    fun `a call cancelled before its worker runs sends nothing and leaves no registration`() {
        val release = CountDownLatch(1)
        val held = object : ExecutorService by delegate {
            override fun execute(command: Runnable) = delegate.execute {
                assertTrue("the test never released the worker", release.await(10, TimeUnit.SECONDS))
                command.run()
            }
        }
        val source = McpToolSource(held)

        val future = invoke(source)
        source.cancel(CALL_ID)
        assertEquals("a call cancelled before it ran kept its registration", 0, source.pendingCalls())
        release.countDown()
        awaitWorkers()

        assertNothingSent()
        assertEndedCancelled(future)
        assertEquals(0, source.pendingCalls())
    }

    @Test
    fun `a normal call leaves no registration behind`() {
        val source = McpToolSource(delegate)

        val outcome = invoke(source).get(10, TimeUnit.SECONDS)

        assertTrue("the call failed: ${outcome.errorMessage}", outcome.isSuccess)
        assertTrue("the call never reached the server", server.requests.size > requestsBefore)
        assertEquals("a finished call left its registration behind", 0, source.pendingCalls())
    }

    @Test
    fun `a call whose scheduling throws leaves no registration behind`() {
        val rejecting = object : ExecutorService by delegate {
            override fun execute(command: Runnable) = throw RejectedExecutionException("full")
        }
        val source = McpToolSource(rejecting)

        try {
            invoke(source)
            fail("scheduling should have thrown")
        } catch (expected: RejectedExecutionException) {
            assertFalse(expected.message.isNullOrEmpty())
        }

        assertEquals("a call that was never scheduled kept its registration", 0, source.pendingCalls())
        assertNothingSent()
    }

    private companion object {
        const val CALL_ID = "call-1"
    }
}
