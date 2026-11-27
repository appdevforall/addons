package com.itsaky.androidide.plugins.aiagentmcp.tools

import android.content.Context
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.R
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticEndpoint
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticServers
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServer
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpHttpException
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Element

/**
 * The reason the Agent tab shows when an app's SQLite debugger entry cannot be reached says what
 * to do with the app. Read from [McpServerHealth], where the Agent tab gets it, in the words of
 * this plugin's own strings.xml.
 */
class AppEndpointReasonTest {

    private val app = "com.example.probe"
    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        every { context.androidContext } returns stringsXmlContext()
        plugin = McpPlugin().also { it.initialize(context) }
    }

    @After
    fun tearDown() {
        AutomaticServers.install(null)
        McpConnections.closeAll()
        McpToolCatalog.clear()
        McpServerHealth.clear()
        plugin.dispose()
    }

    @Test
    fun `a timed-out connection to an app says Android may have paused it`() {
        val server = appEntry(33425)
        val paused = "The app com.example.probe did not answer. If the app is in the background, " +
            "Android may have paused it. Bring the app to the front and try again."

        // What Android's HttpURLConnection threw on a Galaxy Note 20 for an app frozen in the background.
        McpToolCatalog.recordFailure(
            server,
            SocketTimeoutException(
                "failed to connect to /127.0.0.1 (port 33425) from /127.0.0.1 (port 56874) after 15000ms",
            ),
        )
        assertEquals(paused, reason(server))

        // Where the system accepts the connection for a paused app, it is the read that times out.
        McpToolCatalog.recordFailure(server, SocketTimeoutException("Read timed out"))
        assertEquals(paused, reason(server))
    }

    @Test
    fun `a refused connection to an app says to open it again from Code on the Go`() {
        // A port that was free a moment ago: nothing listens there, as after the app's process died.
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val server = appEntry(port)

        runCatching { McpToolCatalog.connect(server) }

        assertEquals(
            "The app com.example.probe is not running with the SQLite debugger. " +
                "Open the app again from Code on the Go.",
            reason(server),
        )
    }

    @Test
    fun `a refusal wrapped in another exception still says to open the app again`() {
        val server = appEntry(33425)

        McpToolCatalog.recordFailure(
            server,
            IOException("request failed", ConnectException("Failed to connect to /127.0.0.1:33425")),
        )

        assertEquals(
            "The app com.example.probe is not running with the SQLite debugger. " +
                "Open the app again from Code on the Go.",
            reason(server),
        )
    }

    @Test
    fun `a server added by hand keeps the generic reason for a refusal and a timeout`() {
        val server = McpServerStore.saveDetails(McpServerStore.newServer("Docs", "http://127.0.0.1:33425/mcp"))

        McpToolCatalog.recordFailure(server, ConnectException("Failed to connect to /127.0.0.1:33425"))
        assertEquals("Could not reach Docs: Failed to connect to /127.0.0.1:33425", reason(server))

        McpToolCatalog.recordFailure(server, SocketTimeoutException("Read timed out"))
        assertEquals("Docs did not answer in time. It may be slow or offline.", reason(server))
    }

    @Test
    fun `any other failure of an app keeps the generic reason`() {
        val server = appEntry(33425)

        McpToolCatalog.recordFailure(server, IOException("socket closed"))
        assertEquals("Could not reach SQLite debugger (com.example.probe): socket closed", reason(server))

        McpToolCatalog.recordFailure(server, McpHttpException(500, ""))
        assertEquals("SQLite debugger (com.example.probe) reported a server error (HTTP 500).", reason(server))

        // A 504 is an answer: the app is running, so it is not the paused-app reason.
        McpToolCatalog.recordFailure(server, McpHttpException(504, ""))
        assertEquals(
            "SQLite debugger (com.example.probe) did not answer in time. It may be slow or offline.",
            reason(server),
        )
    }

    /** The automatic entry for [app], listening on [port] as far as Code on the Go knows. */
    private fun appEntry(port: Int): McpServer {
        AutomaticServers.install({ listOf(AutomaticEndpoint(app, port, "t1")) })
        return AutomaticServers.servers().single()
    }

    private fun reason(server: McpServer): String? = McpServerHealth.of(server.id)?.message

    /** A Context answering `getString` from this plugin's strings.xml, so the shipped words are checked. */
    private fun stringsXmlContext(): Context {
        val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
            .getElementsByTagName("string")
        val texts = (0 until nodes.length).map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent.replace("\\'", "'") }
        val text = { id: Int -> texts.getValue(names.getValue(id)) }
        return mockk(relaxed = true) {
            every { getString(any()) } answers { text(firstArg()) }
            every { getString(any(), *anyVararg()) } answers {
                String.format(text(firstArg()), *secondArg<Array<out Any?>>())
            }
        }
    }
}
