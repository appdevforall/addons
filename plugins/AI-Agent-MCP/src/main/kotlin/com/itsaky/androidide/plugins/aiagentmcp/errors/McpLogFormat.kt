package com.itsaky.androidide.plugins.aiagentmcp.errors

import org.json.JSONArray
import org.json.JSONObject

/**
 * The display-only shaping the log view applies to an exchange: a status badge and readable bodies.
 * The copied transcript never goes through this, so it stays byte for byte what was kept.
 */
object McpLogFormat {

    /** How a status reads at a glance. */
    enum class Tone { SUCCESS, REDIRECT, ERROR }

    private const val SSE_DATA = "data:"

    private const val JSON_INDENT = 2

    private val STATUS_CODE = Regex("""\b([1-5]\d\d)\b""")

    /**
     * @param status the status line as received, or null when no answer arrived.
     * @return the tone of its badge; no answer reads as an error.
     */
    fun tone(status: String?): Tone = when (status?.let(::code)) {
        in 200..299 -> Tone.SUCCESS
        in 300..399 -> Tone.REDIRECT
        else -> Tone.ERROR
    }

    /**
     * @param status a status line such as `HTTP/1.1 401 Unauthorized`.
     * @return the part worth a badge, such as `401 Unauthorized`.
     */
    fun badge(status: String): String =
        if (status.startsWith("HTTP", ignoreCase = true)) status.substringAfter(' ', status) else status

    /**
     * A body made readable: JSON indented, and each JSON `data:` line of an SSE stream with it.
     * @param body the body as kept; a capped one is not valid JSON and is returned as it is.
     * @return the body to show.
     */
    fun pretty(body: String): String {
        json(body.trim())?.let { return it }
        val lines = body.trimEnd('\n').lines()
        if (lines.none { it.startsWith(SSE_DATA) }) return body
        return lines.joinToString("\n") { line ->
            if (!line.startsWith(SSE_DATA)) return@joinToString line
            json(line.removePrefix(SSE_DATA).trim())?.let { "$SSE_DATA\n$it" } ?: line
        }
    }

    private fun code(status: String): Int? = STATUS_CODE.find(status)?.groupValues?.get(1)?.toInt()

    private fun json(text: String): String? {
        val indented = runCatching {
            when {
                text.startsWith("{") -> JSONObject(text).toString(JSON_INDENT)
                text.startsWith("[") -> JSONArray(text).toString(JSON_INDENT)
                else -> null
            }
        }.getOrNull()
        // org.json escapes every '/', which turns each URL in a reply into noise.
        return indented?.replace("\\/", "/")
    }
}
