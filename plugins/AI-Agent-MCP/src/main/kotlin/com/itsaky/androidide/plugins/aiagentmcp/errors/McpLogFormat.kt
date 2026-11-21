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

    /**
     * How deeply a body may nest arrays and objects and still be indented. Parsing recurses once
     * per level, on Android with no limit of its own, so a server's body of nothing but `[` would
     * run the thread out of stack; no MCP reply comes near this.
     */
    internal const val MAX_JSON_DEPTH = 100

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

    /**
     * [text] as indented JSON, or null when it is not JSON. An Exception or LinkageError from
     * [indent] is a body that is not JSON; any other Error, an OutOfMemoryError above all, propagates.
     * So would a StackOverflowError, which is why [text] never reaches [indent] when it nests
     * deeper than [MAX_JSON_DEPTH]: it is shown as it came.
     * @param indent the parsing and indenting; injectable so a test can make it fail.
     */
    internal fun json(text: String, indent: (String) -> String? = ::indentJson): String? {
        if (nestsDeeperThan(text, MAX_JSON_DEPTH)) return null
        val indented = runCatchingRecoverable { indent(text) }.getOrNull()
        // org.json escapes every '/', which turns each URL in a reply into noise.
        return indented?.replace("\\/", "/")
    }

    /**
     * Whether [text] opens more than [limit] arrays or objects one inside another; one pass, no
     * recursion. A bracket inside a string does not count.
     *
     * A string starts only where a parser starts a value or a key: at a double or single quote
     * (org.json reads both) that opens the text or follows `[`, `{`, `,` or `:`, past whitespace.
     * A quote anywhere else, after a comment for instance, starts nothing here, so the brackets
     * after it count. Erring that way only leaves a body unindented; erring the other way would
     * let a parser recurse through brackets this scan took for text.
     */
    internal fun nestsDeeperThan(text: String, limit: Int): Boolean {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        var valueStarts = true
        for (c in text) {
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == quote -> quote = null
                }
                continue
            }
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') continue
            if ((c == '"' || c == '\'') && valueStarts) {
                quote = c
                valueStarts = false
                continue
            }
            when (c) {
                '[', '{' -> if (++depth > limit) return true
                ']', '}' -> if (depth > 0) depth--
            }
            valueStarts = c == '[' || c == '{' || c == ',' || c == ':'
        }
        return false
    }

    private fun indentJson(text: String): String? = when {
        text.startsWith("{") -> JSONObject(text).toString(JSON_INDENT)
        text.startsWith("[") -> JSONArray(text).toString(JSON_INDENT)
        else -> null
    }
}
