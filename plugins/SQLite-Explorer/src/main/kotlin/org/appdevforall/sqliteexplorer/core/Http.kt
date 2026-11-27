package org.appdevforall.sqliteexplorer.core

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP answer: its status, the session header if any, and the body as text. */
class HttpReply(val status: Int, val sessionId: String?, val body: String)

/** Where requests go. The real one is [LoopbackTransport]; tests pass a fake. */
fun interface HttpTransport {
    /** POSTs [body] (JSON) to the debugger at [port] with [headers]. Throws [UnreachableException] when nothing answers. */
    fun post(port: Int, headers: Map<String, String>, body: String): HttpReply
}

class UnreachableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * HTTP to the database debugger, which listens only on 127.0.0.1 and always answers with plain JSON.
 *
 * [HttpURLConnection] rather than a library: plugins run in Code on the Go's class loader, where a bundled
 * OkHttp clashes with the host's copy.
 */
class LoopbackTransport(
    private val connectTimeoutMs: Int = 5_000,
    // The server stops a statement after 50 s and every tool answers within 60 s.
    private val readTimeoutMs: Int = 65_000,
) : HttpTransport {
    override fun post(port: Int, headers: Map<String, String>, body: String): HttpReply {
        require(port in 1..65535) { "Port $port is out of range." }
        val connection = URL("http", "127.0.0.1", port, MCP_PATH).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val bytes = body.toByteArray(Charsets.UTF_8)
            try {
                connection.outputStream.use { it.write(bytes) }
            } catch (e: ConnectException) {
                throw UnreachableException("Nothing is listening on port $port.", e)
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return HttpReply(status, connection.getHeaderField(SESSION_HEADER)?.takeIf { it.isNotBlank() }, text)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val MCP_PATH = "/mcp"
        const val SESSION_HEADER = "Mcp-Session-Id"
    }
}
