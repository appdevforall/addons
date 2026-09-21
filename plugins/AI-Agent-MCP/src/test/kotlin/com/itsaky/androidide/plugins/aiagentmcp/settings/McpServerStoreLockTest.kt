package com.itsaky.androidide.plugins.aiagentmcp.settings

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Whether [McpServerStore]'s lock actually serialises the read-modify-write of the server list.
 *
 * The list is one JSON blob, so every mutator reads it whole and puts it back whole. A refresh on
 * `McpPlugin`'s scope and a toggle on the screen's dispatcher do interleave in practice, and the
 * loser's write simply vanishes — the switch stays on while `enabledTools` on disk no longer holds
 * it, which reads to the user as the Agent ignoring a tool they enabled.
 */
class McpServerStoreLockTest {

    private companion object {
        /** Enough concurrent writers to lose one, few enough to stay fast. */
        const val WRITERS = 8

        /** Widens the read-modify-write window; see [FakeSharedPreferences.readDelayMillis]. */
        const val READ_DELAY_MS = 2L

        const val JOIN_TIMEOUT_SECONDS = 30L
    }

    private val prefs = FakeSharedPreferences()
    private lateinit var plugin: McpPlugin
    private lateinit var serverId: String

    @Before
    fun setUp() {
        // Through the plugin's own lifecycle rather than a hook on the store: `initialize` is how
        // the host hands over the preferences [McpServerStore] then reads, so the test drives the
        // same path the device does and the store keeps no test-only surface.
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns prefs
        plugin = McpPlugin()
        plugin.initialize(context)
        serverId = McpServerStore.saveDetails(
            McpServerStore.newServer("Docs", "https://example.test/mcp")
        ).id
    }

    @After
    fun tearDown() {
        plugin.dispose()
    }

    @Test
    fun givenConcurrentToolToggles_whenTheyInterleave_thenNoWriteIsLost() {
        val tools = (1..WRITERS).map { "tool_$it" }
        McpServerStore.setKnownTools(serverId, tools)
        prefs.readDelayMillis = READ_DELAY_MS

        runTogether(tools) { McpServerStore.setToolEnabled(serverId, it, true) }

        assertEquals(tools.toSet(), McpServerStore.server(serverId)?.enabledTools)
    }

    @Test
    fun givenAToggleAndAWholeServerSwitchAtOnce_whenTheyInterleave_thenNeitherIsLost() {
        val tools = (1..WRITERS).map { "tool_$it" }
        McpServerStore.setKnownTools(serverId, tools)
        prefs.readDelayMillis = READ_DELAY_MS

        runTogether(tools + "disable") { work ->
            if (work == "disable") {
                McpServerStore.setEnabled(serverId, false)
            } else {
                McpServerStore.setToolEnabled(serverId, work, true)
            }
        }

        val stored = McpServerStore.server(serverId)
        assertEquals(tools.toSet(), stored?.enabledTools)
        assertFalse("the whole-server switch must survive the toggles", stored?.enabled ?: true)
    }

    /**
     * Runs [work] on one thread per item, all released at once.
     *
     * @param items one item per thread.
     * @param work what each thread does with its item.
     */
    private fun runTogether(items: List<String>, work: (String) -> Unit) {
        val start = CountDownLatch(1)
        val done = CountDownLatch(items.size)
        val threads = items.map { item ->
            // Daemons, and joined in a `finally` below: a writer wedged on the store's lock must not
            // outlive the assertion that noticed it and hold the Gradle test worker's JVM open.
            Thread {
                start.await()
                try {
                    work(item)
                } finally {
                    done.countDown()
                }
            }.apply {
                isDaemon = true
                start()
            }
        }
        try {
            start.countDown()
            assertTrue(
                "the writers did not finish",
                done.await(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            )
        } finally {
            threads.forEach { it.join(TimeUnit.SECONDS.toMillis(JOIN_TIMEOUT_SECONDS)) }
        }
    }
}
