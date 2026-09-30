package com.itsaky.androidide.plugins.aiagentmcp.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests [McpServerHealth], which the agent's per-server tags read: a change is announced once,
 * an unchanged answer is not, and a re-probe of a known server never flickers it to connecting.
 */
class McpServerHealthTest {

    private companion object {
        const val SERVER = "server-1"
    }

    private var changes = 0
    private val listener: () -> Unit = { changes++ }

    @Before
    fun setUp() {
        McpServerHealth.clear()
        McpServerHealth.addChangeListener(listener)
    }

    @After
    fun tearDown() {
        McpServerHealth.removeChangeListener(listener)
        McpServerHealth.clear()
    }

    @Test
    fun givenAnUnknownServer_whenAFirstAttemptStarts_thenItReadsAsConnecting() {
        McpServerHealth.connectingIfUnknown(SERVER)

        assertEquals(McpServerHealth.State.CONNECTING, McpServerHealth.of(SERVER)?.state)
        assertEquals(1, changes)
    }

    @Test
    fun givenAWorkingServer_whenItIsProbedAgain_thenItStaysAvailable() {
        McpServerHealth.available(SERVER)

        McpServerHealth.connectingIfUnknown(SERVER)

        assertEquals(McpServerHealth.State.AVAILABLE, McpServerHealth.of(SERVER)?.state)
        assertEquals(1, changes)
    }

    @Test
    fun givenADegradedServer_whenItAnswersAgain_thenTheReasonClears() {
        McpServerHealth.degraded(SERVER, "Docs is unreachable.")

        McpServerHealth.available(SERVER)

        assertEquals(McpServerHealth.Health(McpServerHealth.State.AVAILABLE), McpServerHealth.of(SERVER))
        assertEquals(2, changes)
    }

    @Test
    fun givenTheSameAnswerTwice_whenRecorded_thenListenersHearItOnce() {
        McpServerHealth.degraded(SERVER, "Docs is unreachable.")
        McpServerHealth.degraded(SERVER, "Docs is unreachable.")

        assertEquals(1, changes)
    }

    @Test
    fun givenARemovedServer_whenForgotten_thenNothingIsKnownOfIt() {
        McpServerHealth.available(SERVER)

        McpServerHealth.forget(SERVER)

        assertNull(McpServerHealth.of(SERVER))
    }

    @Test
    fun givenAFirstAttemptUnderWay_whenItIsCancelled_thenTheServerReadsAsNeverAsked() {
        McpServerHealth.connectingIfUnknown(SERVER)

        McpServerHealth.cancelConnecting(SERVER)

        assertNull(McpServerHealth.of(SERVER))
        assertEquals(2, changes)
    }

    @Test
    fun givenAKnownAnswer_whenACancelledAttemptIsUndone_thenTheAnswerIsKept() {
        McpServerHealth.degraded(SERVER, "Docs is unreachable.")

        McpServerHealth.cancelConnecting(SERVER)

        assertEquals(McpServerHealth.State.DEGRADED, McpServerHealth.of(SERVER)?.state)
        assertEquals(1, changes)
    }
}
