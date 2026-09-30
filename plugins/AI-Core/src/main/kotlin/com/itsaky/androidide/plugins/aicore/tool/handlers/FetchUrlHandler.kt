package com.itsaky.androidide.plugins.aicore.tool.handlers

import android.net.TrafficStats
import android.util.Log
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema
import com.itsaky.androidide.plugins.aicore.tool.Validation
import com.itsaky.androidide.plugins.aicore.tool.web.WebAccess
import com.itsaky.androidide.plugins.aicore.tool.web.WebPageText
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "$LOG_PREFIX.FetchUrlHandler"

/**
 * Reads one web page or file over HTTP(S) and hands back its text, for a URL the user linked or a
 * page a search turned up. Asks first: the model picks the host, and a page it read can ask it to
 * visit another, so the dialog is where the user sees where the request is going.
 */
class FetchUrlHandler : ToolHandler {

    override val toolName = WebAccess.FETCH_URL_TOOL
    override val parametersSchema = ToolSchema.objectOf(
        "url" to ToolSchema.string(),
        required = listOf("url"),
    )
    override val requiresApproval = true
    override val argAliases = mapOf("link" to "url", "address" to "url")

    override suspend fun validate(args: Map<String, Any?>): Validation {
        val url = args["url"]?.toString()?.trim().orEmpty()
        val problem = problemWith(url)
            ?: return Validation.Accepted(args + ("url" to WebPageText.preferredUrl(url)))
        return Validation.Rejected(ToolResult.failure(problem))
    }

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val url = args["url"]?.toString()?.trim().orEmpty()
        problemWith(url)?.let { return ToolResult.failure(it) }
        return try {
            withContext(Dispatchers.IO) { fetch(url) }
        } catch (e: IOException) {
            Log.w(TAG, "fetch failed: $url", e)
            ToolResult.failure("Could not fetch $url: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * GETs [url], following redirects by hand so each hop is held to [problemWith] too: the
     * platform follows only same-scheme redirects, and checks none of them.
     */
    private fun fetch(url: String): ToolResult {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val conn = open(current)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: return ToolResult.failure("$current redirected without saying where")
                    current = URL(URL(current), location).toString()
                    problemWith(current)?.let { return ToolResult.failure("Redirected to $current: $it") }
                    return@repeat
                }
                if (code !in 200..299) {
                    return ToolResult.failure("$current answered HTTP $code")
                }
                return read(current, conn)
            } finally {
                conn.disconnect()
            }
        }
        return ToolResult.failure("$url redirected more than $MAX_REDIRECTS times")
    }

    private fun open(url: String): HttpURLConnection {
        // Tagged, or the host's debug StrictMode flags an untagged socket from plugin code.
        val previous = TrafficStats.getThreadStatsTag()
        TrafficStats.setThreadStatsTag(TRAFFIC_TAG)
        try {
            return (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,text/plain,application/json,*/*;q=0.5")
                connect()
            }
        } finally {
            TrafficStats.setThreadStatsTag(previous)
        }
    }

    private fun read(url: String, conn: HttpURLConnection): ToolResult {
        val contentType = conn.contentType
        if (!WebPageText.isReadable(contentType)) {
            return ToolResult.failure("$url is not a text page ($contentType)")
        }
        val (bytes, cut) = conn.inputStream.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var cut = false
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_BYTES) {
                    out.write(buffer, 0, MAX_BYTES - out.size())
                    cut = true
                    break
                }
                out.write(buffer, 0, n)
            }
            out.toByteArray() to cut
        }
        val raw = String(bytes, charsetOf(contentType))
        val text = if (WebPageText.isHtml(contentType, raw)) WebPageText.htmlToText(raw) else raw.trim()
        if (text.isBlank()) return ToolResult.failure("$url returned no readable text")
        val note = if (cut) " (first ${MAX_BYTES / 1024} KB only)" else ""
        return ToolResult.success("Fetched ${text.length} characters from $url$note", text)
    }

    private fun charsetOf(contentType: String?): Charset {
        val name = contentType?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')?.trim('"', ' ')
        return runCatching { name?.let(Charset::forName) }.getOrNull() ?: Charsets.UTF_8
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_BYTES = 2 * 1024 * 1024
        const val TIMEOUT_MS = 20_000
        const val USER_AGENT = "CodeOnTheGo-Agent/1.0 (+https://github.com/appdevforall/CodeOnTheGo)"

        /** `"ACWF"` in ASCII, so a raw `dumpsys netstats` shows these as the agent's web fetches. */
        const val TRAFFIC_TAG = 0x41435746

        /**
         * Why [url] cannot be fetched, or null when it can: only absolute http(s) URLs with a host,
         * so a model cannot turn this into a way to read `file://` or `content://` paths.
         */
        fun problemWith(url: String): String? {
            if (url.isEmpty()) return "url is required"
            val uri = runCatching { URI(url) }.getOrNull() ?: return "Not a valid URL: $url"
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return "Only http and https URLs can be fetched: $url"
            if (uri.host.isNullOrEmpty()) return "URL has no host: $url"
            return null
        }
    }
}
