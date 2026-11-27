package com.itsaky.androidide.plugins.aiagentmcp.transport

/**
 * How deeply a server's JSON may nest arrays and objects before anything here parses it.
 *
 * org.json parses recursively, once per array or object, and Android's has no limit of its own:
 * a body of nothing but `[` runs the thread out of stack, and the StackOverflowError ends Code on
 * the Go, whose process this plugin runs in. So a server's text is scanned first, in one pass with
 * no recursion, and text nested deeper than [MAX], or not strict JSON, is never handed to a parser:
 * a reply is refused with the rule it broke ([JsonRpc.parseReply]), and a body in the connection
 * log is shown as it came.
 */
internal object JsonDepth {

    /** The deepest nesting read; no MCP reply comes near it. */
    const val MAX = 100

    /**
     * Why [refusal] refused a text, kept apart so a server's author is told the rule their reply
     * broke: a reply refused for a comment but reported as nested too deep sends them looking for
     * nesting that is not there.
     * @property reason what is wrong with the text, to follow "The server's reply to 'm' ".
     */
    sealed interface Refusal {
        val reason: String

        /** More than [limit] arrays or objects one inside another. */
        data class TooDeep(val limit: Int) : Refusal {
            override val reason: String get() = "nests arrays and objects more than $limit levels deep"
        }

        /**
         * [found] outside a string, where strict JSON never has it. A single quote is named in
         * words, since three in a row read as noise; every other character is shown in quotes.
         */
        data class NotStrict(val found: Char) : Refusal {
            override val reason: String
                get() = "is not strict JSON: a comment, single quote, ';', '=' or stray '\"' outside " +
                    "a string (found ${describe(found)})"
        }
    }

    /** [c] as a reason shows it: in single quotes, or in words for a single quote itself. */
    private fun describe(c: Char): String = if (c == '\'') "a single quote" else "'$c'"

    /**
     * Why [text] may not be parsed: it opens more than [limit] arrays or objects one inside
     * another, or it is not strict JSON where that could hide brackets; null when it may. One
     * pass, no recursion; the first rule broken is the one reported. A bracket inside a string
     * does not count.
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
    fun refusal(text: String, limit: Int = MAX): Refusal? {
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
                '\'', ';', '=', '/', '#' -> return Refusal.NotStrict(c)
                '"' -> {
                    if (last != null && last != '[' && last != '{' && last != ',' && last != ':') {
                        return Refusal.NotStrict(c)
                    }
                    inString = true
                }
                '[', '{' -> if (++depth > limit) return Refusal.TooDeep(limit)
                ']', '}' -> if (depth > 0) depth--
            }
            last = c
        }
        return null
    }
}
