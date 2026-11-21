package com.itsaky.androidide.plugins.aiagentmcp.transport

/**
 * How deeply a server's JSON may nest arrays and objects before anything here parses it.
 *
 * org.json parses recursively, once per array or object, and Android's has no limit of its own:
 * a body of nothing but `[` runs the thread out of stack, and the StackOverflowError ends Code on
 * the Go, whose process this plugin runs in. So a server's text is scanned first, in one pass with
 * no recursion, and text nested deeper than [MAX] is never handed to a parser: a reply is refused
 * ([JsonRpc.parseReply]), and a body in the connection log is shown as it came.
 */
internal object JsonDepth {

    /** The deepest nesting read; no MCP reply comes near it. */
    const val MAX = 100

    /**
     * Whether [text] opens more than [limit] arrays or objects one inside another; one pass, no
     * recursion. A bracket inside a string does not count.
     *
     * A string starts only where a parser starts a value or a key: at a double or single quote
     * (org.json reads both) that opens the text or follows `[`, `{`, `,` or `:`, past whitespace.
     * A quote anywhere else, after a comment for instance, starts nothing here, so the brackets
     * after it count. Erring that way only refuses a body a parser could have read; erring the
     * other way would let a parser recurse through brackets this scan took for text.
     */
    fun exceeds(text: String, limit: Int = MAX): Boolean {
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
}
