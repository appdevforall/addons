package com.itsaky.androidide.plugins.aiagentmcp.settings

import com.itsaky.androidide.plugins.aiagentmcp.testing.awaitFinishedOrBlockedBy
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * The settings dialog's load shows the token and the headers as stored together, never a token
 * from after a save beside headers from before it.
 *
 * The load read the token, then the headers, outside the lock a save writes them under. A save
 * landing between the two reads showed "no token" with the old headers. The load is made from
 * inside the headers write of a clear, which has already forgotten the token, on another thread;
 * the test waits until it has finished or is blocked on a lock the clearing thread holds.
 */
class LoadFormTogetherTest {

    @Volatile private var token = "old-token"
    @Volatile private var headers = mapOf("X-Old" to "1")

    @Before
    fun setUp() {
        mockkObject(McpServerStore)
        every { McpServerStore.token(any()) } answers {
            token.takeIf { it.isNotEmpty() }?.let { KeystoreSecretStore.Stored.Value(it) }
                ?: KeystoreSecretStore.Stored.Absent
        }
        every { McpServerStore.headers(any()) } answers { headers }
        every { McpServerStore.hasToken(any()) } answers { token.isNotEmpty() }
        every { McpServerStore.hasHeaders(any()) } answers { headers.isNotEmpty() }
        every { McpServerStore.writeToken(any(), any()) } answers { token = secondArg(); true }
    }

    @After
    fun tearDown() {
        unmockkObject(McpServerStore)
    }

    @Test
    fun `a load between the token write and the headers write shows both before or both after`() {
        val clearer = Thread.currentThread()
        var load: Thread? = null
        var state: McpSettingsViewModel.FormState? = null
        every { McpServerStore.writeHeaders(any(), any()) } answers {
            // The token is forgotten, the headers not yet.
            load = thread(name = "load") { state = McpSettingsViewModel { null }.loadFormNow("s") }
            awaitFinishedOrBlockedBy(load!!, clearer)
            headers = secondArg()
            true
        }

        McpServerStore.clearCredentials("s")
        load!!.join(10_000)

        val shown = assertNotNull_(state)
        assertEquals(
            "the load showed a token and headers from different saves",
            shown.hasToken,
            shown.headers.isNotEmpty(),
        )
    }

    private fun assertNotNull_(value: McpSettingsViewModel.FormState?): McpSettingsViewModel.FormState {
        assertNotNull("the load never finished", value)
        return value!!
    }
}
