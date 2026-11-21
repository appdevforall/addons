package com.itsaky.androidide.plugins.aiagentmcp.settings

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer.Companion.tool
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import com.itsaky.androidide.plugins.aiagentmcp.testing.awaitFinishedOrBlockedBy
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpToolCatalog
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Saving a server's new URL and token ends the old session with the old credentials, and a request
 * reading the credentials meanwhile gets the old URL's or the new URL's, never one with the other.
 *
 * The save stored the URL, then the token, then the headers, and only then ended the old session.
 * Its DELETE read the credentials for the old URL once the server had left it, so it carried none,
 * and a server that wants its token to end a session refused it and kept the session open. A
 * request reading the credentials between the URL write and the token write passed the URL check
 * for the new URL and sent it the old token and headers. The session is now taken out first, with
 * the credentials it had, the three are written in one step a request's read waits for, and the
 * session is ended last.
 *
 * The request between the two writes is made from inside the token write, on another thread, and
 * the test waits until it has finished or is blocked on a lock the saving thread holds. Nothing
 * sleeps.
 */
class SaveServerSessionTest {

    private val old = FakeMcpServer("old-token", listOf(tool("search", true)), sessionId = "old-session")
    private val moved = FakeMcpServer("new-token", listOf(tool("search", true)))

    // The Keystore is not there on the JVM: the store answers what was last written.
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
        every { McpServerStore.writeToken(any(), any()) } answers { token = secondArg(); true }
        every { McpServerStore.writeHeaders(any(), any()) } answers { headers = secondArg(); true }
    }

    @After
    fun tearDown() {
        McpConnections.closeAll()
        McpToolCatalog.clear()
        old.stop()
        moved.stop()
        plugin.dispose()
        unmockkObject(McpServerStore)
    }

    @Test
    fun `editing the URL and the token ends the old session with the old token, and the new URL gets the new one`() {
        val saved = McpServerStore.saveDetails(McpServerStore.newServer("Docs", url(old)))
        McpConnections.withSession(saved) { it.initialize() }

        McpSettingsViewModel { null }.saveNow(saved.copy(url = url(moved)), "new-token", mapOf("X-New" to "1"))

        val delete = old.requests.single { it.httpMethod == "DELETE" }
        assertEquals("the old session's DELETE did not carry the old token", "Bearer old-token", delete.authorization)
        assertTrue("the old session's DELETE did not carry the old headers", "x-old" in delete.headerNames)
        assertFalse("the old URL was sent the new headers", "x-new" in delete.headerNames)

        McpConnections.withSession(McpServerStore.server(saved.id)!!) { it.initialize() }
        val first = moved.requests.first()
        assertEquals("the new URL's first request did not carry the new token", "Bearer new-token", first.authorization)
        assertTrue("the new URL's first request did not carry the new headers", "x-new" in first.headerNames)
        assertFalse("the new URL was sent the old headers", "x-old" in first.headerNames)
    }

    @Test
    fun `a request between the URL write and the token write gets the old pair or the new, never a mix`() {
        val saved = McpServerStore.saveDetails(McpServerStore.newServer("Docs", url(old)))
        val saver = Thread.currentThread()
        var lookup: Thread? = null
        var found: Thread.State? = null
        every { McpServerStore.writeToken(saved.id, any()) } answers {
            // The URL is written, the token not yet: a request for the server as it now stands.
            val current = McpServerStore.server(saved.id)!!
            lookup = thread(name = "lookup") {
                runCatching { McpConnections.withSession(current) { it.initialize() } }
            }
            found = awaitFinishedOrBlockedBy(lookup!!, saver)
            token = secondArg()
            true
        }

        McpSettingsViewModel { null }.saveNow(saved.copy(url = url(moved)), "new-token", mapOf("X-New" to "1"))
        lookup!!.join(10_000)

        assertTrue("the request never reached the new URL", moved.requests.isNotEmpty())
        moved.requests.forEach { request ->
            assertEquals(
                "a request read between the URL write and the token write (found $found) sent the new URL",
                "Bearer new-token" to setOf("x-new"),
                request.authorization to request.headerNames.filterTo(mutableSetOf()) { it.startsWith("x-") },
            )
        }
    }

    @Test
    fun `clearing the credentials ends the session with the ones it had`() {
        val saved = McpServerStore.saveDetails(McpServerStore.newServer("Docs", url(old)))
        McpConnections.withSession(saved) { it.initialize() }

        val cleared = McpSettingsViewModel { null }.clearCredentialNow(saved.id)

        assertTrue(cleared)
        val delete = old.requests.single { it.httpMethod == "DELETE" }
        assertEquals("the session's DELETE did not carry its token", "Bearer old-token", delete.authorization)
        assertTrue("the session's DELETE did not carry its headers", "x-old" in delete.headerNames)
    }

    @Test
    fun `a save that throws still ends the detached session with the old credentials`() {
        val saved = McpServerStore.saveDetails(McpServerStore.newServer("Docs", url(old)))
        McpConnections.withSession(saved) { it.initialize() }
        every { McpServerStore.saveConnection(any(), any(), any()) } throws IllegalStateException("disk full")

        val thrown = runCatching {
            McpSettingsViewModel { null }.saveNow(saved.copy(url = url(moved)), "new-token", mapOf("X-New" to "1"))
        }.exceptionOrNull()

        assertTrue("the save's failure was swallowed", thrown is IllegalStateException)
        val delete = old.requests.singleOrNull { it.httpMethod == "DELETE" }
        assertTrue("the detached session was left open on the server", delete != null)
        assertEquals("the DELETE did not carry the old token", "Bearer old-token", delete!!.authorization)
    }

    @Test
    fun `clearing that throws still ends the detached session with the old credentials`() {
        val saved = McpServerStore.saveDetails(McpServerStore.newServer("Docs", url(old)))
        McpConnections.withSession(saved) { it.initialize() }
        every { McpServerStore.clearCredentials(any()) } throws IllegalStateException("disk full")

        val thrown = runCatching { McpSettingsViewModel { null }.clearCredentialNow(saved.id) }.exceptionOrNull()

        assertTrue("the clear's failure was swallowed", thrown is IllegalStateException)
        val delete = old.requests.singleOrNull { it.httpMethod == "DELETE" }
        assertTrue("the detached session was left open on the server", delete != null)
        assertEquals("the DELETE did not carry the old token", "Bearer old-token", delete!!.authorization)
    }

    private fun url(server: FakeMcpServer) = "http://127.0.0.1:${server.port}/mcp"
}
