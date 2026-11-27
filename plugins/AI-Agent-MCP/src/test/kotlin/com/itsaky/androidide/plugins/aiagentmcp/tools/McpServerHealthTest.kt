package com.itsaky.androidide.plugins.aiagentmcp.tools

import com.itsaky.androidide.plugins.aiagentmcp.testing.awaitFinishedOrBlockedBy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.concurrent.thread

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

    @Test
    fun givenARefusalBeingRecorded_whenTheCredentialChangesMeanwhile_thenTheRefusalIsCleared() {
        McpServerHealth.degraded(SERVER, "refused", refused = true)
        var changing: Thread? = null

        // The credential changes while the refused write holds the lock, between its check and
        // its write; the change must wait for the write and then clear it.
        McpServerHealth.degraded(SERVER, "refused", refused = true) {
            changing = thread(name = "changing") { McpServerHealth.credentialChanged(SERVER) }
            awaitFinishedOrBlockedBy(changing!!, Thread.currentThread())
            true
        }
        changing!!.join(10_000)

        assertFalse("the refusal outlived the credential change", McpServerHealth.of(SERVER)!!.refused)
        assertTrue(McpServerHealth.of(SERVER)?.state == McpServerHealth.State.DEGRADED)
    }
}
