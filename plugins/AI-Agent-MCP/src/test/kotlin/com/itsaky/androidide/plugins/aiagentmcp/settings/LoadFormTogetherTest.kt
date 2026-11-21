package com.itsaky.androidide.plugins.aiagentmcp.settings

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import com.itsaky.androidide.plugins.aiagentmcp.testing.awaitFinishedOrBlockedBy
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import io.mockk.every
import io.mockk.mockk
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
 * the test waits until it has finished or is blocked on a lock the clearing thread holds. The
 * other direction is covered too: a save made from inside the load's token read, which must wait
 * for the load to finish.
 */
class LoadFormTogetherTest {

    @Volatile private var token = "old-token"
    @Volatile private var headers = mapOf("X-Old" to "1")

    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        plugin = McpPlugin().also { it.initialize(context) }
        mockkObject(McpServerStore)
        every { McpServerStore.token(any()) } answers {
            token.takeIf { it.isNotEmpty() }?.let { KeystoreSecretStore.Stored.Value(it) }
                ?: KeystoreSecretStore.Stored.Absent
        }
        every { McpServerStore.headers(any()) } answers { headers }
        every { McpServerStore.hasToken(any()) } answers { token.isNotEmpty() }
        every { McpServerStore.hasHeaders(any()) } answers { headers.isNotEmpty() }
        every { McpServerStore.writeToken(any(), any()) } answers { token = secondArg(); true }
        every { McpServerStore.writeHeaders(any(), any()) } answers { headers = secondArg(); true }
    }

    @After
    fun tearDown() {
        plugin.dispose()
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

    @Test
    fun `a save between the token read and the headers read does not mix the two`() {
        val saved = McpServerStore.saveDetails(McpServerStore.newServer("Docs", "https://example.invalid/mcp"))
        val reader = Thread.currentThread()
        var saver: Thread? = null
        var found: Thread.State? = null
        every { McpServerStore.token(saved.id) } answers {
            val read = token.takeIf { it.isNotEmpty() }?.let { KeystoreSecretStore.Stored.Value(it) }
                ?: KeystoreSecretStore.Stored.Absent
            if (saver == null) {
                // The token is read, the headers not yet: a save that forgets the token and
                // replaces the headers.
                saver = thread(name = "saver") {
                    McpServerStore.saveConnection(saved, "", mapOf("X-New" to "1"))
                }
                found = awaitFinishedOrBlockedBy(saver!!, reader)
            }
            read
        }

        val shown = McpSettingsViewModel { null }.loadFormNow(saved.id)
        saver!!.join(10_000)

        assertEquals(
            "the form mixed one save's token with another's headers (saver found $found)",
            true to mapOf("X-Old" to "1"),
            shown.hasToken to shown.headers,
        )
    }

    private fun assertNotNull_(value: McpSettingsViewModel.FormState?): McpSettingsViewModel.FormState {
        assertNotNull("the load never finished", value)
        return value!!
    }
}
