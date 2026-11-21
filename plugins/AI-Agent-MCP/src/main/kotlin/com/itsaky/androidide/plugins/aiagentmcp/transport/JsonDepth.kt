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
     * Whether [text] opens more than [limit] arrays or objects one inside another, or is not strict
     * JSON where that could hide brackets; one pass, no recursion. A bracket inside a string does
     * not count.
     *
     * The scan reads strict JSON, because Android's org.json is lenient: it also reads `;` as a
     * separator, `=` and `=>` between a name and its value, `//`, `/* */` and `#` comments, single
     * quoted strings, and unquoted text that runs up to the next delimiter, a `"` included. Each is
     * a way for a parser to take a bracket as structure where a scan of strict JSON takes it as
     * text, and so to recurse past [limit] through text this scan passed. So, outside a string:
     * - `'`, `;`, `=`, `/` and `#` refuse the text: valid JSON has none of them there;
     * - a `"` opens a string where a value or a name starts, at the start or after `[`, `{`, `,` or
     *   `:`, whitespace aside, and refuses the text anywhere else, where Android's parser reads it
     *   as part of unquoted text: `[a",[[[` is one array nested in another for it, however many
     *   brackets follow the quote.
     *
     * Valid JSON is never refused for these rules, so no MCP reply is.
     */
    fun exceeds(text: String, limit: Int = MAX): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        // The last character outside a string and whitespace, or null at the start of the text.
        var last: Char? = null
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                ' ', '\t', '\n', '\r' -> continue
                '\'', ';', '=', '/', '#' -> return true
                '"' -> {
                    if (last != null && last != '[' && last != '{' && last != ',' && last != ':') return true
                    inString = true
                }
                '[', '{' -> if (++depth > limit) return true
                ']', '}' -> if (depth > 0) depth--
            }
            last = c
        }
        return false
    }
}
